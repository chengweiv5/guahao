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
    override fun onCreate() {
        super.onCreate()
        // Browser-only processes must not open the task database or recover worker ownership.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName() != packageName) return
        graph = AppGraph(this)
    }
}
val Context.graph: AppGraph get() = (applicationContext as GuahaoApplication).graph

class AppGraph(val context: Context, val mode: AppMode = AppMode()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val vault = EncryptedVault(context)
    val store = BookingDatabase(context, vault).apply { recoverProcessOwnership() }
    val sessions = SessionRepository(vault, Mutex())
    init { store.all().asReversed().forEach { sessions.register(it.task.condition.patient) } }
    val hospital = PscClient(sessions) { !store.get(it.id).stopRequested }
    val beijingQueries = cn.guahao.hospital.beijing.BeijingQueryClient(cn.guahao.hospital.beijing.BeijingBrowserClient(context))
    private val demo by lazy { DemoGateway(store) }
    private fun gatewayBinding(p: PatientRef) = if (p.isDemo) demoBinding(p) else sessions.identities.resolve(p)
        ?: throw HospitalException("连接身份待核对，请重新连接医院")
    val gateway: BookingGateway = object : BookingGateway {
        fun forPatient(p: PatientRef): BookingGateway {
            mode.requireAllowed(p)
            return when (gatewayBinding(p).providerId) {
                "demo-a", "demo-b" -> demo
                "psc-youan" -> hospital
                else -> throw HospitalException("此医院接入来源尚未支持")
            }
        }
        override fun binding(patient: PatientRef): ConnectionBinding { mode.requireAllowed(patient); return gatewayBinding(patient) }
        override suspend fun departments(patient: PatientRef) = forPatient(patient).departments(patient)
        override suspend fun candidates(condition: VisitCondition) = forPatient(condition.patient).candidates(condition)
        override suspend fun schedule(query: ScheduleQuery) = forPatient(query.patient).schedule(query)
        override suspend fun validateBookingAccess(patient: PatientRef) = forPatient(patient).validateBookingAccess(patient)
        override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate) = forPatient(patient).orders(patient, from, to)
        override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply {
            mode.requireAllowed(task)
            return forPatient(task.condition.patient).lock(task, candidate)
        }
        override suspend fun querySubmission(patient: PatientRef) = forPatient(patient).querySubmission(patient)
        override suspend fun insuranceAvailable(patient: PatientRef) = forPatient(patient).insuranceAvailable(patient)
        override suspend fun initializeInsurance(patient: PatientRef, orderNo: String) = forPatient(patient).initializeInsurance(patient, orderNo)
        override suspend fun paymentState(patient: PatientRef, orderNo: String) = forPatient(patient).paymentState(patient, orderNo)
    }
    val clock = AndroidBookingClock()
    val engine = BookingEngine(gateway, store, clock)
    private val taskGates = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun taskGate(id: String) = taskGates.getOrPut(id) { Mutex() }
    val payment = PaymentCoordinator(gateway, store, clock)
    val scheduler = AlarmScheduler(context, mode)
    val notifications = BookingNotifications(context, mode)
    fun visibleRecords() = mode.visible(store.all())
    fun saveDraft(task: BookingTask) { mode.requireAllowed(task); store.save(TaskRecord(task.copy(binding = gateway.binding(task.condition.patient)))) }
    fun hasUnresolvedOrActive(excluding: String? = null) = visibleRecords().any { it.task.id != excluding &&
        (it.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING) ||
            (it.attempt != null && it.order == null && !it.manuallyResolved)) }
    // Import creates a new isolated session; existing tasks keep their original PatientRef.
    // An unresolved submission restricts new submissions, not adding a connection.
    suspend fun importSession(raw: String): PscSession = sessions.importAndVerify(raw)
    suspend fun checkConnection(force: Boolean = false) {
        val currentPatient = sessions.current()?.reference ?: return
        // Suppress optional checks only for the session currently used by a protected task.
        if (!force && visibleRecords().any { !it.task.demo && it.task.condition.patient == currentPatient &&
                (it.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING) ||
                    (it.attempt != null && it.order == null && !it.manuallyResolved)) }) return
        sessions.checkCurrent(force)
    }
    suspend fun enable(id: String) {
        val r = store.get(id)
        mode.requireAllowed(r.task)
        check(r.phase == TaskPhase.DRAFT && r.attempt == null)
        check(r.task.releaseAt.isAfter(clock.now())) { "放号时间已过，请调整后重新启用" }
        val sessionValid = r.task.demo || runCatching { sessions.load(r.task.condition.patient) }.isSuccess
        check(readiness(context, sessionValid).ready) { "请先补齐医院连接、通知、精确定时和后台运行准备" }
        var observation = r.task.initialSchedule
        if (!r.task.demo) {
            check(gateway.validateBookingAccess(r.task.condition.patient)) { "医院会话不可用，请重新连接" }
            check(gateway.departments(r.task.condition.patient).contains(r.task.condition.department)) { "医院科室信息已变化，请重新选择" }
            val schedule = gateway.schedule(r.task.condition.scheduleQuery())
            check(schedule.department == r.task.condition.department) { "医院返回科室不一致，请重新查询" }
            val doctor = DoctorRef(r.task.condition.doctorCode, r.task.condition.doctorName)
            check(schedule.doctors.any { it.sameIdentity(doctor) }) { "未能核实医生与科室的关联，请刷新后选择" }
            observation = schedule.day(r.task.condition.visitDate).observation(doctor, clock.now())
        }
        store.update(id) { it.copy(phase = TaskPhase.WAITING, stopRequested = false, note = "等待计划查号时间",
            latestSchedule = observation, lastEventAt = clock.now()) }
        try { scheduler.schedule(r.task) }
        catch (e: Exception) { store.update(id) { it.copy(phase = TaskPhase.DRAFT, note = "定时失败，请检查权限后重新启用") }; throw e }
    }
    suspend fun runTask(id: String, generation: Long, owner: String) = taskGate(id).withLock {
        engine.run(id, generation, owner)
    }
    fun stop(id: String) { engine.stop(id); scheduler.cancel(store.get(id).task) }
    suspend fun preparePayment(id: String) = taskGate(id).withLock {
        mode.requireAllowed(store.get(id).task)
        try { payment.prepare(id) }
        catch (_: HospitalException) { store.update(id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION, note = "已锁号，付款信息未取得，请刷新或到服务号核对") } }
    }
    suspend fun refreshPayment(id: String) = taskGate(id).withLock {
        mode.requireAllowed(store.get(id).task)
        payment.refresh(id)
    }
    suspend fun manualReconcile(id: String) = taskGate(id).withLock {
        val r = store.get(id)
        mode.requireAllowed(r.task)
        val attempt = r.attempt ?: return@withLock
        if (r.order != null || r.manuallyResolved) return@withLock
        val patient = r.reconciliationPatient ?: r.task.condition.patient
        val number = attempt.orderNo ?: (gateway.querySubmission(patient) as? AsyncReply.OrderFound)?.orderNo
        val matches = gateway.orders(patient, r.task.condition.visitDate, r.task.condition.visitDate)
            .filter { it.patient == patient }.map { it.copy(patient = r.task.condition.patient) }
            .filter { it.orderNo !in attempt.baselineOrderNos && matches(it, r.task, attempt.candidate) }
        val found = matches.singleOrNull()?.takeIf { it.orderNo == number }
            ?: throw HospitalException("未找到可唯一关联的订单，请在微信服务号人工核对。列表为空不代表提交失败。")
        store.update(id) { it.copy(order = found.copy(insuranceSupported = attempt.paymentContext?.insuranceSupported,
            specialPaymentCondition = found.specialPaymentCondition || attempt.paymentContext?.requiresUserChoice == true),
            phase = TaskPhase.AWAITING_PAYMENT, note = "已找到同一订单，请刷新付款结果", lastEventAt = clock.now()) }
        payment.refresh(id)
    }
    suspend fun useConnection(id: String, ref: PatientRef) = taskGate(id).withLock {
        val r = store.get(id)
        val old = r.task.binding ?: gateway.binding(r.task.condition.patient)
        val fresh = gateway.binding(ref)
        check(old.samePrincipal(fresh)) { "医院、来源、账户或就诊人不一致，不能接管此任务" }
        check(gateway.validateBookingAccess(ref)) { "新连接未通过校验" }
        if (r.attempt != null || r.order != null) {
            // Read-only recovery override; the original attempt, binding and scope remain frozen.
            store.update(id) { it.copy(reconciliationPatient = ref, note = "已选择同身份新连接，仅用于核对原提交与订单") }
            if (r.order != null) payment.refresh(id)
        } else {
            check(r.phase !in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING)) {
                "请先停止此任务，再更新此任务连接"
            }
            scheduler.cancel(r.task)
            store.update(id) { it.copy(task = it.task.copy(condition = it.task.condition.copy(patient = ref),
                binding = fresh, generation = it.task.generation + 1), phase = TaskPhase.DRAFT,
                stopRequested = false, note = "已更新连接，请核对原放号时间后手动启用") }
        }
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
            if (!mode.allows(r.task)) {
                scheduler.cancel(r.task)
                notifications.cancelResult(r.task.id)
                continue
            }
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
