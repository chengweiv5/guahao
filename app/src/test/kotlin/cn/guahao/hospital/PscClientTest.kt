package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PscClientTest {
    @Test fun queuedRequestRechecksDeadlineAfterSharedProviderBudget() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val gate = Mutex(); val budget = RequestBudget(150)
            val a = PscTransport(CookieJar.NO_COOKIES, gate, server.url("/"), budget)
            val b = PscTransport(CookieJar.NO_COOKIES, gate, server.url("/"), budget)
            server.enqueue(MockResponse().setBody("{\"code\":0}"))
            a.post("/read", emptyMap())
            var allowed = true
            val pending = async { runCatching { b.post("/lock", emptyMap()) { allowed } } }
            delay(30); allowed = false
            assertTrue(pending.await().isFailure)
            assertEquals(1, server.requestCount)
        }
    }
    private fun session() = PscSession(PatientRef("synthetic-session","synthetic-patient"),"synthetic-user","AB+C=","synthetic-patient","test-pt-key","测试就诊人")
    @Test fun lockFieldsFollowOfficialProtocolExactly() {
        val d=DepartmentRef("1037","1308","五官","眼科","")
        val t=BookingTask("task",VisitCondition(session().reference,d,"doctor","测试医生",LocalDate.parse("2026-10-08"),0,1440,"2",8000),Instant.parse("2026-10-01T00:00:00Z"),demo=false)
        val c=Candidate(d,"doctor","测试医生",t.condition.visitDate,"1","09:00-09:30",540,570,5000,1,"2",false)
        val p=lockPayload(session(),t,c)
        assertEquals(setOf("userId","userIdKey","dept_code1","dept_code2","purpose","dept_nm1","dept_nm2","reg_date","reg_half","reg_hour","doctor_code","title_type","iscanceled","doctor","dept_code2_from"),p.keys)
        assertEquals("20261008",p["reg_date"]); assertEquals("AB+C=",p["userIdKey"])
        assertEquals("AB%2BC%3D",insurancePayload(session(),"order")["userIdKey"])
    }
    @Test fun disconnectAfterRequestHasExactlyOneMutation() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val transport=PscTransport(CookieJar.NO_COOKIES,Mutex(),server.url("/"))
            try { transport.post("/regis/lockRegis",mapOf("userId" to "test")); fail("Expected response failure") } catch (_: HospitalException) { }
            assertEquals(1,server.requestCount)
            assertEquals("POST",server.takeRequest().method)
        } finally { server.shutdown() }
    }
    @Test fun refusesRedirectWithoutForwardingIdentity() = runBlocking {
        val server=MockWebServer(); val other=MockWebServer(); server.start(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/steal")))
            val transport=PscTransport(CookieJar.NO_COOKIES,Mutex(),server.url("/"))
            try { transport.get("/regis/initDept",mapOf("userIdKey" to "AB+C=")); fail("redirect followed") } catch (_: HospitalException) { }
            assertEquals(0,other.requestCount)
            assertTrue(server.takeRequest().path!!.contains("AB%2BC%3D"))
        } finally { server.shutdown(); other.shutdown() }
    }
    @Test fun serverBackoffIsReturnedToTheEngine() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","90"))
            try { PscTransport(CookieJar.NO_COOKIES,Mutex(),server.url("/")).get("/regis/initDept",emptyMap()); fail() }
            catch (e: HospitalException) { assertTrue(e.retryable); assertEquals(90000L,e.retryAfterMillis) }
        } finally { server.shutdown() }
    }
    @Test fun cookiesRespectSecurePathExpiryAndReplacement() {
        var saved=emptyList<String>()
        val jar=PscCookieJar({saved},{saved=it})
        val url="https://psc.hkinfo.net/regis/initDept".toHttpUrl()
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"SID=one; Path=/regis; Secure")!!))
        assertEquals("one",jar.loadForRequest(url).single().value)
        assertTrue(jar.loadForRequest("https://psc.hkinfo.net/order/sxPayCN".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://psc.hkinfo.net/regis/initDept".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://evil.org/regis/initDept".toHttpUrl()).isEmpty())
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"SID=two; Path=/regis; Secure")!!))
        assertEquals("two",PscCookieJar({saved},{}).loadForRequest(url).single().value)
        jar.saveFromResponse(url,listOf(Cookie.parse(url,"SID=gone; Path=/regis; Max-Age=0; Secure")!!))
        assertTrue(jar.loadForRequest(url).isEmpty())
    }
    @Test fun orderSevenWithZeroInsuranceFlagStaysInsurancePending() {
        val root=responseObject("""{"code":200,"data":[{"orderno":"test-order","ptno":"synthetic-patient","doctor":"测试医生","dept":"眼科","actdate":"2026-10-08","ampm":"上午","reserved_date":"09:00-09:30","fee":"50.00","status":"7","source":"2","invalidtime":"2026-10-01 08:30:00","isyb":"0","iscanceled":"0"}]}""")
        val order=parseOrders(root,session().reference).single()
        assertEquals(OrderPhase.INSURANCE_PENDING,order.phase)
        assertEquals(PaymentAction.INSURANCE_PAYMENT,paymentAction(order,PaymentPreference.INSURANCE_FIRST))
        assertEquals(Instant.parse("2026-10-01T00:30:00Z"),order.invalidAt)
    }
}
