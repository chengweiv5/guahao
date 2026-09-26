package cn.guahao.hospital.beijing

import cn.guahao.core.*

/** Only complete, precisely timed, ordinary stock becomes a candidate. */
internal fun BeijingDoctorSchedule.asDay(route: HospitalRoute, department: DepartmentRef,
    availability: DateAvailability): ScheduleDay {
    val candidates = doctors.flatMap { doctor -> doctor.slots.flatMap { slot ->
        if (slot.stock != BeijingStock.AVAILABLE || slot.feeFen == null || (slot.remaining ?: 0) <= 0) emptyList()
        else slot.periods.mapNotNull { period ->
            val start = period.startMinute ?: return@mapNotNull null
            val end = period.endMinute ?: return@mapNotNull null
            val remaining = period.remaining?.takeIf { it > 0 } ?: return@mapNotNull null
            if (period.generated || start !in 0..1439 || end !in 1..1440 || start >= end ||
                slot.productKey.isBlank() || period.productTimeKey.isBlank()) return@mapNotNull null
            Candidate(department, doctor.doctor.code, doctor.doctor.name, date, slot.halfCode,
                "%02d:%02d-%02d:%02d".format(start / 60, start % 60, end / 60, end % 60), start, end,
                slot.feeFen, minOf(remaining, slot.remaining!!), "", false,
                PlatformProduct(route.channel.id, route.hospitalId, slot.productKey, period.productTimeKey))
        }
    } }
    return ScheduleDay(date, availability, doctors.map { it.doctor }, candidates)
}
