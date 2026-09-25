package cn.guahao

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
