package cn.guahao

import cn.guahao.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AppModeTest {
    private fun task(demo: Boolean, session: String) = BookingTask(
        "mode-$demo-$session",
        VisitCondition(PatientRef(session, "synthetic"), DepartmentRef("p", "d", "parent", "dept", ""),
            "doctor", "synthetic doctor", LocalDate.of(2030, 1, 1), 0, 1440, "2", 8000),
        Instant.parse("2030-01-01T00:00:00Z"), demo = demo)

    @Test fun releaseExcludesEitherDemoMarkerButKeepsRealRecords() {
        val mode = AppMode(false)
        val real = task(false, "real-session")
        val records = listOf(real, task(true, "demo"), task(false, "demo"), task(true, "real-session"), task(false, "demo-b")).map(::TaskRecord)
        assertEquals(listOf(TaskRecord(real)), mode.visible(records))
        assertFalse(mode.allows(PatientRef("demo", "synthetic")))
        assertTrue(mode.allows(real.condition.patient))
        records.drop(1).forEach {
            try { mode.requireAllowed(it.task); fail("Demo task was accepted") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun debugKeepsBothModes() {
        val records = listOf(task(true, "demo"), task(false, "real-session")).map(::TaskRecord)
        assertEquals(records, AppMode(true).visible(records))
    }

    @Test fun compiledDefaultMatchesBuildVariant() {
        assertEquals(BuildConfig.BUILD_TYPE == "debug", AppMode().demoEnabled)
    }
}
