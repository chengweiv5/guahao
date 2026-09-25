package cn.guahao.hospital.beijing

import cn.guahao.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic bodies matching the observed public schema; not live platform verification. */
class BeijingQueryClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BeijingQueryClient
    private val channel = RegistrationChannel.BEIJING_114
    private val department = DepartmentRef("parent", "department", "大科室", "子科室", "")
    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = BeijingQueryClient(server.url("/jtjk/mobile-service/"), OkHttpClient(), 0)
    }
    @After fun close() { server.shutdown() }
    private fun reply(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private suspend fun failure(expected: BeijingFailureKind, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (e: BeijingQueryException) { assertEquals(expected, e.kind); assertFalse(e.message.orEmpty().contains("secret")) }
    }

    @Test fun channelsAreExplicitAndCookiesNeverCross() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Set-Cookie", "secret=one")
            .setBody("""{"code":"0000","data":{"list":[{"code":"h","name":"测试医院"}],"count":1}}"""))
        assertEquals("测试医院", client.hospitals(channel).hospitals.single().name)
        val request = server.takeRequest()
        assertEquals("WE_CHAT", request.getHeader("Request-Source"))
        assertEquals("POST", request.method)
        assertEquals("/jtjk/mobile-service/hospital/list", request.requestUrl!!.encodedPath)
        reply("""{"code":"0000","data":{"list":[],"count":0}}""")
        assertTrue(client.hospitals(RegistrationChannel.JINGTONG).hospitals.isEmpty())
        val jt = server.takeRequest()
        assertEquals("JT_WECHAT", jt.getHeader("Request-Source"))
        assertNull(jt.getHeader("Cookie"))
    }

    @Test fun htmlChallengeIsNotEmptyDirectoryOrSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Type", "text/html").setBody("<html>secret</html>"))
        failure(BeijingFailureKind.CLIENT_VERIFICATION) { client.hospitals(channel) }
        assertEquals(1, server.requestCount)
    }

    @Test fun successfulHttpStillRequiresExactBusinessCodeAndSchema() = runBlocking {
        reply("""{"code":"0001","message":"secret"}""")
        failure(BeijingFailureKind.RECONNECT) { client.hospitals(channel) }
        reply("""{"code":"8888","message":"secret"}""")
        failure(BeijingFailureKind.BUSINESS) { client.hospitals(channel) }
        reply("""{"code":"0000","data":{"list":[],"count":{}}}""")
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.hospitals(channel) }
        reply("""{"code":0,"data":{"list":[],"count":0}}""")
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.hospitals(channel) }
    }

    @Test fun redirectIsNotFollowedAndRateLimitDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/auth?secret=one"))
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.hospitals(channel) }
        server.enqueue(MockResponse().setResponseCode(429))
        failure(BeijingFailureKind.RATE_LIMITED) { client.hospitals(channel) }
        assertEquals(2, server.requestCount)
    }

    @Test fun departmentParentMismatchIsRejected() = runBlocking {
        reply("""{"code":"0000","data":[{"code":"parent","name":"大科室","subList":[{"code":"department","name":"子科室","parentCode":"parent"}]}]}""")
        assertEquals(department, client.departments(channel, "hospital").single())
        reply("""{"code":"0000","data":[{"code":"parent","name":"大科室","subList":[{"code":"department","name":"子科室","parentCode":"other"}]}]}""")
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.departments(channel, "hospital") }
    }

    @Test fun unknownAndUnreleasedCalendarRemainDifferentFromNoStock() = runBlocking {
        reply("""{"code":"0000","data":{"calendars":[{"dutyDate":"20260926","status":6,"statusView":"即将放号"},{"dutyDate":"20260927","status":999,"statusView":"候补"},{"dutyDate":"20260928","status":2,"statusView":"无号"}]}}""")
        val days = client.calendar(channel, "hospital", department)
        assertEquals(listOf(DateAvailability.NOT_RELEASED, DateAvailability.UNKNOWN, DateAvailability.NO_STOCK), days.map { it.availability })
        assertEquals("候补", days[1].officialLabel)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"hosCode\":\"hospital\""))
    }

    @Test fun invalidOrDuplicateDatesAreRejected() = runBlocking {
        reply("""{"code":"0000","data":{"calendars":[{"dutyDate":"20260230","statusView":"有号"}]}}""")
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.calendar(channel, "hospital", department) }
        reply("""{"code":"0000","data":{"calendars":[{"dutyDate":"20260926","statusView":"有号"},{"dutyDate":"20260926","statusView":"无号"}]}}""")
        failure(BeijingFailureKind.INVALID_RESPONSE) { client.calendar(channel, "hospital", department) }
    }
}
