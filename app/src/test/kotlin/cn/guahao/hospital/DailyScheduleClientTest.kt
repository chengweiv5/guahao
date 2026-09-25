package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import okhttp3.CookieJar
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyScheduleClientTest {
    @Test fun transportPreservesDateStatusAndOtherDateIdentityWithoutInventingTargetSlots() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = PscSession(PatientRef("test", "patient"), "user", "key", "patient", "patient-key", "测试就诊人")
            val secrets = object : SecretStore {
                val values = mutableMapOf("session-test" to pscJson.encodeToString(session))
                override fun read(name: String) = values[name]
                override fun write(name: String, text: String) { values[name] = text }
            }
            val sessions = SessionRepository(secrets, Mutex(), transportFactory = {
                PscTransport(CookieJar.NO_COOKIES, Mutex(), server.url("/"))
            })
            server.enqueue(MockResponse().setBody("""<script>var regisInfo = {"dayViews":[
                {"day":"20261002","syqty":0,"registryList":null},
                {"day":"20261010","syqty":-3,"registryList":null},
                {"day":"20261001","syqty":1,"registryList":[{"doctor_code":"doctor","doctor":"测试医生","title":"主任医师","title_type":"4","fee":"80","count":"1","reg_half":"1","iscanceled":"0","regHourList":[["08:00-08:30","1"]]}]}
                ]};</script>"""))
            val dept = DepartmentRef("parent", "eye", "五官科", "眼科", "origin")
            val query = ScheduleQuery(session.reference, dept, LocalDate.parse("2026-10-02"), "2")
            val schedule = PscClient(sessions).schedule(query)
            val target = schedule.day(query.visitDate)
            assertEquals(DateAvailability.NO_STOCK, target.status)
            assertTrue(target.doctors.isEmpty()); assertTrue(target.candidates.isEmpty())
            assertEquals(DateAvailability.NOT_RELEASED, schedule.day(LocalDate.parse("2026-10-10")).status)
            assertEquals(DateAvailability.UNKNOWN, schedule.day(LocalDate.parse("2026-10-11")).status)
            assertEquals(listOf(DoctorRef("doctor", "测试医生", "主任医师")), schedule.doctors)
            assertEquals(8000L, schedule.day(LocalDate.parse("2026-10-01")).candidates.single().feeFen)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/regis/initRegis", request.requestUrl!!.encodedPath)
            assertEquals(dept.code, request.requestUrl!!.queryParameter("deptCode2"))
            assertEquals(query.purpose, request.requestUrl!!.queryParameter("purpose"))
            assertEquals(1, server.requestCount)
        }
    }
}
