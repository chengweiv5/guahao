package cn.guahao

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.guahao.core.*
import cn.guahao.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.Instant
import java.io.File

/** Synthetic query results only; never saves or enables a registration task. */
class DailyScheduleUiTest {
    @get:Rule val compose = createAndroidComposeRule<SchedulePreviewActivity>()
    private val dept = DepartmentRef("test", "eye", "五官科", "眼科", "")
    private val doctor = DoctorRef("test-doctor", "测试医生", "主任医师")
    private val initialQuery = ScheduleQuery(PatientRef("test", "test"), dept, LocalDate.parse("2026-10-10"), "2")
    private var selection: DoctorSelection? = null
    private fun schedule(query: ScheduleQuery, status: DateAvailability, published: Boolean = false) = DepartmentSchedule(dept, listOf(
        ScheduleDay(query.visitDate, status, if (published) listOf(doctor) else emptyList(), emptyList()),
        ScheduleDay(query.visitDate.minusDays(1), DateAvailability.AVAILABLE, listOf(doctor), emptyList())))

    private fun show(loader: suspend (ScheduleQuery) -> DepartmentSchedule) {
        compose.setContent {
            MaterialTheme {
                var query by remember { mutableStateOf(initialQuery) }
                var chosen by remember { mutableStateOf<DoctorSelection?>(null) }
                var next by remember { mutableStateOf(false) }
                val state = remember(query) { SchedulePickerState() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { query = query.copy(visitDate = query.visitDate.plusDays(1)); chosen = null; selection = null }) { Text("更换日期") }
                    if (!next) key(query) { DailySchedulePicker(query, chosen, loader, state) { chosen = it; selection = it } }
                    else ScheduleSummary(chosen?.observation)
                    Button(onClick = { next = !next }, enabled = chosen?.query == query) { Text(if (next) "返回条件" else "下一步") }
                }
            }
        }
    }
    private fun query() {
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("重新查询当日排班").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun preview(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            // This Activity only contains synthetic data supplied by show().
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            bitmap.recycle()
        }
    }
    @Test fun unreleasedPreselectionPreservesStatusAndClearsOnDateChange() {
        show { schedule(it, DateAvailability.NOT_RELEASED) }
        query()
        compose.onNodeWithText("尚未放号").assertExists()
        compose.onNodeWithText("当日无号").assertDoesNotExist()
        compose.onNodeWithText("预选目标医生").performScrollTo().performClick()
        preview("daily-unreleased.jpeg")
        compose.onNodeWithText("测试医生 · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("下一步").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("最近查询：尚未放号").assertExists()
        compose.onNodeWithText("目标日排班待确认").assertExists()
        assertFalse(selection!!.observation.doctorConfirmed)
        compose.onNodeWithText("返回条件").performClick()
        compose.onNodeWithText("测试医生 · 已选择 ✓").assertExists()
        compose.onNodeWithText("更换日期").performScrollTo().performClick()
        compose.onNodeWithText("下一步").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("测试医生 · 已选择 ✓").assertDoesNotExist()
    }
    @Test fun noStockKeepsDistinctTitleAfterPreselection() {
        show { schedule(it, DateAvailability.NO_STOCK) }; query()
        compose.onNodeWithText("当日无号").assertExists()
        preview("daily-no-stock.jpeg")
        compose.onNodeWithText("尚未放号").assertDoesNotExist()
        compose.onNodeWithText("预选目标医生").performScrollTo().performClick()
        compose.onNodeWithText("测试医生 · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("当日无号").assertExists()
        assertEquals(DateAvailability.NO_STOCK, selection!!.observation.availability)
    }
    @Test fun publishedScheduleCanBeUnreleasedAndQueryErrorsAreNotNoStock() {
        var fail = false
        show { if (fail) throw HospitalException("网络不可用，请重试") else schedule(it, DateAvailability.NOT_RELEASED, true) }
        query()
        compose.onNodeWithText("当日排班 · 选择医生").assertExists()
        compose.onNodeWithText("测试医生 · 选择 ○").performScrollTo().performClick()
        assertTrue(selection!!.observation.doctorConfirmed)
        assertEquals(DateAvailability.NOT_RELEASED, selection!!.observation.availability)
        fail = true
        compose.onNodeWithText("重新查询当日排班").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("网络不可用，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("当日无号").assertDoesNotExist()
        compose.onNodeWithText("下一步").performScrollTo().assertIsNotEnabled()
    }
    @Test fun oldQueryCannotPopulateChangedDate() {
        val deferred = CompletableDeferred<DepartmentSchedule>()
        show { deferred.await() }
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.onNodeWithText("更换日期").performScrollTo().performClick()
        deferred.complete(schedule(initialQuery, DateAvailability.NO_STOCK))
        compose.waitForIdle()
        compose.onNodeWithText("当日无号").assertDoesNotExist()
        compose.onNodeWithText("查询当日排班").assertIsEnabled()
        compose.onNodeWithText("下一步").performScrollTo().assertIsNotEnabled()
    }
    @Test fun hospitalReleaseTimeStaysWithTargetDateAndConfirmation() {
        val release = Instant.parse("2026-09-26T07:00:00Z")
        val expected = "医院放号时间：2026-09-26 15:00:00（北京时间）"
        show { q -> schedule(q, DateAvailability.NOT_RELEASED).let { s -> s.copy(days = s.days.map {
            if (it.date == q.visitDate) it.copy(hospitalReleaseAt = release) else it
        }) } }
        query()
        compose.onNodeWithText(expected).assertExists()
        preview("hospital-release-time.jpeg")
        compose.onNodeWithText("预选目标医生").performScrollTo().performClick()
        compose.onNodeWithText("测试医生 · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("下一步").performScrollTo().performClick()
        compose.onNodeWithText(expected).assertExists()
        compose.onNodeWithText("返回条件").performClick()
        compose.onNodeWithText("更换日期").performScrollTo().performClick()
        compose.onNodeWithText(expected).assertDoesNotExist()
    }
}
