package cn.guahao.hospital.beijing

import cn.guahao.core.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

class JingtongGateway(private val sessions: BeijingConnectionRepository, private val queries: BeijingQueryClient,
    private val source: BeijingBookingSource, private val maySubmit: (BookingTask) -> Boolean) : BookingGateway {
    private fun selection(ref: PatientRef) = sessions.load(ref).also {
        if (it.channel != RegistrationChannel.JINGTONG) throw HospitalException("此任务不是京通连接")
    }
    override fun binding(patient: PatientRef) = sessions.binding(patient)
    override suspend fun departments(patient: PatientRef): List<DepartmentRef> = safely {
        val s = selection(patient); queries.departments(s.channel, s.hospitalId)
    }
    override suspend fun candidates(condition: VisitCondition) = schedule(condition.scheduleQuery()).days.flatMap { it.candidates }
    override suspend fun schedule(query: ScheduleQuery): DepartmentSchedule = safely {
        val s = selection(query.patient)
        val calendar = queries.calendar(s.channel, s.hospitalId, query.department)
        val day = calendar.singleOrNull { it.date == query.visitDate }
            ?: return@safely DepartmentSchedule(query.department, emptyList())
        val doctors = queries.doctors(s.channel, s.hospitalId, query.department, query.visitDate)
        DepartmentSchedule(query.department, listOf(doctors.asDay(HospitalRoute(s.hospitalId, s.channel, s.campusId), query.department, day.availability)))
    }
    override suspend fun validateBookingAccess(patient: PatientRef): Boolean = safely { selection(patient); sessions.validate(patient); true }
    override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot> = safely {
        source.orders(selection(patient), from, to)
    }
    override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply = safely {
        if (!JingtongCapabilities.automaticBookingVerified) throw HospitalException(JingtongCapabilities.unavailableReason)
        if (!maySubmit(task)) throw HospitalException("任务已停止，未发送预约")
        source.submit(selection(task.condition.patient), task, candidate) { maySubmit(task) }
    }
    // 京通 synchronously returns an order ID. Missing receipt is never inferred from an empty list.
    override suspend fun querySubmission(patient: PatientRef): AsyncReply = AsyncReply.Unknown("JINGTONG_RECEIPT_UNKNOWN")
    override suspend fun querySubmission(task: BookingTask, attempt: SubmissionAttempt, patient: PatientRef): AsyncReply = safely {
        require(attempt.taskId == task.id)
        if (!binding(task.condition.patient).samePrincipal(binding(patient))) throw HospitalException("核对连接身份不一致")
        source.receipt(selection(task.condition.patient), task, attempt.candidate)
    }
    override suspend fun insuranceAvailable(patient: PatientRef) = false
    override suspend fun initializeInsurance(patient: PatientRef, orderNo: String) = InsuranceReply.UNKNOWN
    override suspend fun paymentState(patient: PatientRef, orderNo: String) = InsuranceReply.UNKNOWN
    private suspend fun <T> safely(block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: BeijingQueryException) { throw HospitalException(e.message.orEmpty(),
        retryable = e.kind in setOf(BeijingFailureKind.NETWORK, BeijingFailureKind.RATE_LIMITED),
        reconnectRequired = e.kind in setOf(BeijingFailureKind.RECONNECT, BeijingFailureKind.CLIENT_VERIFICATION)) }
}
