package cn.guahao

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.hospital.*
import cn.guahao.storage.EncryptedVault
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.time.LocalDate
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.filters.SdkSuppress

/** Opt-in live queries. Credentials stay on device; UI checks never save or enable a task. */
@RunWith(AndroidJUnit4::class)
class LiveDoctorQueryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @SdkSuppress(minSdkVersion = 29)
    @Test fun editorDisplaysDoctorsWithoutSavingTask() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveHospitalProbe") == "true")
        val name = args.getString("departmentName") ?: error("Public department name required")
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.graph
        val before = graph.store.all()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("＋ 新建挂号任务").performClick()
            compose.onNodeWithText("真实挂号 · 使用本人服务号", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("就诊人（请核对）").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("否", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("科室：请选择科室").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("选择科室").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("搜索").performTextInput(name)
            compose.onNodeWithText("$name ·", substring = true).performScrollTo().performClick()
            args.getString("visitDate")?.let { raw ->
                val date = LocalDate.parse(raw)
                compose.onNodeWithText("就诊日期：", substring = true).performScrollTo().performClick()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fun find(view: View): DatePicker? {
                        if (view is DatePicker) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val root = WindowInspector.getGlobalWindowViews().single { find(it) != null }
                    find(root)!!.updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
                    root.findViewById<View>(android.R.id.button1).performClick()
                }
            }
            compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("重新查询当日排班").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("排班查询失败，请重试或重新连接医院").assertDoesNotExist()
            args.getString("expectedStatus")?.let { compose.onNodeWithText(DateAvailability.valueOf(it).label).assertExists() }
            val preselected = compose.onAllNodesWithText("预选目标医生").fetchSemanticsNodes().isNotEmpty()
            if (preselected)
                compose.onNodeWithText("预选目标医生").performScrollTo().performClick()
            assertTrue("Expected verified doctor options", compose.onAllNodesWithText(" · 选择 ○", substring = true).fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodesWithText(" · 选择 ○", substring = true)[0].performScrollTo().performClick()
            compose.onNodeWithText("下一步 · 执行设置").performScrollTo().assertIsEnabled().performClick()
            val expectedRelease = args.getString("expectedHospitalRelease")
            if (expectedRelease != null) {
                compose.onNodeWithText("医院放号时间：$expectedRelease（北京时间）").assertExists()
                compose.onNodeWithText("已自动采用医院放号时间").assertExists()
                compose.onNodeWithText("查号日期：${expectedRelease.substringBefore(' ')}").assertExists()
                compose.onNodeWithText("查号时刻：${expectedRelease.substringAfter(' ')}（北京时间）").assertExists()
                compose.onNodeWithText("查号时刻：${expectedRelease.substringAfter(' ')}（北京时间）").performScrollTo().performClick()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fun find(view: View): TimePicker? {
                        if (view is TimePicker) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val root = WindowInspector.getGlobalWindowViews().single { find(it) != null }
                    find(root)!!.apply { hour = 16; minute = 7 }
                    root.findViewById<View>(android.R.id.button1).performClick()
                }
                compose.onNodeWithText("查号时刻：16:07:00（北京时间）").assertExists()
                compose.onNodeWithText("医院放号时间：$expectedRelease（北京时间）").assertExists()
                compose.onNodeWithText("使用医院放号时间").performScrollTo().performClick()
                compose.onNodeWithText("查号时刻：${expectedRelease.substringAfter(' ')}（北京时间）").assertExists()
            } else if (compose.onAllNodesWithText("查号时刻：请选择（北京时间）").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("下一步 · 核对并启用").assertIsNotEnabled()
                compose.onNodeWithText("查号时刻：请选择（北京时间）").performScrollTo().performClick()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fun find(view: View): TimePicker? {
                        if (view is TimePicker) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val root = WindowInspector.getGlobalWindowViews().single { find(it) != null }
                    val time = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusMinutes(5)
                    find(root)!!.apply { hour = time.hour; minute = time.minute }
                    root.findViewById<View>(android.R.id.button1).performClick()
                }
            }
            compose.onNodeWithText("下一步 · 核对并启用").performScrollTo().performClick()
            args.getString("expectedStatus")?.let { compose.onNodeWithText("最近查询：${DateAvailability.valueOf(it).label}").assertExists() }
            compose.onNodeWithText(if (preselected) "目标日排班待确认" else "目标日医生排班已确认").assertExists()
            expectedRelease?.let { compose.onNodeWithText("医院放号时间：$it（北京时间）").assertExists() }
            compose.onNodeWithText("‹ 返回").performScrollTo().performClick()
            compose.onNodeWithText("‹ 返回").performScrollTo().performClick()
            assertEquals(1, compose.onAllNodesWithText(" · 已选择 ✓", substring = true).fetchSemanticsNodes().size)
            // MainActivity's existing foreground payment refresh updates lastEventAt.
            // Compare business data without dumping patient records into failure output.
            val after = graph.store.all()
            assertTrue("Doctor selection must preserve all task conditions, attempts and orders",
                before.associate { it.task.id to it.copy(lastEventAt = null) } ==
                    after.associate { it.task.id to it.copy(lastEventAt = null) })
        }
    }
    @Test fun selectedDepartmentLoadsDoctors() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveHospitalProbe") == "true")
        val name = args.getString("departmentName") ?: error("Public department name required")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val backing = EncryptedVault(context)
        val memory = mutableMapOf<String, String>()
        val overlay = object : SecretStore {
            override fun read(name: String) = memory[name] ?: backing.read(name)
            override fun write(name: String, text: String) { memory[name] = text }
        }
        val sessions = SessionRepository(overlay, Mutex())
        val session = sessions.current() ?: error("No saved session")
        val gateway = PscClient(sessions)
        val department = gateway.departments(session.reference).single { it.name == name }
        val condition = VisitCondition(session.reference, department, "pending", "待选医生",
            LocalDate.now().plusDays(7), 0, 1440, "2", 8000)
        try {
            val schedule = gateway.schedule(condition.scheduleQuery())
            val candidates = schedule.days.flatMap { it.candidates }
            instrumentation.sendStatus(0, Bundle().apply {
                putInt("candidate_count", candidates.size)
                putInt("doctor_count", candidates.map { it.doctorCode }.distinct().size)
                putBoolean("no_persistent_session_writes", true)
                for (raw in args.getString("probeDates", "2026-10-02,2026-10-10").split(",")) {
                    val day = schedule.day(LocalDate.parse(raw))
                    putString("date_$raw", "${day.status.name};doctors=${day.doctors.size};slots=${day.candidates.size};release=${day.hospitalReleaseAt}")
                }
            })
            assertTrue("Expected selectable doctors for the user-selected department", candidates.isNotEmpty())
        } catch (failure: Exception) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("failure_type", failure.javaClass.name)
                putString("application_stack", failure.stackTrace.filter { it.className.startsWith("cn.guahao") }
                    .take(8).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" })
            })
            fail("Doctor loading failed: ${failure.javaClass.simpleName}; see sanitized type/stack")
        }
    }
}
