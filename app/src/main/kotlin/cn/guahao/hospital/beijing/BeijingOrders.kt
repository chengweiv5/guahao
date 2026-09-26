package cn.guahao.hospital.beijing

import cn.guahao.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter

internal class BeijingOrderClient(private val transport: BeijingQueryTransport, private val intervalMillis: Long = 1000) {
    suspend fun orders(selection: BeijingPatientSelection, from: LocalDate, to: LocalDate): List<OrderSnapshot> {
        require(!to.isBefore(from) && !to.isAfter(from.plusDays(366)))
        val output = mutableListOf<OrderSnapshot>()
        val ids = mutableSetOf<String>()
        var expectedCount: Int? = null
        for (page in 1..50) {
            val data = BeijingReplyParser.data(transport.execute(BeijingQueryRequest(selection.channel,
                "auth/order/getOrderListV2", buildJsonObject {
                    put("pageNo", page); put("pageSize", 20); put("idCardNo", selection.patient.identityCard)
                    put("idCardType", selection.patient.identityCardType); put("hosCode", selection.hospitalId)
                    put("orderStatus", "ALL"); put("sortTimeType", 1) // Official UI: 1 = visit date, 0 = order creation date.
                    put("startTime", from.toString()); put("endTime", to.toString())
                }))) as? JsonObject ?: invalid()
            val count = (data["count"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..1000 } ?: invalid()
            if (expectedCount != null && expectedCount != count) invalid()
            expectedCount = count
            val rows = data["list"] as? JsonArray ?: invalid()
            if (rows.size > 20 || (rows.isEmpty() && ids.size != count)) invalid()
            for (raw in rows) {
                val item = raw as? JsonObject ?: invalid()
                // Legacy orders use a different endpoint. Never silently omit them from the duplicate baseline.
                if ((item["isNew"] as? JsonPrimitive)?.intOrNull != 1) invalid()
                val id = item.requiredText("orderId")
                if (!ids.add(id) || item.requiredText("patientId") != selection.patient.id ||
                    item.requiredText("hosCode") != selection.hospitalId) invalid()
                delay(intervalMillis)
                val detail = BeijingReplyParser.data(transport.execute(BeijingQueryRequest(selection.channel,
                    "auth/order/detail", buildJsonObject { put("orderId", id); put("patientId", selection.patient.id) })))
                output += BeijingOrderParser.parse(detail, selection, id, from, to)
            }
            if (ids.size == count) return output
            if (ids.size > count || rows.size < 20) invalid()
            delay(intervalMillis)
        }
        invalid()
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}

/** Field names and state mapping follow official OrderDetail/CardList; live nonempty proof is tracked separately. */
internal object BeijingOrderParser {
    fun parse(data: JsonElement, selection: BeijingPatientSelection, orderId: String,
        from: LocalDate, to: LocalDate): OrderSnapshot {
        val root = data as? JsonObject ?: invalid()
        val base = root["orderBaseInfo"] as? JsonObject ?: invalid()
        if (base.requiredText("orderId") != orderId || base.requiredText("patientId") != selection.patient.id ||
            base.requiredText("hosCode") != selection.hospitalId) invalid()
        val date = runCatching { LocalDate.parse(base.requiredText("treatmentDay"), DateTimeFormatter.BASIC_ISO_DATE) }.getOrElse { invalid() }
        if (date < from || date > to) invalid()
        // Official UI formats the raw value as date + half-day + precise time range.
        val display = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日 (上午|下午|晚上) (\\d{2}:\\d{2}[-~]\\d{2}:\\d{2})")
            .matchEntire(base.requiredText("productTime").trim()) ?: invalid()
        val displayDate = runCatching { LocalDate.of(display.groupValues[1].toInt(), display.groupValues[2].toInt(), display.groupValues[3].toInt()) }.getOrElse { invalid() }
        if (displayDate != date) invalid()
        val time = Regex("(\\d{2}):(\\d{2})[-~](\\d{2}):(\\d{2})").matchEntire(display.groupValues[5]) ?: invalid()
        val (sh, sm, eh, em) = time.destructured.toList().map { it.toInt() }
        if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59 || eh * 60 + em <= sh * 60 + sm) invalid()
        val fee = runCatching { BigDecimal(base.requiredScalar("price")).movePointRight(2).longValueExact() }.getOrElse { invalid() }
        if (fee < 0) invalid()
        val status = (base["orderStatus"] as? JsonPrimitive)?.intOrNull ?: invalid()
        if (!root.containsKey("payInfo")) invalid()
        val pay = when (val raw = root["payInfo"]) { JsonNull -> null; is JsonObject -> raw; else -> invalid() }
        val payStatus = (pay?.get("payStatus") as? JsonPrimitive)?.intOrNull
        val payLabel = pay?.optionalText("payStatusView")
        val mustPay = pay?.optionalText("mustPay")
        val phase = when {
            status == 99 -> OrderPhase.LOCKED
            status == 1 && payStatus == 6 -> OrderPhase.BOOKED
            status == 1 && mustPay == "BIND" -> OrderPhase.LOCKED
            status == 1 && pay == null -> OrderPhase.RESERVED_ONSITE
            status in setOf(3, 5) -> OrderPhase.BOOKED
            status in setOf(2, 6, 7) -> OrderPhase.OTHER
            // Unknown state must block the duplicate baseline, not silently permit another order.
            else -> invalid()
        }
        // No guessed timeout. Preserve unknown or unfamiliar server time formats as unknown.
        val deadline = pay?.optionalText("payCodeExpireTime")?.let {
            runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant() }.getOrNull()
        }
        val title = base.requiredText("orderStatusView")
        return OrderSnapshot(orderId, selection.reference, base.requiredText("doctorCode"), base.requiredText("secondDeptCode"),
            base.requiredText("doctorName"), base.requiredText("secondDeptName"), selection.channel.id, date, "",
            "%02d:%02d-%02d:%02d".format(sh, sm, eh, em), fee, phase, status.toString(), deadline,
            phase == OrderPhase.OTHER, platform = PlatformOrder(selection.channel.id, selection.hospitalId,
                sh * 60 + sm, eh * 60 + em, title, payLabel, base.optionalText("takeTimeTips")))
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
internal fun JsonObject.optionalText(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
internal fun JsonObject.requiredText(key: String) = optionalText(key) ?: throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
internal fun JsonObject.requiredScalar(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
