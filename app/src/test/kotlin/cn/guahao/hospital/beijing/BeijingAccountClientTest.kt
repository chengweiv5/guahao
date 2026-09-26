package cn.guahao.hospital.beijing

import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeijingAccountClientTest {
    private val channel = RegistrationChannel.JINGTONG
    private fun ok(data: String) = BeijingQueryReply(200, "application/json", """{"code":"0000","data":$data}""")

    @Test fun accountIsCheckedBeforeAndAfterPatientsWithoutReturningUnneededIdentity() = runBlocking {
        val requests = mutableListOf<BeijingQueryRequest>()
        val source = BeijingAccountClient(BeijingQueryTransport {
            requests += it
            if (it.path == "auth/user/get") ok("""{"userId":"synthetic-account","phone":"synthetic-phone"}""")
            else ok("""{"patientList":[],"hisPatientList":[]}""")
        }, 0)
        val account = source.loadAccount(channel)
        assertEquals(channel, account.channel)
        assertEquals("synthetic-account", account.accountId)
        assertTrue(account.patients.isEmpty())
        assertEquals(listOf("auth/user/get", "auth/patient/list", "auth/user/get"), requests.map { it.path })
        assertEquals(listOf(buildJsonObject {}, null, buildJsonObject {}), requests.map { it.body })
        assertTrue(requests.all { it.channel == channel })
        assertFalse(account.toString().contains("synthetic"))
    }

    @Test fun accountSwitchDuringReadDiscardsPatients() = runBlocking {
        var count = 0
        val source = BeijingAccountClient(BeijingQueryTransport {
            if (it.path == "auth/user/get") ok("""{"userId":"account-${count++}"}""") else ok("""{"patientList":[]}""")
        }, 0)
        try { source.loadAccount(channel); fail() }
        catch (e: BeijingQueryException) { assertEquals(BeijingFailureKind.RECONNECT, e.kind) }
    }

    @Test fun unauthenticatedOrMalformedAccountNeverReadsPatientsOrExposesServerText() = runBlocking {
        for (reply in listOf(
            BeijingQueryReply(200, "application/json", """{"code":"3087","message":"private-secret"}"""),
            ok("""{"userId":""}"""), ok("""{"userId":123}"""),
            BeijingQueryReply(467, "text/html", "private-secret")
        )) {
            var count = 0
            val source = BeijingAccountClient(BeijingQueryTransport { count++; reply }, 0)
            try { source.loadAccount(channel); fail() }
            catch (e: BeijingQueryException) { assertFalse(e.message!!.contains("private-secret")) }
            assertEquals(1, count)
            assertFalse(reply.toString().contains("private-secret"))
        }
    }

    @Test fun privateReadPolicyRejectsMutationsAndArbitraryBodies() {
        val forbidden = listOf(
            BeijingQueryRequest(channel, "auth/order/save", buildJsonObject {}),
            BeijingQueryRequest(channel, "product/confirmV2", buildJsonObject {}),
            BeijingQueryRequest(channel, "auth/user/get", buildJsonObject { put("userId", "injected") }),
            BeijingQueryRequest(channel, "auth/patient/list", buildJsonObject {}),
            BeijingQueryRequest(channel, "auth/patient/list", suffix = "injected")
        )
        for (request in forbidden) {
            try { BeijingReadPolicy.validate(request); fail(request.path) } catch (_: IllegalArgumentException) { }
        }
    }
}
