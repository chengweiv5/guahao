package cn.guahao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.guahao.core.PatientRef
import cn.guahao.core.psc.PscSession
import cn.guahao.hospital.*
import cn.guahao.storage.*
import cn.guahao.ui.SessionScreen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import cn.guahao.core.psc.pscJson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import cn.guahao.core.*
import java.time.Instant
import java.time.LocalDate

/** Pure synthetic UI and namespaced encrypted storage; never installs a hospital session. */
@RunWith(AndroidJUnit4::class)
class ConnectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val patient = PscSession(PatientRef("test-session", "test-patient"), "test-user", "test-key",
        "test-patient", "test-patient-key", "测试就诊人（虚构）")

    @Test fun savedAndOfflineSessionOfferRetryWithoutAskingForAnotherPaste() {
        val state = mutableStateOf(HospitalConnection(patient))
        var checks = 0
        show(state, onCheck = { checks++ })
        compose.onNodeWithText("会话已保存，待校验").assertExists()
        compose.onNodeWithText("粘贴服务号页面链接").assertDoesNotExist()
        compose.onNodeWithText("重新校验连接").performScrollTo().performClick()
        assertEquals(1, checks)
        compose.runOnIdle { state.value = HospitalConnection(patient, ConnectionHealth(ConnectionStatus.CHECK_FAILED)) }
        compose.onNodeWithText("暂未确认连接状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("粘贴服务号页面链接").assertDoesNotExist()
        compose.onNodeWithText("重新导入 / 更换就诊人").performScrollTo().performClick()
        compose.onNodeWithText("粘贴服务号页面链接").assertExists()
    }

    @Test fun expiredSessionShowsReconnectAndSuccessfulImportCollapsesTheForm() {
        val state = mutableStateOf(HospitalConnection(patient, ConnectionHealth(ConnectionStatus.RECONNECT_REQUIRED)))
        var imports = 0
        show(state, onImport = { imports++ })
        compose.onNodeWithText("需重新连接").assertExists()
        compose.onNodeWithText("重新校验连接").assertDoesNotExist()
        compose.onNodeWithText("粘贴服务号页面链接").performScrollTo().performTextInput("synthetic-link-only")
        compose.onNodeWithText("连接并核验就诊人").performScrollTo().performClick()
        assertEquals(1, imports)
        compose.runOnIdle { state.value = HospitalConnection(patient,
            ConnectionHealth(ConnectionStatus.VERIFIED, "2026-09-25T03:00:00Z", "2026-09-25T03:00:00Z")) }
        compose.onNodeWithText("最近校验可用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("粘贴服务号页面链接").assertDoesNotExist()
        compose.onNodeWithText("上次校验通过：", substring = true).assertExists()
        capture("connection-verified.png")
        compose.runOnIdle { state.value = HospitalConnection(patient, ConnectionHealth(ConnectionStatus.RECONNECT_REQUIRED)) }
        compose.onNodeWithText("需重新连接").performScrollTo()
        capture("connection-reconnect.png")
    }

    @Test fun reconnectStatusSurvivesEncryptedStorageReopen() = runBlocking {
        val prefix = "connection-test-${UUID.randomUUID()}-"
        fun secrets() = object : SecretStore {
            private val vault = EncryptedVault(compose.activity)
            override fun read(name: String) = vault.read(prefix + name)
            override fun write(name: String, text: String) = vault.write(prefix + name, text)
        }
        val store = secrets()
        store.write("session-test-session", pscJson.encodeToString(patient))
        store.write("current-session", "test-session")
        val repo = SessionRepository(store, Mutex())
        repo.recordFailure(patient.reference, cn.guahao.core.HospitalException("test", reconnectRequired = true))
        val reopened = SessionRepository(secrets(), Mutex())
        assertTrue(reopened.connection().needsReconnect)
        assertEquals(patient.reference, reopened.current()!!.reference)
        try { reopened.load(patient.reference); fail("Expired credentials accepted") }
        catch (e: cn.guahao.core.HospitalException) { assertTrue(e.reconnectRequired) }
    }

    @Test fun priorConnectionDraftCannotEnableAndUnresolvedTaskBlocksReimport() = runBlocking {
        val isolated = File(compose.activity.cacheDir, "connection-test-${UUID.randomUUID()}").apply { mkdirs() }
        val testContext = object : ContextWrapper(compose.activity) {
            override fun getNoBackupFilesDir() = File(isolated, "vault").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(isolated, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val graph = AppGraph(testContext)
        val condition = VisitCondition(patient.reference, DemoGateway.department, "test-doctor", "测试医生（虚构）",
            LocalDate.now().plusDays(7), 0, 1440, "2", 8000)
        val task = BookingTask("connection-test-${UUID.randomUUID()}", condition, Instant.now().plusSeconds(3600), demo = false)
        graph.store.save(TaskRecord(task))
        try {
            try { graph.enable(task.id); fail("Old session draft enabled") }
            catch (e: IllegalStateException) { assertTrue(e.message, e.message!!.contains("医院连接已更新")) }
            assertEquals(TaskPhase.DRAFT, graph.store.get(task.id).phase)
            assertNull(graph.store.get(task.id).attempt)
            val candidate = Candidate(condition.department, condition.doctorCode, condition.doctorName, condition.visitDate,
                "1", "09:00-09:30", 540, 570, 5000, 1, "2", false)
            graph.store.update(task.id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION,
                attempt = SubmissionAttempt("synthetic", task.id, candidate, Instant.now().minusSeconds(180), emptySet())) }
            try { graph.importSession("not-a-real-link"); fail("Unresolved task allowed reimport") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("未决提交")) }
            assertNotNull(graph.store.get(task.id).attempt)
        } finally {
            graph.store.update(task.id) { it.copy(phase = TaskPhase.STOPPED, manuallyResolved = true, stopRequested = true,
                note = "连接保护测试已结束（虚构），未请求医院") }
            graph.store.close()
        }
    }

    private fun show(state: MutableState<HospitalConnection>, onCheck: () -> Unit = {}, onImport: (String) -> Unit = {}) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B5B), onSurface = Color(0xFF17342F))) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SessionScreen(state.value, false, onCheck, onImport, {})
                    }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
