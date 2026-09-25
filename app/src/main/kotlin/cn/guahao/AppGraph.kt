package cn.guahao

import android.app.Application
import android.content.Context
import cn.guahao.core.*
import cn.guahao.core.psc.PscSession
import cn.guahao.hospital.*
import cn.guahao.storage.*
import cn.guahao.runtime.*
import cn.guahao.notifications.BookingNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

class GuahaoApplication : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
val Context.graph: AppGraph get() = (applicationContext as GuahaoApplication).graph

class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val vault = EncryptedVault(context)
    val store = BookingDatabase(context, vault).apply { recoverProcessOwnership() }
    val sessions = SessionRepository(vault, Mutex())
    val hospital = PscClient(sessions)
    val demo = DemoGateway(store)
    val gateway: BookingGateway = object : BookingGateway {
        fun forPatient(p: PatientRef) = if (p.sessionId == "demo") demo else hospital
        override suspend fun departments(patient: PatientRef) = forPatient(patient).departments(patient)
        override suspend fun candidates(condition: VisitCondition) = forPatient(condition.patient).candidates(condition)
        override suspend fun validateBookingAccess(patient: PatientRef) = forPatient(patient).validateBookingAccess(patient)
        override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate) = forPatient(patient).orders(patient, from, to)
        override suspend fun lock(task: BookingTask, candidate: Candidate) = forPatient(task.condition.patient).lock(task, candidate)
        override suspend fun querySubmission(patient: PatientRef) = forPatient(patient).querySubmission(patient)
        override suspend fun insuranceAvailable(patient: PatientRef) = forPatient(patient).insuranceAvailable(patient)
        override suspend fun initializeInsurance(patient: PatientRef, orderNo: String) = forPatient(patient).initializeInsurance(patient, orderNo)
        override suspend fun paymentState(patient: PatientRef, orderNo: String) = forPatient(patient).paymentState(patient, orderNo)
    }
    val clock = AndroidBookingClock()
    val engine = BookingEngine(gateway, store, clock)
    private val paymentGate = Mutex()
    val payment = PaymentCoordinator(gateway, store, clock)
    val scheduler = AlarmScheduler(context)
    val notifications = BookingNotifications(context)
    fun hasUnresolvedOrActive(excluding: String? = null) = store.all().any { it.task.id != excluding &&
        (it.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING) ||
            (it.attempt != null && it.order == null && !it.manuallyResolved)) }
    suspend fun importSession(raw: String): PscSession {
        check(!hasUnresolvedOrActive()) { "请先停止任务并处理未决提交，再重新连接医院" }
        return sessions.importAndVerify(raw)
    }
    suspend fun enable(id: String) {
        check(!hasUnresolvedOrActive(id)) { "已有任务正在等待、执行或核对，请先处理" }
        val r = store.get(id)
        check(r.phase == TaskPhase.DRAFT && r.attempt == null)
        check(r.task.releaseAt.isAfter(clock.now())) { "放号时间已过，请调整后重新启用" }
        val sessionValid = r.task.demo || runCatching { sessions.load(r.task.condition.patient) }.isSuccess
        check(readiness(context, sessionValid).ready) { "请先补齐医院连接、通知、精确定时和后台运行准备" }
        if (!r.task.demo) {
            check(gateway.validateBookingAccess(r.task.condition.patient)) { "医院会话不可用，请重新连接" }
            check(gateway.departments(r.task.condition.patient).contains(r.task.condition.department)) { "医院科室信息已变化，请重新选择" }
            check(gateway.candidates(r.task.condition).any { it.doctorCode == r.task.condition.doctorCode && it.doctorName == r.task.condition.doctorName }) { "未能核实医生与科室的关联，请刷新后选择" }
        }
        store.update(id) { it.copy(phase = TaskPhase.WAITING, stopRequested = false, note = "等待放号", lastEventAt = clock.now()) }
        try { scheduler.schedule(r.task) }
        catch (e: Exception) { store.update(id) { it.copy(phase = TaskPhase.DRAFT, note = "定时失败，请检查权限后重新启用") }; throw e }
    }
    fun stop(id: String) { engine.stop(id); scheduler.cancel(store.get(id).task) }
    suspend fun preparePayment(id: String) = paymentGate.withLock {
        try { payment.prepare(id) }
        catch (_: HospitalException) { store.update(id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "已锁号，付款信息未取得，请刷新或到服务号核对") } }
    }
    suspend fun refreshPayment(id: String) = paymentGate.withLock { payment.refresh(id) }
    suspend fun manualReconcile(id: String) = paymentGate.withLock {
        val r = store.get(id)
        val attempt = r.attempt ?: return@withLock
        if (r.order != null || r.manuallyResolved) return@withLock
        val number = attempt.orderNo ?: (gateway.querySubmission(r.task.condition.patient) as? AsyncReply.OrderFound)?.orderNo
        val matches = gateway.orders(r.task.condition.patient, r.task.condition.visitDate, r.task.condition.visitDate)
            .filter { it.orderNo !in attempt.baselineOrderNos && matches(it, r.task, attempt.candidate) }
        val found = matches.singleOrNull()?.takeIf { it.orderNo == number }
            ?: throw HospitalException("未找到可唯一关联的订单，请在微信服务号人工核对。列表为空不代表提交失败。")
        store.update(id) { it.copy(order = found.copy(insuranceSupported = attempt.paymentContext?.insuranceSupported,
            specialPaymentCondition = found.specialPaymentCondition || attempt.paymentContext?.requiresUserChoice == true),
            phase = TaskPhase.AWAITING_PAYMENT, note = "已找到同一订单，请刷新付款结果", lastEventAt = clock.now()) }
        payment.refresh(id)
    }
    fun acknowledgeManualResolution(id: String) {
        val r = store.get(id)
        check(r.phase == TaskPhase.NEEDS_ATTENTION && r.order == null && r.attempt != null)
        check(!clock.now().isBefore(r.attempt!!.sentAt.plusSeconds(120)))
        store.update(id) { it.copy(phase = TaskPhase.STOPPED, manuallyResolved = true, stopRequested = true,
            note = "用户已在医院核对并结束本任务；原提交记录保留，未取消任何医院订单") }
        scheduler.cancel(r.task)
    }
    fun recover() {
        if (!readiness(context, true).unlocked) return
        val now = clock.now()
        val lastWall = context.getSharedPreferences("runtime", Context.MODE_PRIVATE).getLong("lastWall", 0)
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putLong("lastWall", maxOf(lastWall, now.toEpochMilli())).apply()
        for (r in store.all()) {
            if (r.manuallyResolved) continue
            if (r.order != null) {
                val preparationUntil = r.insuranceStartedAt?.plusSeconds(120)
                if (r.order!!.phase == OrderPhase.LOCKED && r.task.paymentPreference == PaymentPreference.INSURANCE_FIRST &&
                    r.order!!.invalidAt?.isAfter(now) == true && (preparationUntil == null || now.isBefore(preparationUntil))) {
                    runCatching { scheduler.schedule(r.task, now.plusSeconds(2)) }.onFailure { notifications.attention() }
                }
                continue
            }
            val attempt = r.attempt
            if (attempt != null) {
                if (now.isBefore(attempt.sentAt.plusSeconds(120)) && r.phase != TaskPhase.NEEDS_ATTENTION) {
                    runCatching { scheduler.schedule(r.task, now.plusSeconds(2)) }.onFailure { notifications.attention() }
                } else if (r.phase != TaskPhase.NEEDS_ATTENTION) {
                    notifications.result(store.update(r.task.id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "存在未决提交，请到官方页面核对，不会再次提交") })
                }
            } else if (r.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING)) {
                when {
                    now.toEpochMilli() + 60000 < maxOf(lastWall, r.lastEventAt?.toEpochMilli() ?: 0) -> notifications.result(store.update(r.task.id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "系统时间发生变化，请检查放号时间后重新设置") })
                    !now.isBefore(r.task.deadline) -> notifications.result(store.update(r.task.id) { it.copy(phase = TaskPhase.EXPIRED, note = "已错过运行窗口，不会重新计时") })
                    else -> runCatching { scheduler.schedule(r.task, maxOf(now.plusSeconds(2), r.task.releaseAt)) }.onFailure { notifications.attention() }
                }
            }
        }
    }
}
