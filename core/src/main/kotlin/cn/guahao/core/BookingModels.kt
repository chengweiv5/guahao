@file:UseSerializers(InstantSerializer::class, DateSerializer::class)
package cn.guahao.core

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.time.Instant
import java.time.LocalDate

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = Instant.parse(decoder.decodeString())
}
object DateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = LocalDate.parse(decoder.decodeString())
}
@Serializable enum class PaymentPreference { INSURANCE_FIRST, FULL_AMOUNT_BY_USER }
@Serializable enum class TaskPhase { DRAFT, WAITING, SEARCHING, SUBMITTING, RECONCILING, AWAITING_PAYMENT, BOOKED, EXPIRED, STOPPED, NEEDS_ATTENTION }
@Serializable data class PatientRef(val sessionId: String, val patientId: String)
/** Keys are local HMAC indexes, never raw hospital identity or random credential IDs. */
@Serializable data class SubmissionScope(val providerId: String, val accountKey: String?) {
    fun conflicts(other: SubmissionScope) = providerId == other.providerId &&
        (accountKey == null || other.accountKey == null || accountKey == other.accountKey)
}
@Serializable data class ConnectionBinding(
    val hospitalId: String, val hospitalName: String, val providerId: String, val providerName: String,
    val accountKey: String, val patientKey: String, val connectionId: String, val credentialVersionId: String,
    val submissionScope: SubmissionScope, val campusId: String? = null, val campusName: String? = null
) {
    fun samePrincipal(other: ConnectionBinding) = hospitalId == other.hospitalId && campusId == other.campusId && providerId == other.providerId &&
        accountKey == other.accountKey && patientKey == other.patientKey
}
val PatientRef.isDemo: Boolean get() = sessionId == "demo" || sessionId.startsWith("demo-")
@Serializable data class DepartmentRef(val parentCode: String, val code: String, val parentName: String, val name: String, val originCode: String)
@Serializable data class VisitCondition(
    val patient: PatientRef, val department: DepartmentRef, val doctorCode: String, val doctorName: String,
    val visitDate: LocalDate, val startMinute: Int, val endMinute: Int, val purpose: String, val maxFeeFen: Long
) {
    init {
        require(doctorCode.isNotBlank() && doctorName.isNotBlank())
        require(startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute)
        require(maxFeeFen >= 0 && purpose in setOf("1", "2"))
    }
}
@Serializable data class BookingTask(
    val id: String, val condition: VisitCondition, val releaseAt: Instant, val maxRuntimeMinutes: Int = 30,
    val paymentPreference: PaymentPreference = PaymentPreference.INSURANCE_FIRST,
    val generation: Long = 1, val demo: Boolean = true, val binding: ConnectionBinding? = null,
    val initialSchedule: ScheduleObservation? = null
) {
    init { require(maxRuntimeMinutes > 0); require(id.isNotBlank()) }
    val deadline: Instant get() = releaseAt.plusSeconds(maxRuntimeMinutes.toLong() * 60)
}
@Serializable data class Candidate(
    val department: DepartmentRef, val doctorCode: String, val doctorName: String, val date: LocalDate,
    val half: String, val hour: String, val startMinute: Int, val endMinute: Int,
    val feeFen: Long, val remaining: Int, val titleType: String, val standby: Boolean
)
@Serializable enum class OrderPhase { LOCKED, INSURANCE_PENDING, BOOKED, INSURANCE_PAID, OTHER }
@Serializable data class OrderSnapshot(
    val orderNo: String, val patient: PatientRef, val doctorCode: String?, val departmentCode: String?,
    val doctorName: String, val departmentName: String, val source: String, val visitDate: LocalDate,
    val half: String, val hour: String, val feeFen: Long, val phase: OrderPhase, val rawStatus: String,
    val invalidAt: Instant?, val specialPaymentCondition: Boolean, val insuranceVerified: Boolean = false,
    val insuranceSupported: Boolean? = null
)
@Serializable data class PaymentContext(val zeroFee: Boolean, val insuranceSupported: Boolean, val requiresUserChoice: Boolean)
@Serializable data class SubmissionAttempt(
    val id: String, val taskId: String, val candidate: Candidate, val sentAt: Instant,
    val baselineOrderNos: Set<String>, val orderNo: String? = null, val paymentContext: PaymentContext? = null,
    val submissionScope: SubmissionScope? = null
)
@Serializable data class TaskRecord(
    val task: BookingTask, val phase: TaskPhase = TaskPhase.DRAFT, val attempt: SubmissionAttempt? = null,
    val order: OrderSnapshot? = null, val stopRequested: Boolean = false, val note: String = "",
    val insuranceStartedAt: Instant? = null, val insuranceResult: String? = null,
    val lastEventAt: Instant? = null, val manuallyResolved: Boolean = false,
    val reconciliationPatient: PatientRef? = null, val latestSchedule: ScheduleObservation? = null
)
val TaskRecord.hasUnresolvedSubmission get() = attempt != null && order == null && !manuallyResolved
val BookingTask.hospitalName get() = binding?.hospitalName ?: if (demo) "演示医院 A" else "北京佑安医院"
sealed interface LockReply {
    data object Accepted : LockReply
    data object NoStock : LockReply
    data class Rejected(val code: String) : LockReply
    data object OutcomeUnknown : LockReply
}
sealed interface AsyncReply {
    data object Pending : AsyncReply
    data class OrderFound(val orderNo: String, val paymentContext: PaymentContext? = null) : AsyncReply
    data object NoStock : AsyncReply
    data class Unknown(val code: String) : AsyncReply
}
enum class InsuranceReply { ACCEPTED, PENDING, PAID, UNKNOWN, REJECTED }
class HospitalException(val safeMessage: String, val retryAfterMillis: Long? = null, val retryable: Boolean = false,
    val reconnectRequired: Boolean = false) : Exception(safeMessage)
