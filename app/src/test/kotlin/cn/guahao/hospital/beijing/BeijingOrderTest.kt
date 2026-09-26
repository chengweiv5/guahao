package cn.guahao.hospital.beijing

import cn.guahao.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeijingOrderTest {
    private fun detail(id: String = "order", status: Int = 1, pay: JsonElement = JsonNull) = buildJsonObject {
        put("orderBaseInfo", buildJsonObject {
            put("orderId", id); put("patientId", JtFixture.patient.id); put("hosCode", JtFixture.selection.hospitalId)
            put("doctorCode", "doctor"); put("doctorName", "测试医生"); put("secondDeptCode", "child"); put("secondDeptName", "测试科室")
            put("treatmentDay", "20261008"); put("productTime", "2026年10月8日 上午 08:00~08:30"); put("price", "50.00")
            put("orderStatus", status); put("orderStatusView", "官方状态"); put("takeTimeTips", "按医院要求取号")
        }); put("payInfo", pay)
    }
    private fun parse(data: JsonElement) = BeijingOrderParser.parse(data, JtFixture.selection, "order", JtFixture.date, JtFixture.date)
    private fun invalid(data: JsonElement) { assertThrows(BeijingQueryException::class.java) { parse(data) } }
    private fun row(id: String) = buildJsonObject { put("isNew", 1); put("orderId", id); put("patientId", JtFixture.patient.id); put("hosCode", JtFixture.selection.hospitalId) }

    @Test fun onsiteReservationAndRequiredPaymentHaveDifferentOutcomes() {
        val onsite = parse(detail())
        assertEquals(OrderPhase.RESERVED_ONSITE, onsite.phase)
        assertEquals(PaymentAction.BOOKED, paymentAction(onsite, PaymentPreference.INSURANCE_FIRST))
        assertNull(onsite.invalidAt)
        val required = parse(detail(pay = buildJsonObject { put("mustPay", "BIND") }))
        assertEquals(OrderPhase.LOCKED, required.phase)
        assertEquals(PaymentAction.OFFICIAL_PAYMENT, paymentAction(required, PaymentPreference.INSURANCE_FIRST))
        assertEquals(OrderPhase.BOOKED, parse(detail(pay = buildJsonObject { put("payStatus", 6) })).phase)
        assertEquals(PaymentAction.NEEDS_ATTENTION, paymentAction(parse(detail(status = 2)), PaymentPreference.INSURANCE_FIRST))
    }

    @Test fun missingMalformedUnknownPaymentOrOrderStatusNeverMeansReservationSuccess() {
        val original = detail()
        invalid(JsonObject(original - "payInfo"))
        invalid(detail(pay = JsonPrimitive("bad")))
        invalid(detail(pay = buildJsonObject { put("payStatus", 5) }))
        invalid(detail(status = 999))
    }

    @Test fun mismatchedOwnerHospitalDateAndAmbiguousTimeAreRejected() {
        val original = detail()
        for ((key, value) in listOf("patientId" to "other", "hosCode" to "other", "orderId" to "other", "treatmentDay" to "20261009", "productTime" to "08:00-08:30 09:00-09:30")) {
            invalid(JsonObject(original + ("orderBaseInfo" to JsonObject(original["orderBaseInfo"]!!.jsonObject + (key to JsonPrimitive(value))))))
        }
    }

    @Test fun completePaginationUsesVisitDateAndValidatesEveryDetail() = runBlocking {
        val pages = mutableListOf<Int>(); var details = 0
        val client = BeijingOrderClient(BeijingQueryTransport { req ->
            BeijingReadPolicy.validate(req)
            if (req.path.endsWith("getOrderListV2")) {
                assertEquals(1, req.body!!["sortTimeType"]!!.jsonPrimitive.int)
                assertEquals(JtFixture.selection.hospitalId, req.body["hosCode"]!!.jsonPrimitive.content)
                val page = req.body["pageNo"]!!.jsonPrimitive.int; pages += page
                JtFixture.reply(buildJsonObject { put("count", 21); put("list", JsonArray((if (page == 1) 1..20 else 21..21).map { row("order-$it") })) })
            } else { details++; JtFixture.reply(detail(req.body!!["orderId"]!!.jsonPrimitive.content)) }
        }, 0)
        assertEquals(21, client.orders(JtFixture.selection, JtFixture.date, JtFixture.date).size)
        assertEquals(listOf(1, 2), pages); assertEquals(21, details)
    }

    @Test fun partialDuplicateAndLegacyListsNeverBecomeEmptyBaseline() = runBlocking {
        for (data in listOf(
            buildJsonObject { put("count", 2); put("list", JsonArray(listOf(row("order")))) },
            buildJsonObject { put("count", 2); put("list", JsonArray(listOf(row("order"), row("order")))) },
            buildJsonObject { put("count", 1); put("list", JsonArray(listOf(JsonObject(row("order") + ("isNew" to JsonPrimitive(0)))))) }
        )) {
            val client = BeijingOrderClient(BeijingQueryTransport { JtFixture.reply(if (it.path.endsWith("detail")) detail() else data) }, 0)
            try { client.orders(JtFixture.selection, JtFixture.date, JtFixture.date); fail() } catch (_: BeijingQueryException) { }
        }
    }
}
