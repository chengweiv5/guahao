package cn.guahao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cn.guahao.core.*
import cn.guahao.hospital.DemoGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiAndAlarmTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createSixtyMinuteDraftFromVisibleUi() {
        compose.onNodeWithText("＋ 新建挂号任务").performClick()
        compose.onNodeWithText("否",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 执行设置").performScrollTo().performClick()
        compose.onNodeWithText("最长运行时长（分钟）").performScrollTo().performTextReplacement("60")
        compose.onNodeWithText("下一步 · 核对并启用").performScrollTo().performClick()
        compose.onNodeWithText("60 分钟").assertExists()
        compose.onNodeWithText("先保存草稿").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("确认开启自动挂号").fetchSemanticsNodes().isNotEmpty() }
        val task = compose.activity.graph.store.all().first { it.phase==TaskPhase.DRAFT }
        assertEquals(60,task.task.maxRuntimeMinutes)
        assertEquals(3600,Duration.between(task.task.releaseAt,task.task.deadline).seconds)
        compose.onNodeWithText("任务详情").performScrollTo()
        compose.waitForIdle()
        // Render our own synthetic Activity into a bitmap, retaining the secure window flag.
        compose.runOnUiThread {
            val view=compose.activity.window.decorView
            val screenshot=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(screenshot))
            val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: compose.activity.getExternalFilesDir(null)!!
            output.mkdirs()
            File(output,"ui-task-detail.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun exactAlarmStartsServiceWithScreenOff() = runBlocking {
        val graph=compose.activity.graph
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settings=compose.activity.getSharedPreferences("settings",Context.MODE_PRIVATE)
        val previous=settings.getBoolean("batteryAcknowledged",false)
        settings.edit().putBoolean("batteryAcknowledged",true).commit()
        val release=Instant.now().plusSeconds(10)
        val c=VisitCondition(DemoGateway.patient,DemoGateway.department,"demo-doctor","林医生（虚构）",LocalDate.now().plusDays(9),0,1440,"2",8000)
        val task=BookingTask(UUID.randomUUID().toString(),c,release,maxRuntimeMinutes=1)
        graph.store.save(TaskRecord(task,TaskPhase.WAITING,note="演示定时设备测试"))
        try {
            graph.scheduler.schedule(task)
            device.sleep()
            val end=System.currentTimeMillis()+45000
            while (System.currentTimeMillis()<end && graph.store.get(task.id).order?.phase!=OrderPhase.INSURANCE_PENDING) kotlinx.coroutines.delay(250)
            val result=graph.store.get(task.id)
            assertNotNull(result.attempt)
            assertEquals(OrderPhase.INSURANCE_PENDING,result.order?.phase)
            assertFalse(result.attempt!!.sentAt.isBefore(release))
            val delay=Duration.between(release,result.attempt!!.sentAt).toMillis()
            val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: compose.activity.getExternalFilesDir(null)!!
            output.mkdirs()
            val evidence="release=$release\nattempt=${result.attempt!!.sentAt}\ndelayMillis=$delay\nphase=${result.phase}\norder=${result.order!!.phase}\nscreenOn=${device.isScreenOn}\n"
            File(output,"alarm-evidence.txt").writeText(evidence)
            println("GUAHAO_ALARM_EVIDENCE $evidence")
            assertFalse(device.isScreenOn)
        } finally {
            device.wakeUp(); graph.scheduler.cancel(task)
            settings.edit().putBoolean("batteryAcknowledged",previous).commit()
        }
    }
}
