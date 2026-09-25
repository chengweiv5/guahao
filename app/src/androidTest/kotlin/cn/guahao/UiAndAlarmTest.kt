package cn.guahao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TimePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import cn.guahao.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiAndAlarmTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @SdkSuppress(minSdkVersion = 29)
    @Test fun demoReleaseTimeKeepsCustomHourAndLabelsShortcutAsAction() {
        val beforeIds = compose.activity.graph.store.all().map { it.task.id }.toSet()
        val zone = ZoneId.of("Asia/Shanghai")
        val selected = Instant.now().plusSeconds(3600).atZone(zone).withSecond(0).withNano(0)
        compose.onNodeWithText("＋ 新建挂号任务").performClick()
        compose.onNodeWithText("否", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("林医生（虚构） · 选择 ○").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("林医生（虚构） · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 执行设置").performScrollTo().performClick()
        compose.onNodeWithText("放号日期：", substring = true).performScrollTo().performClick()
        compose.runOnUiThread {
            val root = WindowInspector.getGlobalWindowViews().single { findView<android.widget.DatePicker>(it) != null }
            findView<android.widget.DatePicker>(root)!!.updateDate(selected.year, selected.monthValue - 1, selected.dayOfMonth)
            root.findViewById<View>(android.R.id.button1).performClick()
        }
        compose.onNodeWithText("放号时刻：", substring = true).performScrollTo().performClick()
        compose.runOnUiThread {
            val root = WindowInspector.getGlobalWindowViews().single { findView<TimePicker>(it) != null }
            findView<TimePicker>(root)!!.apply { hour = selected.hour; minute = selected.minute }
            root.findViewById<View>(android.R.id.button1).performClick()
        }
        val selectedTime = selected.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        compose.onNodeWithText("放号时刻：$selectedTime（北京时间）").assertExists()
        val shortcutIsAction = compose.onAllNodesWithText("快捷设置为 1 分钟后").fetchSemanticsNodes().size == 1
        if (shortcutIsAction) {
            compose.onNodeWithText("快捷设置为 1 分钟后").performScrollTo().assertHasClickAction()
            capturePreview("ui-demo-release-settings.png")
        }
        compose.onNodeWithText("下一步 · 核对并启用").performScrollTo().performClick()
        compose.onNodeWithText("先保存草稿").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.graph.store.all().any { it.task.id !in beforeIds } }
        val saved = compose.activity.graph.store.all().single { it.task.id !in beforeIds }
        assertTrue(saved.task.demo)
        assertEquals(TaskPhase.DRAFT, saved.phase)
        assertEquals("Demo must preserve the selected release time", selected.toInstant(), saved.task.releaseAt)
        assertNull(saved.attempt)
        assertTrue("Label the one-minute shortcut as an action, not the current release time", shortcutIsAction)
    }

    @Test fun selectedHospitalBIsFrozenInDraft() {
        val before = compose.activity.graph.store.all().map { it.task.id }.toSet()
        compose.onNodeWithText("＋ 新建挂号任务").performClick()
        compose.onNodeWithText("演示医院 B", useUnmergedTree = true).performScrollTo().performClick()
        capturePreview("ui-parallel-hospital-picker.png")
        compose.onNodeWithText("否", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("林医生（虚构） · 选择 ○").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("林医生（虚构） · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 执行设置").performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 核对并启用").performScrollTo().performClick()
        compose.onNodeWithText("先保存草稿").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.graph.store.all().any { it.task.id !in before } }
        val saved = compose.activity.graph.store.all().single { it.task.id !in before }
        assertEquals("demo-b", saved.task.binding!!.hospitalId)
        assertEquals(cn.guahao.hospital.DemoGateway.patientB, saved.task.condition.patient)
        assertNull(saved.attempt)
        compose.waitUntil(5000) { compose.onAllNodesWithText("演示医院 B · 本机演示").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("任务详情").performScrollTo()
        capturePreview("ui-parallel-task-detail.png")
    }

    private inline fun <reified T : View> findView(root: View): T? {
        if (root is T) return root
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is T) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
        }
        return null
    }

    @Test fun createSixtyMinuteDraftFromVisibleUi() {
        compose.onNodeWithText("＋ 新建挂号任务").performClick()
        compose.onNodeWithText("否",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("林医生（虚构） · 选择 ○").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("林医生（虚构） · 选择 ○").performScrollTo().performClick()
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
        capturePreview("ui-task-detail.png")
    }

    private fun capturePreview(name: String) {
        compose.waitForIdle()
        // Render our own synthetic Activity into a bitmap, retaining the secure window flag.
        compose.runOnUiThread {
            val view=compose.activity.window.decorView
            val screenshot=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(screenshot))
            val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: compose.activity.getExternalFilesDir(null)!!
            output.mkdirs()
            File(output,name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
