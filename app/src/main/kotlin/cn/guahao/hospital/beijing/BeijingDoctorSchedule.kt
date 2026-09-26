package cn.guahao.hospital.beijing

import cn.guahao.core.DepartmentRef
import cn.guahao.core.DoctorRef
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Keeps platform product keys separate from the existing hospital's booking candidate. */
data class BeijingDoctorSchedule(val date: LocalDate, val doctors: List<BeijingDoctor>)
data class BeijingDoctor(val doctor: DoctorRef, val slots: List<BeijingSlot>)
enum class BeijingStock { AVAILABLE, FULL, NOT_RELEASED, WAITLIST, UNKNOWN }
data class BeijingSlot(
    val productKey: String, val halfCode: String, val halfLabel: String,
    val feeFen: Long?, val remaining: Int?, val stock: BeijingStock, val officialLabel: String,
    val periods: List<BeijingPeriod>
)
data class BeijingPeriod(
    val productTimeKey: String, val label: String, val startMinute: Int?, val endMinute: Int?,
    val remaining: Int?, val generated: Boolean
)

internal object BeijingDoctorParser {
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMddHHmm").withResolverStyle(ResolverStyle.STRICT)

    fun parse(data: JsonElement, department: DepartmentRef, date: LocalDate): BeijingDoctorSchedule {
        val rows = data.obj()["doctors"]?.arr() ?: invalid()
        val doctors = rows.map { raw ->
            val doctor = raw.obj()
            if (doctor.text("firstDeptCode") != department.parentCode || doctor.text("secondDeptCode") != department.code) invalid()
            val identity = DoctorRef(doctor.text("doctorCode"), doctor.text("doctorName"), doctor.optionalText("titleView"))
            val slots = (doctor["detail"] ?: invalid()).arr().map { rawSlot ->
                val slot = rawSlot.obj()
                val label = slot.text("productStatusView")
                // Numeric codes are interpreted only with the matching observed official label.
                val stock = when ((slot["productStatus"] as? JsonPrimitive)?.intOrNull to label) {
                    1 to "有号" -> BeijingStock.AVAILABLE
                    2 to "约满", 4 to "无号" -> BeijingStock.FULL
                    6 to "即将放号" -> BeijingStock.NOT_RELEASED
                    8 to "候补" -> BeijingStock.WAITLIST
                    else -> BeijingStock.UNKNOWN
                }
                val periods = (slot["period"] ?: invalid()).arr().map { rawPeriod ->
                    val period = rawPeriod.obj()
                    val generated = (period["create"] as? JsonPrimitive)?.booleanOrNull ?: invalid()
                    val timeLabel = period.optionalText("dutyTimeView").orEmpty()
                    val time = parseTime(period.optionalText("dutyTime"), date)
                    BeijingPeriod(period.text("uniqProductKey"), timeLabel, time?.first, time?.second,
                        count(period["ncode"]), generated)
                }
                if (periods.map { it.productTimeKey }.distinct().size != periods.size) invalid()
                BeijingSlot(slot.text("uniqueProductKey"), slot.scalar("dutyCode"), slot.text("dutyCodeView"),
                    fee(slot["fcode"]), count(slot["ncode"]), stock, label, periods)
            }
            if (slots.map { it.productKey }.distinct().size != slots.size) invalid()
            BeijingDoctor(identity, slots)
        }
        if (doctors.map { it.doctor.code }.distinct().size != doctors.size) invalid()
        return BeijingDoctorSchedule(date, doctors)
    }

    private fun parseTime(raw: String?, date: LocalDate): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        // Missing/coarse time is unknown. A precise time for another day is a response mismatch.
        if (!Regex("\\d{12}-\\d{12}").matches(raw)) return null
        val (start, end) = raw.split('-').map {
            runCatching { LocalDateTime.parse(it, timestamp) }.getOrElse { invalid() }
        }
        if (start.toLocalDate() != date || end.toLocalDate() != date || !end.isAfter(start)) invalid()
        return start.hour * 60 + start.minute to end.hour * 60 + end.minute
    }
    private fun count(value: JsonElement?): Int? {
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: return null
        if (raw.isBlank()) return null
        return raw.toIntOrNull()?.takeIf { it >= 0 }
    }
    private fun fee(value: JsonElement?): Long? {
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: return null
        if (raw.isBlank()) return null
        return runCatching { BigDecimal(raw).movePointRight(2).longValueExact().takeIf { it >= 0 } }.getOrNull()
    }
    private fun JsonElement.obj() = this as? JsonObject ?: invalid()
    private fun JsonElement.arr() = this as? JsonArray ?: invalid()
    private fun JsonObject.optionalText(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.text(key: String) = optionalText(key) ?: invalid()
    private fun JsonObject.scalar(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: invalid()
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
