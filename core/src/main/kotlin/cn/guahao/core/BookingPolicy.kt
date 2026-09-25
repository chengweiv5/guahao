package cn.guahao.core

import java.math.BigDecimal
import java.time.Instant

fun yuanToFen(value: String): Long {
    require(Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(value)) { "金额需为非负数，最多两位小数" }
    return try { BigDecimal(value).movePointRight(2).longValueExact() }
    catch (_: ArithmeticException) { throw IllegalArgumentException("金额超出范围") }
}
fun eligible(task: BookingTask, candidate: Candidate, now: Instant): Boolean {
    val c = task.condition
    return !now.isBefore(task.releaseAt) && now.isBefore(task.deadline) &&
        candidate.department == c.department && candidate.doctorCode == c.doctorCode &&
        candidate.doctorName == c.doctorName && candidate.date == c.visitDate && candidate.remaining > 0 &&
        !candidate.standby && candidate.feeFen in 0..c.maxFeeFen &&
        candidate.startMinute >= c.startMinute && candidate.endMinute <= c.endMinute
}
fun shouldStart(task: BookingTask, generation: Long, now: Instant) = generation == task.generation && now.isBefore(task.deadline)
fun matches(order: OrderSnapshot, task: BookingTask, candidate: Candidate): Boolean =
    order.patient == task.condition.patient && order.source == "2" &&
    (order.doctorCode == null || order.doctorCode == candidate.doctorCode) &&
    (order.departmentCode == null || order.departmentCode == candidate.department.code) &&
    order.doctorName == candidate.doctorName && order.departmentName == candidate.department.name &&
    order.visitDate == candidate.date && order.half == candidate.half && order.hour == candidate.hour &&
    order.feeFen == candidate.feeFen && order.phase != OrderPhase.OTHER

fun satisfiesCondition(order: OrderSnapshot, task: BookingTask): Boolean {
    val c = task.condition
    val range = runCatching { cn.guahao.core.psc.timeRange(order.hour) }.getOrNull() ?: return false
    return order.patient == c.patient && order.source == "2" && order.doctorName == c.doctorName &&
        order.departmentName == c.department.name && (order.doctorCode == null || order.doctorCode == c.doctorCode) &&
        (order.departmentCode == null || order.departmentCode == c.department.code) && order.visitDate == c.visitDate &&
        range.first >= c.startMinute && range.second <= c.endMinute && order.feeFen in 0..c.maxFeeFen && order.phase != OrderPhase.OTHER
}

data class RuntimeReadiness(val sessionValid: Boolean, val notificationsAllowed: Boolean,
    val exactAlarmAllowed: Boolean, val batteryRestrictionAcknowledged: Boolean, val unlocked: Boolean) {
    val ready get() = sessionValid && notificationsAllowed && exactAlarmAllowed && batteryRestrictionAcknowledged && unlocked
}
