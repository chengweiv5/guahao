package cn.guahao.core

import kotlinx.coroutines.CancellationException

enum class PaymentAction { BOOKED, OFFICIAL_PAYMENT, INSURANCE_PAYMENT, INITIALIZE_INSURANCE, NEEDS_ATTENTION }
fun paymentAction(order: OrderSnapshot, preference: PaymentPreference) = when {
    order.specialPaymentCondition -> PaymentAction.NEEDS_ATTENTION
    order.phase == OrderPhase.BOOKED -> PaymentAction.BOOKED
    order.phase == OrderPhase.INSURANCE_PAID -> if (order.insuranceVerified) PaymentAction.BOOKED else PaymentAction.NEEDS_ATTENTION
    order.source != "2" -> PaymentAction.NEEDS_ATTENTION
    order.phase == OrderPhase.INSURANCE_PENDING -> PaymentAction.INSURANCE_PAYMENT
    order.phase != OrderPhase.LOCKED -> PaymentAction.NEEDS_ATTENTION
    preference == PaymentPreference.INSURANCE_FIRST -> PaymentAction.INITIALIZE_INSURANCE
    else -> PaymentAction.OFFICIAL_PAYMENT
}
class PaymentCoordinator(private val gateway: BookingGateway, private val store: TaskStore, private val clock: BookingClock) {
    suspend fun prepare(id: String) {
        var r = store.get(id)
        val o = r.order ?: return
        if (paymentAction(o, r.task.paymentPreference) != PaymentAction.INITIALIZE_INSURANCE) { refresh(id); return }
        if (o.insuranceSupported != true || o.feeFen == 0L || o.invalidAt == null || !o.invalidAt.isAfter(clock.now()) || !gateway.insuranceAvailable(o.patient)) {
            store.update(id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "已锁号，医保或付款条件需在官方页面确认") }; return
        }
        var newlyRecorded = false
        r = store.update(id) {
            if (it.insuranceStartedAt != null) it else { newlyRecorded = true; it.copy(insuranceStartedAt = clock.now()) }
        }
        if (newlyRecorded) {
            val reply = try { gateway.initializeInsurance(o.patient, o.orderNo) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { InsuranceReply.UNKNOWN }
            store.update(id) { it.copy(insuranceResult = reply.name) }
        }
        val limit = r.insuranceStartedAt!!.plusSeconds(120)
        do {
            try { refresh(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (store.get(id).order?.phase in setOf(OrderPhase.INSURANCE_PENDING, OrderPhase.INSURANCE_PAID)) return
            if (!clock.now().isBefore(limit)) break
            clock.delayMillis(minOf(5000, java.time.Duration.between(clock.now(), limit).toMillis()))
        } while (true)
        store.update(id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "已锁号，医保准备结果需处理；请到服务号核对，不会改为全额支付") }
    }
    suspend fun refresh(id: String) {
        val r = store.get(id)
        val old = r.order ?: return
        val list = gateway.orders(old.patient, old.visitDate, old.visitDate)
        var fresh = list.singleOrNull { it.orderNo == old.orderNo && it.patient == old.patient }
            ?: throw HospitalException("未查到同一医院订单，请稍后刷新或在服务号核对")
        fresh = fresh.copy(insuranceSupported = old.insuranceSupported,
            specialPaymentCondition = fresh.specialPaymentCondition || old.specialPaymentCondition)
        val c = r.attempt?.candidate
        if (c != null && !matches(fresh, r.task, c) && fresh.phase != OrderPhase.OTHER) throw HospitalException("医院订单信息有变化，请到官方页面核对")
        var paid: InsuranceReply? = null
        if (r.task.paymentPreference == PaymentPreference.INSURANCE_FIRST || fresh.phase in setOf(OrderPhase.INSURANCE_PENDING, OrderPhase.INSURANCE_PAID)) {
            paid = try { gateway.paymentState(old.patient, old.orderNo) } catch (e: CancellationException) { throw e } catch (_: Exception) { InsuranceReply.UNKNOWN }
            fresh = fresh.copy(insuranceVerified = paid == InsuranceReply.PAID && fresh.phase == OrderPhase.INSURANCE_PAID)
        }
        val action = paymentAction(fresh, r.task.paymentPreference)
        val note = when {
            action == PaymentAction.BOOKED -> "挂号已完成"
            fresh.phase == OrderPhase.INSURANCE_PAID -> "医院已挂号，医保付款状态核对中"
            paid == InsuranceReply.PAID -> "医保支付成功，挂号结果核对中"
            action == PaymentAction.INSURANCE_PAYMENT -> "锁号成功，待医保付款"
            action == PaymentAction.OFFICIAL_PAYMENT -> "锁号成功，待付款"
            else -> "已锁号，请在医院官方页面核对付款条件"
        }
        store.update(id) { it.copy(order = fresh, phase = when(action) {
            PaymentAction.BOOKED -> TaskPhase.BOOKED
            PaymentAction.INSURANCE_PAYMENT, PaymentAction.OFFICIAL_PAYMENT -> TaskPhase.AWAITING_PAYMENT
            else -> TaskPhase.NEEDS_ATTENTION
        }, note = note, lastEventAt = clock.now()) }
    }
}
