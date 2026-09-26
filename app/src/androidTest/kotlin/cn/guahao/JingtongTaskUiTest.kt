package cn.guahao

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TimePicker
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.guahao.core.*
import cn.guahao.hospital.HospitalConnection
import cn.guahao.hospital.beijing.*
import cn.guahao.ui.TaskEditorScreen
import cn.guahao.ui.Teal
import cn.guahao.ui.Ink
import cn.guahao.ui.Warm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Synthetic platform only: no hospital network, credentials or real order creation. */
class JingtongTaskUiTest {
    @get:Rule val compose = createAndroidComposeRule<SchedulePreviewActivity>()
    private val channel = RegistrationChannel.JINGTONG
    private val hospitalId = "synthetic-ui-${UUID.randomUUID()}"
    private val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7)
    private val dateCode = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    private val card = BeijingPatientCard("synthetic-card", "****5678", 1, 3, "自费", true)
    private val person = BeijingPatient("synthetic-patient", "测*", "synthetic-id", 1, 0, "wait_verify", false, listOf(card))
    private val source = object : BeijingPlatformSource {
        override suspend fun loadAccount(channel: RegistrationChannel) = BeijingAccountSnapshot(channel, "synthetic-account", listOf(person), true)
        override suspend fun execute(request: BeijingQueryRequest): BeijingQueryReply {
            assertEquals(channel, request.channel)
            val data = when (request.path) {
                "hospital/list" -> """{"list":[{"code":"$hospitalId","name":"测试医院（虚构）"}],"count":1}"""
                "department/list" -> """[{"code":"parent","name":"内科","subList":[{"code":"child","name":"测试科室","parentCode":"parent"}]}]"""
                "product/calendar" -> """{"calendars":[{"dutyDate":"$dateCode","status":1,"statusView":"有号"}]}"""
                "product/doctor/detail" -> """{"doctors":[{"doctorCode":"doctor","doctorName":"测试医生","firstDeptCode":"parent","secondDeptCode":"child","titleView":"主任医师","detail":[{"dutyCode":1,"dutyCodeView":"上午","fcode":"50.00","ncode":"3","productStatus":1,"productStatusView":"有号","uniqueProductKey":"synthetic-product","period":[{"create":false,"dutyTime":"${dateCode}0800-${dateCode}0830","dutyTimeView":"08:00-08:30","ncode":"3","uniqProductKey":"synthetic-time"}]}]}]}"""
                else -> error("Unexpected query")
            }
            return BeijingQueryReply(200, "application/json", """{"code":"0000","data":$data}""")
        }
        override suspend fun orders(selection: BeijingPatientSelection, from: LocalDate, to: LocalDate): List<OrderSnapshot> = error("No order query expected")
        override suspend fun submit(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate, maySend: () -> Boolean): LockReply = error("No submission allowed")
        override suspend fun receipt(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate): AsyncReply = error("No receipt query expected")
    }

    @Test fun fourStepsSaveJingtongDraftWithFixedHospitalPatientCardAndNoEnable() {
        val graph = AppGraph(compose.activity, AppMode(false), source)
        var saved: BookingTask? = null
        show(graph, null) { task, enable -> assertFalse(enable); graph.saveDraft(task); saved = task }
        compose.onNodeWithText("京通").performScrollTo().performClick()
        compose.onNodeWithText("查询医院目录").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试医院（虚构）").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试医院（虚构）").performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 就诊条件").performClick()
        compose.onNodeWithText("先连接医院").assertDoesNotExist()
        compose.onNodeWithText("是否专程来京就医").assertDoesNotExist()
        compose.onNodeWithText("下一步 · 执行设置").assertIsNotEnabled()
        compose.onNodeWithText("核验登录与就诊人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测*").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测*").performScrollTo().performClick()
        compose.onNodeWithText("自费 · ****5678").performScrollTo().performClick()
        compose.onNodeWithText("保存本次就诊人和卡").performScrollTo().performClick()
        capture("jingtong-patient.png")
        compose.onNodeWithText("科室：请选择科室").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试科室 · 内科").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试科室 · 内科").performClick()
        compose.onNodeWithText("查询当日排班").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("测试医生 · 选择 ○").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试医生 · 选择 ○").performScrollTo().performClick()
        compose.onNodeWithText("下一步 · 执行设置").assertIsEnabled().performClick()
        capture("jingtong-settings.png")
        compose.onNodeWithText("优先医保移动支付").assertDoesNotExist()
        compose.onNodeWithText("查号时刻：", substring = true).performScrollTo().performClick()
        compose.runOnUiThread {
            val root = WindowInspector.getGlobalWindowViews().single { findTimePicker(it) != null }
            val selected = Instant.now().plusSeconds(120).atZone(ZoneId.of("Asia/Shanghai"))
            findTimePicker(root)!!.apply { hour = selected.hour; minute = selected.minute }
            root.findViewById<View>(android.R.id.button1).performClick()
        }
        compose.onNodeWithText("下一步 · 核对并启用").assertIsEnabled().performClick()
        compose.onNodeWithText("确认开启自动挂号").assertIsNotEnabled()
        capture("jingtong-review.png")
        compose.onNodeWithText("先保存草稿").assertIsEnabled().performClick()
        compose.waitUntil(5000) { saved != null }
        val task = saved!!
        assertEquals(hospitalId, task.binding!!.hospitalId)
        assertEquals(channel.id, task.binding!!.providerId)
        assertEquals("synthetic-card", graph.beijingConnections.load(task.condition.patient).card.number)
        assertEquals(PaymentPreference.FULL_AMOUNT_BY_USER, task.paymentPreference)
        assertEquals(TaskPhase.DRAFT, graph.store.get(task.id).phase)
        assertNull(graph.store.get(task.id).attempt)
    }

    @Test fun reopeningDraftKeepsJingtongHospitalAndRequiresFreshPatientSelection(): Unit = runBlocking {
        val graph = AppGraph(compose.activity, AppMode(false), source)
        val account = graph.beijingConnections.refresh(channel)
        val selection = graph.beijingConnections.save(HospitalRoute(hospitalId, channel), "测试医院（虚构）", account, person, card)
        val task = BookingTask(UUID.randomUUID().toString(), VisitCondition(selection.reference,
            DepartmentRef("parent", "child", "内科", "测试科室", ""), "doctor", "测试医生", date, 0, 1440, "2", 8000),
            Instant.now().plusSeconds(3600), demo = false, binding = graph.beijingConnections.binding(selection.reference))
        show(graph, task) { _, _ -> error("No save expected") }
        compose.onNodeWithText("✓ 京通").assertExists()
        compose.onNodeWithText("已选 测试医院（虚构），下一步核验就诊人并选择科室与医生。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下一步 · 就诊条件").performClick()
        compose.onNodeWithText("下一步 · 执行设置").assertIsNotEnabled()
        compose.onNodeWithText("已保存：测* · 自费 · ****5678").performScrollTo().assertIsDisplayed()
    }

    private fun show(graph: AppGraph, reuse: BookingTask?, save: (BookingTask, Boolean) -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Warm, surface = Color.White,
                onPrimary = Color.White, onBackground = Ink, onSurface = Ink, secondary = Teal)) {
                TaskEditorScreen(graph, HospitalConnection(), emptyList(), reuse, false, {}, {}, save)
            }
        }
    }
    private fun findTimePicker(root: View): TimePicker? {
        if (root is TimePicker) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) findTimePicker(root.getChildAt(index))?.let { return it }
        return null
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
