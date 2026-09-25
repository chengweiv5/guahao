package cn.guahao

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith

/** Emulator only: reaches the actual import button and error dialog, without a hospital request. */
@RunWith(AndroidJUnit4::class)
class SessionImportErrorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeLinkWithoutCredentialExplainsHowToGetBookingLink() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("连接医院").performScrollTo().performClick()
        compose.onNodeWithText("粘贴服务号页面链接").performScrollTo().performTextInput(
            "https://psc.hkinfo.net/admin/youmanage?ptno=test-patient&userId=test-user")
        compose.onNodeWithText("连接并核验就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("链接缺少医院登录凭据，请在微信进入「预约挂号」后重新复制完整链接").assertIsDisplayed()
        compose.onNodeWithText("链接缺少就诊人身份信息", substring = true).assertDoesNotExist()
    }

    @Test fun invalidPasteShowsActionableLinkErrorInsteadOfGenericFailure() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("连接医院").performScrollTo().performClick()
        compose.onNodeWithText("粘贴服务号页面链接").performScrollTo().performTextInput("not-a-hospital-link")
        compose.onNodeWithText("连接并核验就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Observed the user's exact generic failure instead of a link validation message",
            compose.onAllNodesWithText("操作未完成，请检查输入、医院连接或网络后重试").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("请导入有效的佑安服务号 HTTPS 页面链接", substring = true).assertIsDisplayed()
    }
}
