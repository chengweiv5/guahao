package cn.guahao.core

import cn.guahao.core.psc.PscPageParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.Instant

class ScheduleTest {
    private val dept = fixtureTask().condition.department
    private fun parse(code: String, list: String = "null") = PscPageParser.schedule(
        """<script>var regisInfo = {"dayViews":[{"day":"2026-10-02","syqty":$code,"registryList":$list}]};</script>""", dept).single()

    @Test fun hospitalReleaseTimeUsesBeijingDateAndIsIndependentOfDoctorPublication() {
        val day = PscPageParser.schedule("""<script>var regisInfo = {"dayViews":[
            {"day":"20261010","syqty":-3,"syTime":"2026/09/26 15:00:00","registryList":null}
            ]};</script>""", dept).single().asScheduleDay()
        val at = Instant.parse("2026-09-26T07:00:00Z")
        assertEquals(at, day.hospitalReleaseAt)
        assertEquals(DateAvailability.NOT_RELEASED, day.status)
        val observation = day.observation(DoctorRef("doctor", "医生"), Instant.EPOCH)
        assertEquals(at, observation.hospitalReleaseAt)
        assertFalse(observation.doctorConfirmed)
        assertEquals(observation, Json.decodeFromString(ScheduleObservation.serializer(), Json.encodeToString(ScheduleObservation.serializer(), observation)))
        val old = """{"availability":"NOT_RELEASED","doctorConfirmed":false,"checkedAt":"1970-01-01T00:00:00Z"}"""
        assertNull(Json.decodeFromString(ScheduleObservation.serializer(), old).hospitalReleaseAt)
    }

    @Test fun invalidReleaseTimesDoNotChangeAvailabilityOrDiscardDoctors() {
        for (value in listOf("null", "\"\"", "\"15:00\"", "\"2026/02/30 15:00:00\"", "\"2026/09/26 25:00:00\"", "\"unexpected\"")) {
            val day = PscPageParser.schedule("""<script>var regisInfo = {"dayViews":[
                {"day":"20261010","syqty":-2,"syTime":$value,"registryList":null}
                ]};</script>""", dept).single()
            assertNull(day.hospitalReleaseAt)
            assertEquals(DateAvailability.NOT_RELEASED, day.status)
        }
        val noStock = PscPageParser.schedule("""<script>var regisInfo = {"dayViews":[
            {"day":"20261002","syqty":0,"syTime":"2026/09/26 15:00:00","registryList":null}
            ]};</script>""", dept).single()
        assertNull(noStock.hospitalReleaseAt)
        assertEquals(DateAvailability.NO_STOCK, noStock.status)
    }

