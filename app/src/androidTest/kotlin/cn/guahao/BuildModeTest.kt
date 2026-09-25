package cn.guahao

import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.hospital.DemoGateway
import cn.guahao.runtime.TaskReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Run the same test APK against debug and a locally test-signed release APK. */
@RunWith(AndroidJUnit4::class)
class BuildModeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val expectedDemo get() = InstrumentationRegistry.getArguments().getString("expectedDemo", "true").toBooleanStrict()

    @Test fun installedVariantControlsEditorAndHistory() {
        val graph = compose.activity.graph
        assertEquals(expectedDemo, graph.mode.demoEnabled)
        val task = syntheticTask()
        graph.store.save(TaskRecord(task, note = "构建隔离界面测试"))
        if (expectedDemo) assertTrue(graph.visibleRecords().any { it.task.id == task.id })
        else assertTrue(graph.visibleRecords().none { it.task.id == task.id })
        compose.onNodeWithText("记录", useUnmergedTree = true).performClick()
        if (!expectedDemo) compose.onAllNodesWithText("演示", substring = true).assertCountEquals(0)
        compose.onNodeWithText("任务", useUnmergedTree = true).performClick()
        compose.onNodeWithText("＋ 新建挂号任务").performClick()
        if (expectedDemo) compose.onNodeWithText("演示模式 · 不连接医院").assertExists()
        else {
            compose.onAllNodesWithText("演示", substring = true).assertCountEquals(0)
            compose.onNodeWithText("下一步 · 就诊条件").assertIsDisplayed().performClick()
            compose.onNodeWithText("先连接医院").assertExists()
            compose.onNodeWithText("科室：请选择科室").assertExists()
            compose.onNodeWithText("查询当日排班").assertExists()
            compose.onNodeWithText("下一步 · 执行设置").assertIsDisplayed().assertIsNotEnabled()
        }
        compose.onNodeWithText("新建挂号任务").assertIsDisplayed()
        capture("build-mode-${if (expectedDemo) "debug" else "release"}.png")
    }

    @Test fun releaseRejectsDemoExecutionAndRetainsStoredData() = runBlocking {
        val context = compose.activity
        val graph = context.graph
        assertEquals(expectedDemo, graph.mode.demoEnabled)
        if (expectedDemo) {
            assertTrue(graph.gateway.departments(DemoGateway.patient).isNotEmpty())
            return@runBlocking
        }
        val task = syntheticTask()
        // Simulate a demo task retained by an upgrade, without creating a live booking.
        val original = TaskRecord(task.copy(binding = cn.guahao.hospital.demoBinding(task.condition.patient)),
            TaskPhase.WAITING, note = "旧演示任务隔离测试")
        graph.store.save(original)
        expectRejected { graph.saveDraft(syntheticTask()) }
        expectRejected { graph.enable(task.id) }
        expectRejected { graph.scheduler.schedule(task) }
        expectRejected { graph.gateway.departments(DemoGateway.patient) }
        expectRejected { graph.preparePayment(task.id) }
        expectRejected { graph.refreshPayment(task.id) }
        assertFalse(graph.hasUnresolvedOrActive())
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationId = task.id.hashCode()
        val alarmIntent = Intent(context, TaskReceiver::class.java).setAction("cn.guahao.RUN")
            .setData(Uri.parse("guahao://task/${task.id}/${task.generation}"))
            .putExtra("taskId", task.id).putExtra("generation", task.generation)
        fun pending(flags: Int) = PendingIntent.getBroadcast(context, 0, alarmIntent, flags or PendingIntent.FLAG_IMMUTABLE)
        val oldPending = pending(PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, task.releaseAt.toEpochMilli(), oldPending)
        try {
            val silent = Notification.Builder(context, "execution").setSmallIcon(R.drawable.ic_booking)
                .setContentTitle("构建隔离测试").setGroup("guahao.test.silent")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).build()
            manager.notify(notificationId, silent)
            compose.waitUntil(3000) { manager.activeNotifications.any { it.id == notificationId } }
            graph.recover()
            assertNull("Recovery must cancel the old alarm token", pending(PendingIntent.FLAG_NO_CREATE))
            compose.waitUntil(3000) { manager.activeNotifications.none { it.id == notificationId } }
            graph.notifications.result(original)
            assertTrue(manager.activeNotifications.none { it.id == notificationId })
            pending(PendingIntent.FLAG_UPDATE_CURRENT).send()
            Thread.sleep(1500)
            assertEquals("Recovery and stale alarm must not mutate or execute the demo", original, graph.store.get(task.id))
            assertTrue(graph.visibleRecords().none { it.task.id == task.id })
            assertTrue(manager.activeNotifications.none { it.id == notificationId || it.id == 1 })
        } finally {
            graph.scheduler.cancel(task)
            manager.cancel(notificationId)
            graph.store.update(task.id) { it.copy(phase = TaskPhase.STOPPED, stopRequested = true, note = "构建隔离测试已结束") }
        }
    }

    private suspend fun expectRejected(action: suspend () -> Unit) {
        try { action(); fail("Release accepted a demo operation") } catch (_: IllegalStateException) { }
    }

    private fun syntheticTask(): BookingTask {
        val condition = VisitCondition(DemoGateway.patient, DemoGateway.department, "demo-doctor", "林医生（虚构）",
            LocalDate.now().plusDays(40), 0, 1440, "2", 8000)
        return BookingTask(UUID.randomUUID().toString(), condition, Instant.now().plusSeconds(3600))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
