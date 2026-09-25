package cn.guahao.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

fun fixtureTask() = BookingTask("task", VisitCondition(PatientRef("test", "patient"),
    DepartmentRef("parent", "eye", "五官", "眼科", ""), "doctor", "示例医生",
    LocalDate.parse("2026-10-08"), 0, 1440, "2", 8000), Instant.parse("2026-10-01T00:00:00Z"))
fun fixtureCandidate() = Candidate(fixtureTask().condition.department, "doctor", "示例医生",
    LocalDate.parse("2026-10-08"), "1", "08:00-08:30", 480, 510, 5000, 1, "2", false)

class BookingPolicyTest {
    @Test fun configuredSixtyMinutesDoesNotExpireAtThirty() {
        val t = fixtureTask().copy(maxRuntimeMinutes = 60)
        assertTrue(eligible(t, fixtureCandidate(), t.releaseAt.plusSeconds(31 * 60)))
        assertFalse(eligible(t, fixtureCandidate(), t.deadline))
    }
    @Test fun exactConditionsAndFullTimeContainment() {
        val t = fixtureTask()
        for (c in listOf(fixtureCandidate().copy(doctorCode = "other"), fixtureCandidate().copy(feeFen = 8001),
            fixtureCandidate().copy(remaining = 0), fixtureCandidate().copy(standby = true),
            fixtureCandidate().copy(date = t.condition.visitDate.plusDays(1)))) {
            assertFalse(eligible(t, c, t.releaseAt))
        }
        assertFalse(eligible(t, fixtureCandidate(), t.releaseAt.minusSeconds(1)))
        assertFalse(eligible(t.copy(condition = t.condition.copy(startMinute = 490)), fixtureCandidate(), t.releaseAt))
    }
    @Test fun moneyIsExactAndBounded() {
        assertEquals(5010L, yuanToFen("50.10"))
        for (s in listOf("-1", "0.001", "999999999999999999999", "NaN", "1e3"))
            assertThrows(IllegalArgumentException::class.java) { yuanToFen(s) }
    }
    @Test fun alarmsDoNotResetDeadline() {
        val t = fixtureTask()
        assertFalse(shouldStart(t, 0, t.releaseAt))
        assertFalse(shouldStart(t, 1, t.deadline))
        assertTrue(shouldStart(t, 1, t.releaseAt.plusSeconds(60)))
    }
}
