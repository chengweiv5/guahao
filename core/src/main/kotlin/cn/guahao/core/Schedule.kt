@file:kotlinx.serialization.UseSerializers(InstantSerializer::class, DateSerializer::class)
package cn.guahao.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

@Serializable enum class DateAvailability { AVAILABLE, NOT_RELEASED, NO_STOCK, UNKNOWN }
@Serializable data class DoctorRef(val code: String, val name: String, val title: String? = null) {
    fun sameIdentity(other: DoctorRef) = code == other.code && name == other.name
}
data class ScheduleQuery(val patient: PatientRef, val department: DepartmentRef, val visitDate: LocalDate, val purpose: String)
data class ScheduleDay(val date: LocalDate, val status: DateAvailability, val doctors: List<DoctorRef>, val candidates: List<Candidate>)
data class DepartmentSchedule(val department: DepartmentRef, val days: List<ScheduleDay>) {
    val doctors: List<DoctorRef> get() = days.flatMap { it.doctors }.distinctBy { it.code to it.name }
    fun day(date: LocalDate): ScheduleDay {
        val found = days.singleOrNull { it.date == date } ?: return ScheduleDay(date, DateAvailability.UNKNOWN, emptyList(), emptyList())
        return found.copy(candidates = found.candidates.filter { it.date == date && it.department == department })
    }
}
@Serializable data class ScheduleObservation(val availability: DateAvailability, val doctorConfirmed: Boolean, val checkedAt: Instant)
fun VisitCondition.scheduleQuery() = ScheduleQuery(patient, department, visitDate, purpose)
fun ScheduleDay.observation(doctor: DoctorRef, now: Instant) = ScheduleObservation(status, doctors.any { it.sameIdentity(doctor) }, now)
val DateAvailability.label: String get() = when (this) {
    DateAvailability.AVAILABLE -> "当日有号"
    DateAvailability.NOT_RELEASED -> "尚未放号"
    DateAvailability.NO_STOCK -> "当日无号"
    DateAvailability.UNKNOWN -> "暂未查到当天排班"
}
