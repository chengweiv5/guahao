package cn.guahao.hospital.beijing

import cn.guahao.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BeijingQueryTransportTest {
    @Test fun doctorQueryUsesTheSelectedChannelAndFullDepartmentDate() = runBlocking {
        var received: BeijingQueryRequest? = null
        val client = BeijingQueryClient(BeijingQueryTransport {
            received = it
            BeijingQueryReply(200, "application/json", """{"code":"0000","data":{"doctors":[]}}""")
        })
        val department = DepartmentRef("parent", "child", "大科室", "子科室", "")
        val schedule = client.doctors(RegistrationChannel.JINGTONG, "hospital", department, LocalDate.of(2026, 9, 29))
        assertTrue(schedule.doctors.isEmpty())
        assertEquals(RegistrationChannel.JINGTONG, received!!.channel)
        assertEquals("product/doctor/detail", received!!.path)
        assertEquals("hospital", received!!.body!!["hosCode"]!!.jsonPrimitive.content)
        assertEquals("parent", received!!.body!!["firstDeptCode"]!!.jsonPrimitive.content)
        assertEquals("child", received!!.body!!["secondDeptCode"]!!.jsonPrimitive.content)
        assertEquals("20260929", received!!.body!!["dutyDate"]!!.jsonPrimitive.content)
    }
    @Test fun browserReplyRequiresTheSameSuccessAndSchemaChecks() = runBlocking {
        val client = BeijingQueryClient(BeijingQueryTransport {
            BeijingQueryReply(200, "application/json", """{"code":"3087","message":"private"}""")
        })
        try { client.hospitals(RegistrationChannel.JINGTONG); fail() }
        catch (e: BeijingQueryException) {
            assertEquals(BeijingFailureKind.RECONNECT, e.kind)
            assertFalse(e.message!!.contains("private"))
        }
    }
    @Test fun publicQueryPolicyCannotExpressBookingPrivateReadsOrUrlTraversal() {
        for (path in listOf("auth/user/get", "auth/patient/list", "order/submit", "../auth/user/get", "https://example.com")) {
            try {
                BeijingQueryPolicy.validate(BeijingQueryRequest(RegistrationChannel.JINGTONG, path, buildJsonObject {}))
                fail(path)
            } catch (_: IllegalArgumentException) { }
        }
        for (suffix in listOf("../auth", "h?token=x", "h/x", "")) {
            try {
                BeijingQueryPolicy.validate(BeijingQueryRequest(RegistrationChannel.JINGTONG, "department/list", suffix = suffix))
                fail(suffix)
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun browserPayloadIsJsonMarshalledWithoutInterpolatingExecutableFields() {
        val hostile = "');window.bad=true;//"
        val request = BeijingQueryRequest(RegistrationChannel.JINGTONG, "product/calendar",
            buildJsonObject { put("hosCode", hostile) })
        val script = BeijingQueryPolicy.browserScript(request, "0".repeat(32))
        val config = Json.parseToJsonElement(script.substringAfterLast("})(").removeSuffix(")")).jsonObject
        assertEquals(hostile, config["body"]!!.jsonObject["hosCode"]!!.jsonPrimitive.content)
        assertEquals("jtwechat", config["channel"]!!.jsonPrimitive.content)
        assertEquals("JT_WECHAT", config["source"]!!.jsonPrimitive.content)
    }
}
