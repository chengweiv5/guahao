package cn.guahao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.guahao.core.*
import cn.guahao.hospital.beijing.*
import cn.guahao.storage.EncryptedVault
import cn.guahao.storage.SecretStore
import cn.guahao.ui.BeijingPatientPicker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** All identities are synthetic. Storage is namespaced and never becomes a task connection. */
class BeijingPatientUiTest {
    @get:Rule val compose = createAndroidComposeRule<SchedulePreviewActivity>()
    private val route = HospitalRoute("synthetic-hospital", RegistrationChannel.JINGTONG)
    private fun account() = BeijingAccountSnapshot(route.channel, "synthetic-account", listOf(
        BeijingPatient("synthetic-patient", "测*", "synthetic-document", 1, 0, "wait_verify", false,
            listOf(BeijingPatientCard("synthetic-card", "****1234", 1, 3, "自费", true)))
    ))

    @Test fun explicitPatientAndCardAreRequiredAndEncryptedSelectionReopensUnverified() {
        val prefix = "beijing-ui-${UUID.randomUUID()}-"
        val store = object : SecretStore {
            val vault = EncryptedVault(compose.activity)
            override fun read(name: String) = vault.read(prefix + name)
            override fun write(name: String, text: String) = vault.write(prefix + name, text)
        }
        val source = BeijingAccountSource { account() }
        val repository = BeijingConnectionRepository(store, source)
        show(repository)
        compose.onNodeWithText("保存本次就诊人和卡").assertDoesNotExist()
        compose.onNodeWithText("核验登录与就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测*").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存本次就诊人和卡").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("测*").performScrollTo().performClick()
        compose.onNodeWithText("保存本次就诊人和卡").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("自费 · ****1234").performScrollTo().performClick()
        compose.onNodeWithText("保存本次就诊人和卡").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("已保存：测* · 自费 · ****1234").performScrollTo().assertIsDisplayed()
        val saved = repository.saved(route)!!
        assertTrue(repository.isCurrent(saved))
        val reopened = BeijingConnectionRepository(store, source)
        assertEquals(saved.id, reopened.saved(route)!!.id)
        assertFalse(reopened.isCurrent(saved))
        val bytes = java.io.File(compose.activity.noBackupFilesDir, prefix + "beijing-patient-selections").readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("synthetic"))
        compose.onNodeWithText("synthetic-document").assertDoesNotExist()
        compose.onNodeWithText("synthetic-card").assertDoesNotExist()
    }

    @Test fun failedRefreshRemovesOldChoicesAndRequiresExplicitSelectionAgain() {
        var offline = false
        val store = object : SecretStore {
            private val values = mutableMapOf<String, String>()
            override fun read(name: String) = values[name]
            override fun write(name: String, text: String) { values[name] = text }
        }
        val repository = BeijingConnectionRepository(store, BeijingAccountSource {
            if (offline) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
            account()
        })
        show(repository)
        compose.onNodeWithText("核验登录与就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测*").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测*").performScrollTo().performClick()
        compose.onNodeWithText("自费 · ****1234").performScrollTo().performClick()
        offline = true
        compose.onNodeWithText("核验登录与就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("此渠道需要重新登录，请在原渠道核对").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存本次就诊人和卡").assertDoesNotExist()
        assertNull(repository.saved(route))
        offline = false
        compose.onNodeWithText("核验登录与就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测*").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存本次就诊人和卡").performScrollTo().assertIsNotEnabled()
    }

    private fun show(repository: BeijingConnectionRepository) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    BeijingPatientPicker(route, "测试医院（虚构）", repository)
                }
            }
        }
    }
}