    @Test fun explicitDateStatesSurviveIdenticalEmptyDoctorLists() {
        assertEquals(DateAvailability.NO_STOCK, parse("0").status)
        assertEquals(DateAvailability.NOT_RELEASED, parse("-2").status)
        assertEquals(DateAvailability.NOT_RELEASED, parse("-3").status)
        assertEquals(DateAvailability.UNKNOWN, parse("99").status)
        assertEquals(DateAvailability.AVAILABLE, parse("1").status)
    }
    @Test fun publishedDoctorWithoutSlotsIsStillAnIdentityNotInventory() {
        val day = parse("-3", """[{"doctor_code":"doctor","doctor":"示例医生","title_type":"4","count":"0","regHourList":[],"fee":"80","reg_half":"1","iscanceled":"0"}]""")
        assertEquals("doctor", day.doctors.single().code)
        assertTrue(day.candidates.isEmpty())
        assertEquals(DateAvailability.NOT_RELEASED, day.status)
    }
    @Test fun otherDateDoctorsRemainPreselectionOnlyAndMissingDateIsUnknown() {
        val d = DoctorRef("doctor", "示例医生")
        val schedule = DepartmentSchedule(dept, listOf(ScheduleDay(LocalDate.parse("2026-10-01"), DateAvailability.AVAILABLE, listOf(d), listOf(fixtureCandidate()))))
        assertEquals(listOf(d), schedule.doctors)
        val target = schedule.day(LocalDate.parse("2026-10-02"))
        assertEquals(DateAvailability.UNKNOWN, target.status)
        assertTrue(target.doctors.isEmpty()); assertTrue(target.candidates.isEmpty())
    }
    @Test fun oldTaskJsonLoadsWithoutScheduleObservation() {
        val raw = Json.encodeToString(BookingTask.serializer(), fixtureTask())
        assertFalse(raw.contains("initialSchedule"))
        assertNull(Json.decodeFromString(BookingTask.serializer(), raw).initialSchedule)
    }
    @Test fun oldRecordLoadsAndObservationsRoundTripIndependently() {
        val old = TaskRecord(fixtureTask(), TaskPhase.WAITING)
        val raw = Json.encodeToString(TaskRecord.serializer(), old)
        assertFalse(raw.contains("Schedule"))
        assertEquals(old, Json.decodeFromString(TaskRecord.serializer(), raw))
        val observed = old.copy(task = old.task.copy(initialSchedule = ScheduleObservation(
            DateAvailability.NOT_RELEASED, true, Instant.EPOCH)), latestSchedule = ScheduleObservation(
            DateAvailability.NO_STOCK, false, Instant.EPOCH.plusSeconds(20)))
        assertEquals(observed, Json.decodeFromString(TaskRecord.serializer(), Json.encodeToString(TaskRecord.serializer(), observed)))
        assertTrue(DoctorRef("a", "医生", "主任医师").sameIdentity(DoctorRef("a", "医生")))
    }
    @Test fun duplicateDatesAreUnknownAndDoNotSupplyInventory() {
        val day = ScheduleDay(fixtureCandidate().date, DateAvailability.AVAILABLE,
            listOf(DoctorRef("doctor", "示例医生")), listOf(fixtureCandidate()))
        val target = DepartmentSchedule(dept, listOf(day, day)).day(day.date)
        assertEquals(DateAvailability.UNKNOWN, target.status)
        assertTrue(target.doctors.isEmpty()); assertTrue(target.candidates.isEmpty())
    }
    @Test fun wrongDepartmentCannotConfirmDoctorOrSubmit() = runBlocking {
        val t = fixtureTask()
        val s = MemoryStore(TaskRecord(t, TaskPhase.WAITING))
        val g = object : FakeGateway() {
            override suspend fun schedule(query: ScheduleQuery) = DepartmentSchedule(dept.copy(code = "other"), listOf(
                ScheduleDay(query.visitDate, DateAvailability.AVAILABLE, listOf(DoctorRef("doctor", "示例医生")), listOf(fixtureCandidate()))))
        }
        BookingEngine(g, s, FakeClock()).run(t.id, t.generation, "test")
        assertEquals(0, g.locks); assertNull(s.record.latestSchedule)
        assertEquals(TaskPhase.NEEDS_ATTENTION, s.record.phase)
    }
    @Test fun noStockAndUnreleasedNeverSubmitEvenWithStalePositiveCandidate() = runBlocking {
        for (status in listOf(DateAvailability.NO_STOCK, DateAvailability.NOT_RELEASED, DateAvailability.UNKNOWN)) {
            val t = fixtureTask().copy(maxRuntimeMinutes = 1)
            val s = MemoryStore(TaskRecord(t, TaskPhase.WAITING))
            val g = object : FakeGateway() {
                override suspend fun schedule(query: ScheduleQuery) = DepartmentSchedule(dept, listOf(
                    ScheduleDay(query.visitDate, status, listOf(DoctorRef("doctor", "示例医生")), listOf(fixtureCandidate()))))
            }
            BookingEngine(g, s, FakeClock()).run(t.id, t.generation, "test")
            assertEquals(0, g.locks)
            assertEquals(status, s.record.latestSchedule?.availability)
            assertEquals(TaskPhase.EXPIRED, s.record.phase)
        }
    }
    @Test fun preselectedDoctorWaitsForTargetDateAndNeverSubstitutesAnotherDoctor() = runBlocking {
        val t = fixtureTask().copy(maxRuntimeMinutes = 1)
        val s = MemoryStore(TaskRecord(t, TaskPhase.WAITING))
        var calls = 0
        val g = object : FakeGateway() {
            override suspend fun schedule(query: ScheduleQuery): DepartmentSchedule {
                calls++
                val c = when(calls) {
                    1 -> fixtureCandidate().copy(date = query.visitDate.plusDays(1))
                    2 -> fixtureCandidate().copy(doctorCode = "other", doctorName = "其他医生")
                    else -> fixtureCandidate()
                }
                return DepartmentSchedule(dept, listOf(ScheduleDay(c.date, DateAvailability.AVAILABLE,
                    listOf(DoctorRef(c.doctorCode, c.doctorName)), listOf(c))))
            }
        }
        BookingEngine(g, s, FakeClock()).run(t.id, t.generation, "test")
        assertEquals(3, calls); assertEquals(1, g.locks)
        assertEquals(true, s.record.latestSchedule?.doctorConfirmed)
        assertEquals(TaskPhase.AWAITING_PAYMENT, s.record.phase)
    }
}
