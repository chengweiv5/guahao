package cn.guahao.core

import java.time.Instant
import java.time.LocalDate

interface BookingGateway {
    suspend fun departments(patient: PatientRef): List<DepartmentRef>
    suspend fun candidates(condition: VisitCondition): List<Candidate>
    suspend fun validateBookingAccess(patient: PatientRef): Boolean
    suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot>
    suspend fun lock(task: BookingTask, candidate: Candidate): LockReply
    suspend fun querySubmission(patient: PatientRef): AsyncReply
    suspend fun insuranceAvailable(patient: PatientRef): Boolean
    suspend fun initializeInsurance(patient: PatientRef, orderNo: String): InsuranceReply
    suspend fun paymentState(patient: PatientRef, orderNo: String): InsuranceReply
}
/** Implementations must commit update/claim atomically and durably before returning. */
interface TaskStore {
    fun get(id: String): TaskRecord
    fun all(): List<TaskRecord>
    fun save(record: TaskRecord)
    fun update(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord
    fun claim(id: String, generation: Long, owner: String): Boolean
    fun release(id: String, owner: String)
}
interface BookingClock {
    fun now(): Instant
    suspend fun delayMillis(value: Long)
}
