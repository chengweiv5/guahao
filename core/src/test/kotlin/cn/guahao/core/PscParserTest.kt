package cn.guahao.core

import cn.guahao.core.psc.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PscParserTest {
    @Test fun pastedLinkAllowsSurroundingWhitespaceWithoutChangingCredential() {
        for (path in listOf("/admin/youmanage", "/regis/initDept")) {
            val link = "https://psc.hkinfo.net$path?userId=test&userIdKey=AB%2BC%3D&ptno=p"
            val imported = parseSessionLink(" \n$link\r\n ")
            assertEquals("test", imported.userId)
            assertEquals("AB+C=", imported.userKey)
            assertEquals("p", imported.ptno)
        }
    }
    @Test fun missingIdentityAndUnsupportedPagesHaveSpecificSafeErrors() {
        val missing = assertThrows(SessionLinkException::class.java) {
            parseSessionLink("https://psc.hkinfo.net/admin/youmanage?userId=test&userIdKey=secret")
        }
        assertTrue(missing.safeMessage.contains("缺少就诊人身份信息"))
        assertFalse(missing.safeMessage.contains("secret"))
        val unsupported = assertThrows(SessionLinkException::class.java) {
            parseSessionLink("https://psc.hkinfo.net/other?userId=test&userIdKey=secret&ptno=p")
        }
        assertTrue(unsupported.safeMessage.contains("暂不支持这个服务号页面"))
        assertFalse(unsupported.safeMessage.contains("secret"))
    }
    @Test fun importsDecodeOnlyOnceWithoutChangingPlus() {
        for ((encoded, expected) in listOf("AB%2BC%3D" to "AB+C=", "AB+C=" to "AB+C=", "%252B" to "%2B")) {
            val s = parseSessionLink("https://psc.hkinfo.net/regis/initDept?userId=test&userIdKey=$encoded&ptno=p")
            assertEquals(expected, s.userKey)
            assertFalse(s.toString().contains(expected))
        }
    }
    @Test fun rejectsAmbiguousAndForeignLinks() {
        val base = "https://psc.hkinfo.net/regis/initDept?userId=t&userIdKey=k&ptno=p"
        for (bad in listOf(base.replace("https:", "http:"), base.replace(".net", ".net.evil.org"),
            base.replace(".net", ".net:444"), base.replace("psc.hk", "u@psc.hk"), "$base#x", "$base&userIdKey=z", "$base&%75serId=t", base.replace("=k", "="), base.replace("=k", "=%00")))
            assertThrows(IllegalArgumentException::class.java) { parseSessionLink(bad) }
        assertThrows(IllegalArgumentException::class.java) { requireMatchingIdentity(ImportedSession("t", "k", "p"), "other", "p") }
    }
    @Test fun scansNestedJsonAndEscapedBracketsAcrossScripts() {
        val raw = """<script>var unused={};</script><script>
            var regisInfo = {"x":"a}b\"c", "nested":[{"n":1}]};</script>"""
        assertEquals("a}b\"c", PscPageParser.variable(raw, "regisInfo").jsonObject.text("x"))
        assertThrows(HospitalException::class.java) { PscPageParser.variable("<script>var regisInfo = evil();</script>", "regisInfo") }
        assertThrows(HospitalException::class.java) { PscPageParser.variable(raw + raw, "regisInfo") }
    }
    @Test fun scheduleUsesSlotCountsNotDayAvailability() {
        val html = javaClass.getResource("/psc/schedule.html")!!.readText()
        val day = PscPageParser.schedule(html, fixtureTask().condition.department).single { it.date.toString() == "2026-09-26" }
        val morning = day.candidates.filter { it.half == "1" }
        assertEquals(6, morning.size)
        assertEquals(10, morning.sumOf { it.remaining })
        assertEquals(1, day.availability)
    }
    @Test fun nullScheduleDayDoesNotHideDoctorsOnOtherDays() {
        val html = javaClass.getResource("/psc/schedule-null-day.html")!!.readText()
        val days = PscPageParser.schedule(html, fixtureTask().condition.department)
        assertEquals(2, days.size)
        assertTrue(days.first().candidates.isEmpty())
        assertEquals("测试医生", days.last().candidates.single().doctorName)
        assertEquals("08:00-08:30", days.last().candidates.single().hour)
        assertEquals(0, days.last().candidates.single().remaining)
    }
    @Test fun explicitNullAndEmptySchedulesAreEmptyButInvalidShapesAreRejected() {
        fun page(registry: String) = """<script>var regisInfo = {"dayViews":[{"day":"2026-09-25","syqty":"0",$registry}]};</script>"""
        for (value in listOf("null", "[]")) {
            val days = PscPageParser.schedule(page("\"registryList\":$value"), fixtureTask().condition.department)
            assertTrue(days.single().candidates.isEmpty())
        }
        for (registry in listOf("\"registryList\":{}", "\"registryList\":42", "\"unexpected\":[]")) {
            val error = assertThrows(HospitalException::class.java) {
                PscPageParser.schedule(page(registry), fixtureTask().condition.department)
            }
            assertEquals("医院排班列表格式变化，请稍后刷新或在官方页面核对", error.safeMessage)
        }
    }
    @Test fun endpointCodesStayDistinct() {
        assertEquals(LockReply.Accepted, decodeLockCode("0"))
        assertTrue(decodeAsync(responseObject("""{"code":0,"data":[]}""")) is AsyncReply.Unknown)
        assertEquals(AsyncReply.Pending, decodeAsync(responseObject("""{"code":2}""")))
        assertThrows(HospitalException::class.java) { parseOrders(responseObject("""{"code":0,"data":[]}"""), PatientRef("s","p")) }
    }
}
