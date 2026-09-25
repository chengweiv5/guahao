package cn.guahao.hospital

import cn.guahao.core.*
import java.time.*

class DemoGateway(private val store: TaskStore) : BookingGateway {
    companion object {
        val patient = PatientRef("demo", "synthetic-patient")
        val patientB = PatientRef("demo-b", "synthetic-patient")
        val department = DepartmentRef("demo-parent", "demo-eye", "五官科（演示）", "眼科", "")
    }
    override fun binding(patient: PatientRef) = demoBinding(patient)
    override suspend fun departments(patient: PatientRef) = listOf(department)
    override suspend fun candidates(condition: VisitCondition) = listOf(Candidate(department, "demo-doctor", "林医生（虚构）",
        condition.visitDate, "1", "09:00-09:30", 540, 570, 5000, 1, "2", false))
    override suspend fun validateBookingAccess(patient: PatientRef) = true
    override suspend fun insuranceAvailable(patient: PatientRef) = true
    override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply { check(task.demo)
        store.update(task.id) { it.copy(attempt = it.attempt!!.copy(orderNo = "demo-${task.id}")) }
        return LockReply.Accepted }
    override suspend fun querySubmission(patient: PatientRef): AsyncReply {
        val r = store.all().firstOrNull { it.task.demo && it.task.condition.patient == patient && it.attempt?.orderNo != null && it.order == null } ?: return AsyncReply.Pending
        return AsyncReply.OrderFound("demo-${r.task.id}", PaymentContext(false,true,false))
    }
    override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot> = store.all().filter {
        it.task.demo && it.task.condition.patient == patient && it.attempt?.orderNo != null && it.task.condition.visitDate in from..to
    }.map { r ->
        val a = r.attempt!!; val c = a.candidate
        val existing = r.order
        existing?.copy(phase = if (r.insuranceStartedAt != null && existing.phase == OrderPhase.LOCKED) OrderPhase.INSURANCE_PENDING else existing.phase,
            rawStatus = if (r.insuranceStartedAt != null && existing.phase == OrderPhase.LOCKED) "7" else existing.rawStatus)
            ?: OrderSnapshot("demo-${r.task.id}", patient, c.doctorCode, c.department.code, c.doctorName, c.department.name,
                "2", c.date, c.half, c.hour, c.feeFen, OrderPhase.LOCKED, "1", a.sentAt.plusSeconds(1800), false)
    }
    override suspend fun initializeInsurance(patient: PatientRef, orderNo: String) = InsuranceReply.ACCEPTED
    override suspend fun paymentState(patient: PatientRef, orderNo: String) = if (store.all().any {
        it.task.condition.patient == patient && it.order?.orderNo == orderNo && it.order?.phase == OrderPhase.INSURANCE_PAID
    }) InsuranceReply.PAID else InsuranceReply.PENDING
}
