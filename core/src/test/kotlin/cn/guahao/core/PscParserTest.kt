package cn.guahao.core

import cn.guahao.core.psc.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PscParserTest {
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
    @Test fun endpointCodesStayDistinct() {
        assertEquals(LockReply.Accepted, decodeLockCode("0"))
        assertTrue(decodeAsync(responseObject("""{"code":0,"data":[]}""")) is AsyncReply.Unknown)
        assertEquals(AsyncReply.Pending, decodeAsync(responseObject("""{"code":2}""")))
        assertThrows(HospitalException::class.java) { parseOrders(responseObject("""{"code":0,"data":[]}"""), PatientRef("s","p")) }
    }
}
