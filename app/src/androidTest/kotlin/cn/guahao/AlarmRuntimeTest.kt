package cn.guahao

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cn.guahao.core.*
import cn.guahao.hospital.DemoGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Background execution does not need an Activity or a Compose lifecycle. */
@RunWith(AndroidJUnit4::class)
class AlarmRuntimeTest {
    @Test fun exactAlarmStartsServiceWithScreenOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = context.graph
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = settings.getBoolean("batteryAcknowledged", false)

        // Retain all previous records and the real duplicate-order protection. A repeat
        // run uses an unused synthetic date so an earlier demo order cannot block it.
        val usedDates = graph.store.all().filter { it.task.demo }
            .map { it.task.condition.visitDate }.toSet()
        val date = generateSequence(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(9)) { it.plusDays(1) }
            .first { it !in usedDates }
        val release = Instant.now().plusSeconds(10)
        val condition = VisitCondition(DemoGateway.patient, DemoGateway.department,
            "demo-doctor", "林医生（虚构）", date, 0, 1440, "2", 8000)
        val task = BookingTask(UUID.randomUUID().toString(), condition, release, maxRuntimeMinutes = 1)
        graph.store.save(TaskRecord(task, TaskPhase.WAITING, note = "演示定时设备测试"))
        try {
            settings.edit().putBoolean("batteryAcknowledged", true).commit()
            graph.scheduler.schedule(task)
            device.sleep()
            val screenOffAtStart = !device.isScreenOn
            var screenOnObserved = !screenOffAtStart
            val end = SystemClock.elapsedRealtime() + 45000
            while (SystemClock.elapsedRealtime() < end) {
                screenOnObserved = screenOnObserved || device.isScreenOn
                val current = graph.store.get(task.id)
                if (current.order?.phase == OrderPhase.INSURANCE_PENDING ||
                    current.phase in setOf(TaskPhase.NEEDS_ATTENTION, TaskPhase.EXPIRED, TaskPhase.STOPPED)) break
                delay(250)
            }
            val result = graph.store.get(task.id)
            val checkedAt = Instant.now()
            val screenOnAtEnd = device.isScreenOn
            val attemptDelay = result.attempt?.let { Duration.between(release, it.sentAt).toMillis() }
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: context.getExternalFilesDir(null)!!
            output.mkdirs()
            File(output, "alarm-evidence.txt").writeText(
                "release=$release\nattempt=${result.attempt?.sentAt}\ndelayMillis=$attemptDelay\n" +
                    "phase=${result.phase}\norder=${result.order?.phase}\nscreenOn=$screenOnAtEnd\n" +
                    "screenOffAtStart=$screenOffAtStart\nscreenOnObservedDuringWait=$screenOnObserved\n" +
                    "checkedAt=$checkedAt\nsyntheticVisitDate=$date\n")
            assertTrue("Device did not enter screen-off state", screenOffAtStart)
            assertNotNull("No attempt: ${result.phase}, ${result.note}", result.attempt)
            assertEquals(OrderPhase.INSURANCE_PENDING, result.order?.phase)
            assertFalse(result.attempt!!.sentAt.isBefore(release))
            assertFalse("Screen woke during the execution window", screenOnObserved || screenOnAtEnd)
        } finally {
            graph.scheduler.cancel(task)
            settings.edit().putBoolean("batteryAcknowledged", previous).commit()
            device.wakeUp()
        }
    }
}
