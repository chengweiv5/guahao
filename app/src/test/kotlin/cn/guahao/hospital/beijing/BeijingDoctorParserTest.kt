package cn.guahao.hospital.beijing

import cn.guahao.core.DepartmentRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BeijingDoctorParserTest {
    private val department = DepartmentRef("p", "d", "内科", "测试科室", "")
    private val date = LocalDate.of(2026, 9, 29)
    private val fixture = """{"doctors":[{"doctorCode":"doctor","doctorName":"测试医生","firstDeptCode":"p","secondDeptCode":"d","titleView":"主任医师","detail":[{"dutyCode":2,"dutyCodeView":"下午","fcode":"50.00","ncode":"36","productStatus":1,"productStatusView":"有号","uniqueProductKey":"product","period":[{"create":false,"dutyTime":"202609291500-202609291530","dutyTimeView":"15:00-15:30","ncode":"7","nncode":null,"uniqProductKey":"time"}]}]}]}"""
    private fun parse(raw: String = fixture) = BeijingDoctorParser.parse(Json.parseToJsonElement(raw), department, date)
    private fun invalid(raw: String) {
        try { parse(raw); fail("Expected invalid response") }
        catch (e: BeijingQueryException) { assertEquals(BeijingFailureKind.INVALID_RESPONSE, e.kind) }
    }
    @Test fun moneyTimeAndProductKeysRetainIndependentMeaning() {
        val slot = parse().doctors.single().slots.single()
        assertEquals(5000L, slot.feeFen)
        assertEquals(36, slot.remaining)
        assertEquals(BeijingStock.AVAILABLE, slot.stock)
        assertEquals("product", slot.productKey)
        val period = slot.periods.single()
        assertEquals("time", period.productTimeKey)
        assertEquals(900, period.startMinute)
        assertEquals(930, period.endMinute)
        assertEquals(7, period.remaining)
    }
    @Test fun waitlistUnknownAndMalformedPriceCannotBecomeBookableStock() {
        val waitlist = parse(fixture.replace("\"productStatus\":1", "\"productStatus\":8").replace("有号", "候补"))
        assertEquals(BeijingStock.WAITLIST, waitlist.doctors.single().slots.single().stock)
        val unknown = parse(fixture.replace("\"productStatus\":1", "\"productStatus\":99"))
        assertEquals(BeijingStock.UNKNOWN, unknown.doctors.single().slots.single().stock)
        for (price in listOf("-1", "0.001", "NaN", "999999999999999999999999")) {
            assertNull(parse(fixture.replace("50.00", price)).doctors.single().slots.single().feeFen)
        }
    }
    @Test fun wrongDepartmentAndWrongDateAreRejected() {
        invalid(fixture.replace("\"secondDeptCode\":\"d\"", "\"secondDeptCode\":\"other\""))
        invalid(fixture.replace("202609291500-202609291530", "202609301500-202609301530"))
        invalid(fixture.replace("202609291500-202609291530", "202609291530-202609291500"))
    }
    @Test fun missingTimesAndInventoryRemainUnknownAndEmptyDoctorListIsValid() {
        val slot = parse(fixture.replace("202609291500-202609291530", "下午").replace("\"ncode\":\"7\"", "\"ncode\":null"))
            .doctors.single().slots.single()
        assertNull(slot.periods.single().startMinute)
        assertNull(slot.periods.single().remaining)
        assertTrue(parse("""{"doctors":[]}""").doctors.isEmpty())
        invalid("""{"doctors":null}""")
    }
    @Test fun duplicateDoctorsAndProductTimesCannotBeSilentlyCollapsed() {
        val root = Json.parseToJsonElement(fixture).jsonObject
        val doctor = root["doctors"]!!.jsonArray.single().jsonObject
        invalid(buildJsonObject { put("doctors", JsonArray(listOf(doctor, doctor))) }.toString())
        val slot = doctor["detail"]!!.jsonArray.single().jsonObject
        val time = slot["period"]!!.jsonArray.single()
        val duplicateSlot = JsonObject(slot + ("period" to JsonArray(listOf(time, time))))
        val duplicateDoctor = JsonObject(doctor + ("detail" to JsonArray(listOf(duplicateSlot))))
        invalid(buildJsonObject { put("doctors", JsonArray(listOf(duplicateDoctor))) }.toString())
    }
}
