package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import okhttp3.CookieJar
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class SessionConnectionTest {
    private class MemorySecrets : SecretStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun read(name: String) = values[name]
        override fun write(name: String, text: String) { values[name] = text }
    }
    private val original = PscSession(PatientRef("saved", "test-patient"), "test-user", "test-key",
        "test-patient", "test-patient-key", "测试就诊人")
    private fun saved() = MemorySecrets().apply {
        write("session-saved", pscJson.encodeToString(original)); write("current-session", "saved")
    }
    private fun repository(vault: MemorySecrets, server: MockWebServer, clock: () -> Instant = Instant::now) =
        SessionRepository(vault, Mutex(), clock) { PscTransport(CookieJar.NO_COOKIES, Mutex(), server.url("/")) }
    private fun ok() = MockResponse().setBody("""{"code":"0"}""")

    @Test fun savedSessionChecksOnceAndNeverClaimsFreshAcrossProcessOrLongIdle() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val vault = saved(); var now = Instant.parse("2026-09-25T00:00:00Z")
            val sessions = repository(vault, server) { now }
            assertEquals(ConnectionStatus.SAVED, sessions.connection().health.status)
            server.enqueue(ok()); sessions.checkCurrent()
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
            assertEquals(original.reference, sessions.load(original.reference).reference)
            repeat(3) { sessions.checkCurrent() }
            assertEquals(1, server.requestCount)
            assertEquals("/function/functionControl", server.takeRequest().path)
            assertEquals(ConnectionStatus.SAVED, repository(vault, server) { now }.connection().health.status)
            now = now.plusSeconds(301)
            assertEquals(ConnectionStatus.SAVED, sessions.connection().health.status)
            server.enqueue(ok()); sessions.checkCurrent()
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unavailableNetworkOrMalformedReplyRetainsCredentialsAndCanRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val vault = saved(); val sessions = repository(vault, server)
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("<html>维护</html>"),
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/maintenance")), MockResponse().setResponseCode(403))) {
                server.enqueue(response); sessions.checkCurrent(true)
                assertEquals(ConnectionStatus.CHECK_FAILED, sessions.connection().health.status)
                assertFalse(sessions.connection().needsReconnect)
                assertEquals("test-key", sessions.load(original.reference).userKey)
            }
            server.enqueue(ok()); sessions.checkCurrent(true)
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
        }
    }

    @Test fun accessDenialIsNotLabeledExpiredAndInsuranceDenialDoesNotInvalidateBooking() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val sessions = repository(saved(), server)
            server.enqueue(MockResponse().setBody("""{"code":"-200","name":"参数不正确或缺少参数"}"""))
            sessions.checkCurrent(true)
            assertEquals(ConnectionStatus.BOOKING_UNAVAILABLE, sessions.connection().health.status)
            assertFalse(sessions.connection().needsReconnect)
            server.enqueue(ok()); sessions.checkCurrent(true)
            server.enqueue(MockResponse().setBody("""{"code":"-200"}"""))
            assertFalse(PscClient(sessions).insuranceAvailable(original.reference))
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
        }
    }

    @Test fun unauthorizedAndRedirectPersistReconnectWithoutFollowingOrRetrying() = runBlocking {
        for (status in listOf(401, 302)) MockWebServer().use { server ->
            server.start(); val vault = saved(); val sessions = repository(vault, server)
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "https://open.weixin.qq.com/connect/oauth2/authorize"))
            try { PscClient(sessions).departments(original.reference); fail("Expected login failure") }
            catch (e: HospitalException) { assertTrue(e.reconnectRequired) }
            assertTrue(sessions.connection().needsReconnect)
            assertTrue(repository(vault, server).connection().needsReconnect)
            sessions.checkCurrent(true)
            assertEquals(1, server.requestCount)
            assertNotNull(sessions.current())
            try { sessions.load(original.reference); fail("Invalid session loaded") }
            catch (e: HospitalException) { assertTrue(e.reconnectRequired) }
        }
    }

    @Test fun successfulReimportReplacesOnlyCurrentPointerAndRetainsOldIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val vault = saved(); val sessions = repository(vault, server)
            sessions.recordFailure(original.reference, HospitalException("test", reconnectRequired = true))
            server.enqueue(MockResponse().setBody("<html>synthetic</html>"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"id":"new-user","ptno":"new-patient","name":"新测试就诊人"}]}"""))
            server.enqueue(MockResponse().setBody("""{"code":2,"data":{"id":"new-user","ptno":"new-patient","userIdKey":"new-key","ptnoKey":"new-patient-key"}}"""))
            server.enqueue(ok())
            val replacement = sessions.importAndVerify("https://psc.hkinfo.net/regis/initDept?userId=new-user&userIdKey=new-key&ptno=new-patient")
            assertNotEquals(original.reference, replacement.reference)
            assertEquals("new-patient", sessions.current()!!.ptno)
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
            assertEquals(listOf("/regis/initDept", "/admin/getchargename", "/patient/changePatient", "/function/functionControl"),
                List(4) { server.takeRequest().requestUrl!!.encodedPath })
            assertNotNull(vault.read("session-saved"))
            // An in-flight failure for the old session cannot invalidate the new patient.
            sessions.recordFailure(original.reference, HospitalException("old", reconnectRequired = true))
            assertFalse(sessions.connection().needsReconnect)
        }
    }

    @Test fun failedReimportNeverReplacesExistingSession() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val vault = saved(); val sessions = repository(vault, server)
            server.enqueue(MockResponse().setResponseCode(401))
            try { sessions.importAndVerify("https://psc.hkinfo.net/regis/initDept?userId=new&userIdKey=new&ptno=new"); fail() }
            catch (_: HospitalException) { }
            assertEquals(original.reference, sessions.current()!!.reference)
            assertEquals("test-key", sessions.load(original.reference).userKey)
        }
    }

    @Test fun simultaneousForegroundChecksCoalesceAndCancellationIsNotExpiry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val sessions = repository(saved(), server)
            server.enqueue(ok().setBodyDelay(100, TimeUnit.MILLISECONDS))
            coroutineScope { repeat(3) { launch { sessions.checkCurrent() } } }
            assertEquals(1, server.requestCount)
            sessions.recordFailure(original.reference, CancellationException())
            assertEquals(ConnectionStatus.VERIFIED, sessions.connection().health.status)
        }
    }
}
