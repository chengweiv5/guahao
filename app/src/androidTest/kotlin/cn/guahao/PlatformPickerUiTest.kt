package cn.guahao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.guahao.core.RegistrationChannel
import cn.guahao.hospital.beijing.*
import cn.guahao.ui.HospitalChannelPicker
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlatformPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<SchedulePreviewActivity>()
    private var selected: BeijingHospital? = null
    private val hospital = BeijingHospital("synthetic-hospital", "测试医院（虚构）", "测试级别", null)

    private fun show(load: suspend (RegistrationChannel, Int) -> BeijingHospitalPage) {
        compose.setContent {
            MaterialTheme {
                var channel by remember { mutableStateOf(RegistrationChannel.BEIJING_114) }
                var selection by remember { mutableStateOf<BeijingHospital?>(null) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    HospitalChannelPicker(channel, selection, load, { channel = it }, { selection = it; selected = it })
                }
            }
        }
    }

    @Test fun challengeIsVisibleAndRetryCanRecoverWithoutInventingHospitals() {
        var fail = true
        show { _, _ ->
            if (fail) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
            BeijingHospitalPage(listOf(hospital), 1)
        }
        compose.onNodeWithText("查询医院目录").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("请先打开此渠道的官方连接页，完成登录或校验后重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(hospital.name).assertDoesNotExist()
        fail = false
        compose.onNodeWithText("查询医院目录").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(hospital.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(hospital.name).performScrollTo().performClick()
        assertEquals(hospital, selected)
        compose.onNodeWithText("京通").performScrollTo().performClick()
        assertNull(selected)
        compose.onNodeWithText(hospital.name).assertDoesNotExist()
    }

    @Test fun late114DirectoryCannotPopulateJingtong() {
        val pending = CompletableDeferred<BeijingHospitalPage>()
        show { _, _ -> pending.await() }
        compose.onNodeWithText("查询医院目录").performScrollTo().performClick()
        compose.onNodeWithText("京通").performScrollTo().performClick()
        pending.complete(BeijingHospitalPage(listOf(hospital), 1))
        compose.waitForIdle()
        compose.onNodeWithText(hospital.name).assertDoesNotExist()
        compose.onNodeWithText("查询医院目录").performScrollTo().assertIsEnabled()
    }
}
