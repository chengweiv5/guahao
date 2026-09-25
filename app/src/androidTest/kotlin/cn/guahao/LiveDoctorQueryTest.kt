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

/** Opt-in live queries. Credentials stay on device; UI checks never save or enable a task. */
@RunWith(AndroidJUnit4::class)
class LiveDoctorQueryTest {
    @get:Rule val compose = createEmptyComposeRule()

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
            compose.onNodeWithText("医生：请选择医生").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("选择医生").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("医生加载失败，请刷新科室或重新连接").assertDoesNotExist()
            compose.onNodeWithText("该科室暂未返回可核验的医生排班，请稍后刷新。").assertDoesNotExist()
            assertTrue("Expected real doctor options", compose.onAllNodesWithText(" · ¥", substring = true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("关闭").performClick()
            assertEquals("Doctor selection must not create or modify task records", before, graph.store.all())
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
            val candidates = gateway.candidates(condition)
            instrumentation.sendStatus(0, Bundle().apply {
                putInt("candidate_count", candidates.size)
                putInt("doctor_count", candidates.map { it.doctorCode }.distinct().size)
                putBoolean("no_persistent_session_writes", true)
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
