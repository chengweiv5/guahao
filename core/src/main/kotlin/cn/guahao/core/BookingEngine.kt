package cn.guahao.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class BookingEngine(private val gateway: BookingGateway, private val store: TaskStore, private val clock: BookingClock) {
    private val runMutex = Mutex()
    fun stop(id: String) {
        store.update(id) { it.copy(stopRequested = true, phase = if (it.attempt != null && it.order == null)
            TaskPhase.RECONCILING else if (it.order != null) it.phase else TaskPhase.STOPPED,
            note = if (it.attempt != null && it.order == null) "已停止查号，正在核对已发出的请求" else "任务已停止；医院订单不受影响") }
    }
    private fun phase(id: String, phase: TaskPhase, note: String) = store.update(id) {
        it.copy(phase = phase, note = note, lastEventAt = clock.now())
    }
    suspend fun run(taskId: String, generation: Long, owner: String) {
        if (!runMutex.tryLock()) return
        var claimed = false
        try {
            claimed = store.claim(taskId, generation, owner)
            if (!claimed) return
            val initial = store.get(taskId)
            val t = initial.task
            if (initial.order != null || initial.manuallyResolved) return
            if (initial.attempt != null) { reconcile(t, initial.attempt); return }
            if (initial.lastEventAt?.isAfter(clock.now().plusSeconds(60)) == true) {
                phase(t.id, TaskPhase.NEEDS_ATTENTION, "系统时间发生变化，请检查放号时间后重新设置"); return
            }
            if (initial.phase !in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING)) return
            var failures = 0
            while (true) {
                val r = store.get(taskId)
                if (r.stopRequested) { phase(taskId, TaskPhase.STOPPED, "已停止查号"); return }
                if (!clock.now().isBefore(t.deadline)) { phase(taskId, TaskPhase.EXPIRED, "运行时间已结束，未取得符合条件的号源"); return }
                if (clock.now().isBefore(t.releaseAt)) {
                    phase(taskId, TaskPhase.WAITING, "等待放号")
                    clock.delayMillis(minOf(1000, java.time.Duration.between(clock.now(), t.releaseAt).toMillis()))
                    continue
                }
                try {
                    phase(taskId, TaskPhase.SEARCHING, "正在查询符合条件的号源")
                    val candidate = gateway.candidates(t.condition).firstOrNull { eligible(t, it, clock.now()) }
                    if (candidate != null && canSubmit(t)) {
                        if (!gateway.validateBookingAccess(t.condition.patient)) throw HospitalException("医院会话或挂号权限不可用，请重新接入")
                        if (!canSubmit(t)) continue
                        val baseline = gateway.orders(t.condition.patient, t.condition.visitDate, t.condition.visitDate)
                        if (baseline.any { satisfiesCondition(it, t) && (it.invalidAt == null || it.invalidAt.isAfter(clock.now())) }) {
                            phase(taskId, TaskPhase.NEEDS_ATTENTION, "医院已有同条件订单，请到官方页面核对，已暂停新提交"); return
                        }
                        if (!canSubmit(t) || !eligible(t, candidate, clock.now())) continue
                        val attempt = SubmissionAttempt(UUID.randomUUID().toString(), t.id, candidate, clock.now(), baseline.map { it.orderNo }.toSet())
                        val saved = store.update(t.id) {
                            if (it.stopRequested || !clock.now().isBefore(t.deadline) || it.attempt != null) it
                            else it.copy(attempt = attempt, phase = TaskPhase.SUBMITTING, note = "正在提交，请等待医院结果")
                        }
                        if (saved.attempt?.id != attempt.id) continue
                        // A crash or stop after this durable record must never cause a second submission.
                        if (!canSubmit(t)) { reconcile(t, attempt); return }
                        val reply = try { gateway.lock(t, candidate) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { LockReply.OutcomeUnknown }
                        when (reply) {
                            LockReply.NoStock -> store.update(t.id) { it.copy(attempt = null) }
                            is LockReply.Rejected -> { phase(t.id, TaskPhase.NEEDS_ATTENTION, "医院未确认锁号（${reply.code}），请核对官方结果"); return }
                            else -> { if (reconcile(t, attempt)) return }
                        }
                    }
                    failures = 0
                    clock.delayMillis(5000)
                } catch (e: CancellationException) { throw e }
                catch (e: HospitalException) {
                    if (!e.retryable) { phase(t.id, TaskPhase.NEEDS_ATTENTION, e.safeMessage); return }
                    phase(t.id, TaskPhase.SEARCHING, "网络暂不可用，等待后重试")
                    clock.delayMillis(maxOf(listOf(5000L, 10000, 20000, 40000, 60000)[minOf(failures++, 4)], e.retryAfterMillis ?: 0))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { phase(taskId, TaskPhase.NEEDS_ATTENTION, "任务需要处理，请核对医院结果后再操作") }
        finally { if (claimed) store.release(taskId, owner); runMutex.unlock() }
    }
    private fun canSubmit(t: BookingTask) = !store.get(t.id).stopRequested && clock.now().isBefore(t.deadline)
    /** Returns false only when the server unambiguously reports no stock. */
    private suspend fun reconcile(t: BookingTask, attempt: SubmissionAttempt): Boolean {
        phase(t.id, TaskPhase.RECONCILING, "提交结果待确认，正在核对医院订单")
        var orderNo = attempt.orderNo
        var paymentContext = attempt.paymentContext
        var polls = 0
        val limit = attempt.sentAt.plusSeconds(120)
        while (clock.now().isBefore(limit)) {
            var retryAfter = 5000L
            try {
                if (orderNo == null && polls++ < 30) {
                    when (val reply = gateway.querySubmission(t.condition.patient)) {
                        is AsyncReply.OrderFound -> {
                            orderNo = reply.orderNo
                            paymentContext = reply.paymentContext
                            store.update(t.id) { it.copy(attempt = it.attempt?.copy(orderNo = orderNo, paymentContext = paymentContext)) }
                        }
                        // User-scoped async no-stock does not establish that our uncertain request failed.
                        else -> Unit
                    }
                }
                val orders = gateway.orders(t.condition.patient, t.condition.visitDate, t.condition.visitDate)
                val matches = orders.filter { it.orderNo !in attempt.baselineOrderNos && matches(it, t, attempt.candidate) }
                val order = matches.singleOrNull()?.takeIf { it.orderNo == orderNo }?.let {
                    it.copy(insuranceSupported = paymentContext?.insuranceSupported,
                        specialPaymentCondition = it.specialPaymentCondition || paymentContext?.requiresUserChoice == true || paymentContext?.zeroFee == true)
                }
                if (order != null) {
                    store.update(t.id) { it.copy(order = order, phase = if (order.phase == OrderPhase.BOOKED) TaskPhase.BOOKED else TaskPhase.AWAITING_PAYMENT,
                        note = if (order.phase == OrderPhase.BOOKED) "挂号已完成" else "锁号成功，待付款", lastEventAt = clock.now()) }
                    return true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: HospitalException) { retryAfter = maxOf(retryAfter, e.retryAfterMillis ?: 0) }
            catch (_: Exception) { /* Preserve attempt; parse errors never mean no order. */ }
            clock.delayMillis(minOf(retryAfter, java.time.Duration.between(clock.now(), limit).toMillis().coerceAtLeast(1)))
        }
        phase(t.id, TaskPhase.NEEDS_ATTENTION, "结果待人工核对；请打开服务号挂号结果查询，确认前不会再次提交")
        return true
    }
}
