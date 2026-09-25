package cn.guahao

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.hospital.DemoGateway
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationAlertTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(): TaskRecord {
        val condition = VisitCondition(DemoGateway.patient, DemoGateway.department, "demo-doctor", "林医生（虚构）",
            LocalDate.now().plusDays(20), 0, 1440, "2", 8000)
        val task = BookingTask(UUID.randomUUID().toString(), condition, Instant.now(), maxRuntimeMinutes=1)
        val order = OrderSnapshot("demo-notification", condition.patient, condition.doctorCode, condition.department.code,
            condition.doctorName, condition.department.name, "2", condition.visitDate, "1", "09:00-09:30", 5000,
            OrderPhase.INSURANCE_PENDING, "7", Instant.now().plusSeconds(1800), false)
        return TaskRecord(task, TaskPhase.AWAITING_PAYMENT, order=order)
    }
    @Suppress("DEPRECATION")
    @Test fun runningUpdatesAreSilentAndOnlyAlertOnce() {
        val notification = context.graph.notifications.running(record())
        assertTrue("Runtime updates may re-alert each second", notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Runtime notification must use silent group behavior", Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
    }
    @Suppress("DEPRECATION")
    @Test fun resultKeepsSystemAlertSettingsAndSurvivesProgressRemoval() {
        val notifications = context.graph.notifications
        val manager = context.getSystemService(NotificationManager::class.java)
        val r = record()
        val id = r.task.id
        val originalChannel = manager.getNotificationChannel("results")
        val originalSound = originalChannel.sound
        val originalVibrate = originalChannel.shouldVibrate()
        val originalPattern = originalChannel.vibrationPattern
        val before = manager.activeNotifications.firstOrNull { it.id == 1 }?.notification
        try {
            manager.notify(1, notifications.running(r))
            notifications.result(r)
            manager.notify(1, notifications.running(r))
            manager.cancel(1)
            Thread.sleep(150)
            val result = manager.activeNotifications.single { it.tag == id && it.id == 3 }.notification
            val channel = manager.getNotificationChannel(result.channelId)
            assertEquals("Keep the user's channel", "results", result.channelId)
            assertEquals(originalSound, channel.sound)
            assertEquals(originalVibrate, channel.shouldVibrate())
            assertArrayEquals(originalPattern, channel.vibrationPattern)
            assertEquals("Honor the user's sound in the compatibility layer", channel.sound, result.sound)
            if (channel.shouldVibrate() && channel.vibrationPattern == null) {
                assertTrue("Use the system vibration when no custom pattern is selected", result.defaults and Notification.DEFAULT_VIBRATE != 0)
                assertNull("Do not invent an application vibration pattern", result.vibrate)
            } else {
                assertArrayEquals(if (channel.shouldVibrate()) channel.vibrationPattern else null, result.vibrate)
                assertEquals(0, result.defaults and Notification.DEFAULT_VIBRATE)
            }
            assertTrue("Repeat delivery must not restart sound", result.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            // Give system-owned sound/vibration time to finish; task service termination must not cancel it.
            Thread.sleep(8000)
            assertTrue(manager.activeNotifications.any { it.tag == id && it.id == 3 })
        } finally {
            manager.cancel(id, 3)
            if (before != null) manager.notify(1, before) else manager.cancel(1)
        }
    }
    @Test fun alarmResultRemainsAfterServiceFinishes() {
        val graph = context.graph
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = settings.getBoolean("batteryAcknowledged", false)
        val used = graph.store.all().filter { it.task.demo }.map { it.task.condition.visitDate }.toSet()
        val date = generateSequence(LocalDate.now().plusDays(20)) { it.plusDays(1) }.first { it !in used }
        val base = record().task
        val task = base.copy(condition = base.condition.copy(visitDate = date), releaseAt = Instant.now().plusSeconds(2))
        val id = task.id
        graph.store.save(TaskRecord(task, TaskPhase.WAITING, note = "演示通知交接测试"))
        try {
            settings.edit().putBoolean("batteryAcknowledged", true).commit()
            graph.scheduler.schedule(task)
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline && manager.activeNotifications.none { it.tag == id && it.id == 3 }) {
                Thread.sleep(100)
            }
            assertEquals(OrderPhase.INSURANCE_PENDING, graph.store.get(task.id).order?.phase)
            assertTrue("Result must be posted by the actual task service", manager.activeNotifications.any { it.tag == id && it.id == 3 })
            // Longer than the physical test device's 2.926-second system Bell sound.
            Thread.sleep(5000)
            assertTrue("Result must survive service teardown", manager.activeNotifications.any { it.tag == id && it.id == 3 })
            assertFalse("Progress notification must be removed", manager.activeNotifications.any { it.id == 1 })
        } finally {
            graph.scheduler.cancel(task)
            settings.edit().putBoolean("batteryAcknowledged", previous).commit()
            manager.cancel(id, 3)
        }
    }
}
