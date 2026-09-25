package cn.guahao.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.guahao.AppGraph
import cn.guahao.core.*
import cn.guahao.core.psc.PscSession
import cn.guahao.hospital.DemoGateway
import cn.guahao.payment.OfficialPaymentHandoff
import cn.guahao.runtime.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Teal = Color(0xFF176B5B)
private val Ink = Color(0xFF17342F)
private val Muted = Color(0xFF60726D)
private val Warm = Color(0xFFF5F6F2)
private val Amber = Color(0xFFFFEAC3)
private val zone = ZoneId.of("Asia/Shanghai")
private fun timestamp(i: Instant) = i.atZone(zone).format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm:ss"))
private fun money(fen: Long) = "¥" + java.math.BigDecimal.valueOf(fen, 2).toPlainString()
private fun phaseLabel(p: TaskPhase) = when(p) {
    TaskPhase.DRAFT -> "草稿"; TaskPhase.WAITING -> "等待放号"; TaskPhase.SEARCHING -> "正在查号"
    TaskPhase.SUBMITTING -> "正在提交"; TaskPhase.RECONCILING -> "结果待确认"
    TaskPhase.AWAITING_PAYMENT -> "锁号成功，待付款"; TaskPhase.BOOKED -> "挂号已完成"
    TaskPhase.EXPIRED -> "未挂到"; TaskPhase.STOPPED -> "已停止"; TaskPhase.NEEDS_ATTENTION -> "需要处理"
}
@Composable fun GuahaoApp(graph: AppGraph, initialTask: String?) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Warm, surface = Color.White,
        onPrimary = Color.White, onBackground = Ink, onSurface = Ink, secondary = Teal),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Ink),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink))) {
        val scope = rememberCoroutineScope()
        var records by remember { mutableStateOf(emptyList<TaskRecord>()) }
        var session by remember { mutableStateOf<PscSession?>(null) }
        var page by rememberSaveable { mutableStateOf(if (initialTask != null) "detail" else "home") }
        var selected by rememberSaveable { mutableStateOf(initialTask) }
        var editorKey by rememberSaveable { mutableIntStateOf(0) }
        var reuse by remember { mutableStateOf<BookingTask?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var resolveTask by remember { mutableStateOf<String?>(null) }
        var now by remember { mutableStateOf(Instant.now()) }
        val snackbar = remember { SnackbarHostState() }
        suspend fun reload() = withContext(Dispatchers.IO) { graph.store.all() to graph.sessions.current() }.let { records = it.first; session = it.second }
        fun work(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try { block(); reload() }
                catch (e: CancellationException) { throw e }
                catch (e: HospitalException) { error = e.safeMessage }
                catch (e: IllegalStateException) { error = e.message?.takeIf { it.length < 150 && !it.contains("http") } ?: "操作未完成，请检查条件后重试" }
                catch (_: Exception) { error = "操作未完成，请检查输入、医院连接或网络后重试" }
                finally { busy = false }
            }
        }
        LaunchedEffect(Unit) {
            while (isActive) { runCatching { reload() }.onFailure { error = "本机数据暂不可读，请解锁手机后重试" }; now = Instant.now(); delay(1000) }
        }
        BackHandler(page !in setOf("home", "history", "settings")) { page = "home" }
        Scaffold(containerColor = Warm, snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (page in setOf("home", "history", "settings")) NavigationBar(containerColor = Color.White) {
                    listOf("home" to "挂号任务", "history" to "挂号记录", "settings" to "设置").forEachIndexed { i, (id, title) ->
                        NavigationBarItem(selected = page == id, onClick = { page = id }, icon = { Text(listOf("◷", "▤", "⚙")[i], fontSize = 22.sp) }, label = { Text(title) })
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(page) {
                    "home", "history" -> Content {
                        Text("佑安医院 · 微信服务号", color = Muted, fontSize = 13.sp)
                        Heading(if (page == "home") "挂号任务" else "挂号记录")
                        Text(if (page == "home") "提前设置，到点自动挂号" else "任务结果和医院付款状态", color = Muted)
                        if (page == "home") Primary("＋ 新建挂号任务", !busy) { reuse = null; editorKey++; page = "editor" }
                        val display = if (page == "history") records else records.filter { it.phase !in setOf(TaskPhase.BOOKED, TaskPhase.EXPIRED, TaskPhase.STOPPED) }
                        if (display.isEmpty()) Panel {
                            Text("◷", fontSize = 54.sp, color = Teal)
                            Text(if (page == "home") "把守候放号交给任务" else "暂时没有挂号记录", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("设置就诊条件和放号时间，锁号成功后通知你去微信付款。", color = Muted)
                            if (page == "home") Text("首次使用可选择演示模式，体验完整流程。演示不会连接医院。", color = Muted)
                        }
                        display.forEach { r ->
                            Panel(onClick = { selected = r.task.id; page = "detail" }) {
                                Badge((if (r.task.demo) "演示 · " else "") + phaseLabel(r.phase), r.order != null || r.phase == TaskPhase.NEEDS_ATTENTION)
                                if (r.phase == TaskPhase.WAITING) {
                                    Text(r.task.releaseAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss")), fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${timestamp(r.task.releaseAt)} 放号 · 北京时间", color = Muted)
                                }
                                Text("${r.task.condition.department.name} · ${r.task.condition.doctorName}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text("${r.task.condition.visitDate}  ·  ${periodLabel(r.task.condition.startMinute, r.task.condition.endMinute)}", color = Muted)
                                Text("${r.task.maxRuntimeMinutes} 分钟内尝试 · ${money(r.task.condition.maxFeeFen)} 以内", color = Muted)
                                Text("查看任务详情  →", color = Teal)
                            }
                        }
                        if (page == "home") Panel {
                            Text("执行准备", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (session == null) "医院尚未连接，可先体验演示" else "已连接 · ${session!!.patientName}")
                            Text("通知、精确定时和华为后台管理需提前准备。", color = Muted)
                            TextButton(onClick = { page = "settings" }) { Text("检查运行设置 →") }
                        }
                    }
                    "session" -> Content {
                        BackTitle("连接医院") { page = "settings" }
                        SessionScreen(session, busy, onImport = { raw -> work { withContext(Dispatchers.IO) { graph.importSession(raw) }; snackbar.showSnackbar("已核验就诊人，请确认姓名") } }, onWeChat = { if (!OfficialPaymentHandoff.openWeChat(graph.context)) error = "未安装微信，请在手机微信中打开北京佑安医院服务号" })
                    }
                    "settings" -> Content {
                        Heading("设置")
                        Panel {
                            Text("医院连接", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("北京佑安医院 · 微信服务号", color = Muted)
                            Text(session?.let { "已核验：${it.patientName}" } ?: "尚未连接")
                            Primary(if (session == null) "连接医院" else "重新导入当前就诊人", !busy) { page = "session" }
                        }
                        RuntimeSettings(graph)
                        DefaultSettings(graph.context)
                        Panel { Text("数据留在本机", fontWeight = FontWeight.SemiBold); Text("会话、就诊资料与任务加密保存，不进行系统备份。不会自动读取剪贴板、读取微信数据或自动付款。", color = Muted) }
                    }
                    "editor" -> key(editorKey) {
                        TaskEditorScreen(graph, session, reuse, busy, onBack = { page = "home" },
                            onConnect = { page = "session" },
                            onSave = { task, enable -> work {
                                    withContext(Dispatchers.IO) { graph.store.save(TaskRecord(task)) }
                                selected = task.id; page = "detail"
                                if (enable) withContext(Dispatchers.IO) { graph.enable(task.id) }
                            } })
                    }
                    "detail" -> {
                        val r = records.find { it.task.id == selected }
                        Content {
                            BackTitle("任务详情") { page = "home" }
                            if (r == null) Text("正在读取任务…") else {
                                TaskStatus(r, now)
                                if (r.phase == TaskPhase.DRAFT) {
                                    Primary("确认开启自动挂号", !busy) { work { withContext(Dispatchers.IO) { graph.enable(r.task.id) } } }
                                    OutlinedButton(onClick = { reuse = r.task; editorKey++; page = "editor" }, modifier = Modifier.fillMaxWidth()) { Text("修改条件并另存为任务") }
                                }
                                if (r.order != null) {
                                    Panel(tint = Amber) {
                                        Text("去微信完成付款", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                        Text(OfficialPaymentHandoff.instructions)
                                        Text("打开微信后需按以上步骤找到本单。", color = Muted)
                                        if (!r.task.demo) Primary("打开微信") { if (!OfficialPaymentHandoff.openWeChat(graph.context)) error = "未安装微信，请在手机打开服务号" }
                                        Primary("刷新付款结果", !busy) { work { withContext(Dispatchers.IO) { graph.refreshPayment(r.task.id) } } }
                                        if (r.task.demo && r.phase != TaskPhase.BOOKED) OutlinedButton(onClick = {
                                            work { withContext(Dispatchers.IO) {
                                                graph.store.update(r.task.id) { it.copy(order = it.order!!.copy(phase = if (it.task.paymentPreference == PaymentPreference.INSURANCE_FIRST) OrderPhase.INSURANCE_PAID else OrderPhase.BOOKED,
                                                    rawStatus = if (it.task.paymentPreference == PaymentPreference.INSURANCE_FIRST) "6" else "2")) }
                                                graph.refreshPayment(r.task.id)
                                            } }
                                        }, modifier = Modifier.fillMaxWidth()) { Text("演示：模拟用户完成付款") }
                                    }
                                }
                                if (r.attempt != null && r.order == null && !r.manuallyResolved) Panel(tint = Amber) {
                                    Text("提交结果需要核对", fontWeight = FontWeight.Bold)
                                    Text("请在微信服务号「挂号结果查询」检查。本 App 不会因为列表暂时为空而再次锁号。")
                                    OutlinedButton(onClick = { work { withContext(Dispatchers.IO) { graph.engine.run(r.task.id, r.task.generation, UUID.randomUUID().toString()) } } }, enabled = !busy && now.isBefore(r.attempt!!.sentAt.plusSeconds(120))) { Text("继续有限核对") }
                                    OutlinedButton(onClick = { work { withContext(Dispatchers.IO) { graph.manualReconcile(r.task.id) } } }, enabled = !busy) { Text("手动查询同一订单") }
                                    if (r.phase == TaskPhase.NEEDS_ATTENTION && !now.isBefore(r.attempt!!.sentAt.plusSeconds(120))) TextButton(onClick = { resolveTask = r.task.id }) { Text("已在医院人工核对，结束本任务") }
                                }
                                if (r.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING)) OutlinedButton(onClick = { work { withContext(Dispatchers.IO) { graph.stop(r.task.id) } } }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("停止本次挂号任务") }
                                if (r.phase in setOf(TaskPhase.BOOKED, TaskPhase.EXPIRED, TaskPhase.STOPPED)) OutlinedButton(onClick = { reuse = r.task; editorKey++; page = "editor" }, modifier = Modifier.fillMaxWidth()) { Text("复用条件新建任务") }
                                Text("停止任务不会取消医院订单。取消或退款请在医院官方渠道操作。", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("需要处理") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
        resolveTask?.let { id -> AlertDialog(onDismissRequest = { resolveTask = null }, title = { Text("确认已人工核对") },
            text = { Text("请先在微信服务号检查本次提交是否已生成订单，并处理付款或取消。结束本任务仅解除后续任务限制，保留原提交记录，不代表医院无订单，也不会取消医院挂号。") },
            confirmButton = { TextButton(onClick = { resolveTask = null; work { withContext(Dispatchers.IO) { graph.acknowledgeManualResolution(id) } } }) { Text("我已核对并处理") } },
            dismissButton = { TextButton(onClick = { resolveTask = null }) { Text("返回核对") } }) }
    }
}

@Composable private fun Content(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
@Composable private fun Heading(text: String) { Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink) }
@Composable private fun BackTitle(text: String, back: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = back, contentPadding = PaddingValues(0.dp), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("‹ 返回") }; Spacer(Modifier.width(12.dp)); Heading(text) } }
@Composable private fun Panel(tint: Color = Color.White, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(tint, RoundedCornerShape(20.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun Primary(text: String, enabled: Boolean = true, click: () -> Unit) { Button(onClick = click, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text, fontSize = 16.sp, color = if (enabled) Color.White else Muted) } }
@Composable private fun Badge(text: String, attention: Boolean = false) { Text(text, color = if (attention) Color(0xFF795519) else Teal, fontSize = 13.sp, modifier = Modifier.background(if (attention) Amber else Color(0xFFE7F1EA), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) }
@Composable private fun Value(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = Muted, fontSize = 13.sp); Text(value, fontSize = 16.sp) } }
private fun periodLabel(start: Int, end: Int) = when(start to end) { 0 to 1440 -> "全天"; 0 to 720 -> "上午"; 720 to 1440 -> "下午"; else -> "%02d:%02d–%02d:%02d".format(start/60, start%60, end/60, end%60) }

@Composable private fun SessionScreen(session: PscSession?, busy: Boolean, onImport: (String) -> Unit, onWeChat: () -> Unit) {
    var raw by remember { mutableStateOf("") }
    Panel {
        Text("使用你在医院登记的就诊人", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("1. 打开北京佑安医院微信服务号。\n2. 进入「就诊服务 → 预约挂号」，确认就诊人。\n3. 复制当前官方页面链接，主动粘贴到下方。", lineHeight = 26.sp)
        OutlinedButton(onClick = onWeChat, modifier = Modifier.fillMaxWidth()) { Text("打开微信") }
        OutlinedTextField(value = raw, onValueChange = { raw = it }, label = { Text("粘贴服务号页面链接") }, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Primary("连接并核验就诊人", raw.isNotBlank() && !busy) { val value = raw; raw = ""; onImport(value) }
        Text("链接包含身份凭据，仅用于本机接入。不会自动读取剪贴板。", fontSize = 13.sp, color = Muted)
    }
    session?.let { Panel { Badge("医院已核验"); Value("当前就诊人", it.patientName); Text("请核对姓名。切换就诊人需先在微信切换，再导入对应页面。", color = Muted) } }
}

@Composable private fun RuntimeSettings(graph: AppGraph) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) { delay(1000); tick++ } }
    val ready = remember(tick) { readiness(context, true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    fun settings(action: String, data: Uri? = null) { runCatching { context.startActivity(Intent(action).apply { this.data = data }) } }
    Panel {
        Text("运行准备", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("${if (ready.notificationsAllowed) "✓" else "○"} 通知提醒")
        if (!ready.notificationsAllowed) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("允许通知") }
        if (!ready.notificationsAllowed) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("打开通知设置（包含通知类别）") }
        Text("${if (ready.exactAlarmAllowed) "✓" else "○"} 精确定时")
        if (!ready.exactAlarmAllowed && Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) }) { Text("允许闹钟和提醒") }
        Text("省电限制：${if (batteryExempt(context)) "系统已豁免" else "仍受系统省电策略影响"}")
        Text("华为设置 → 应用启动管理 → 挂号：改为手动管理，允许自启动、关联启动和后台活动。菜单以实机为准。", color = Muted)
        OutlinedButton(onClick = { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")) }) { Text("打开应用设置") }
        OutlinedButton(onClick = { settings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("打开电池优化设置") }
        Row(Modifier.fillMaxWidth().clickable {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("batteryAcknowledged", !ready.batteryRestrictionAcknowledged).apply(); tick++
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ready.batteryRestrictionAcknowledged, onCheckedChange = null)
            Text("已检查后台设置，了解仍需真机验证", modifier = Modifier.weight(1f))
        }
        Text("关机、无网络或强行停止 App 时无法执行。权限开启不等于已通过锁屏验收。", color = Muted, fontSize = 13.sp)
    }
}
@Composable private fun DefaultSettings(context: Context) {
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var minutes by remember { mutableStateOf(prefs.getInt("defaultMinutes", 30).toString()) }
    var insurance by remember { mutableStateOf(prefs.getBoolean("defaultInsurance", true)) }
    Panel {
        Text("新任务默认值", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(minutes, { minutes = it; it.toIntOrNull()?.takeIf { n -> n > 0 }?.let { n -> prefs.edit().putInt("defaultMinutes", n).apply() } }, label = { Text("最长运行时长（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = minutes.toIntOrNull()?.let { it > 0 } != true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(insurance, { insurance = it; prefs.edit().putBoolean("defaultInsurance", it).apply() }); Text("默认优先医保移动支付", Modifier.padding(start = 8.dp)) }
        Text("只影响新建任务，已启用任务的时间和支付方式保持原设置。", color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun TaskStatus(r: TaskRecord, now: Instant) {
    val t = r.task
    if (t.demo) Badge("演示模式 · 所有就诊数据均为虚构")
    Panel(tint = if (r.order != null || r.phase == TaskPhase.NEEDS_ATTENTION) Amber else Color.White) {
        Badge(phaseLabel(r.phase), r.order != null || r.phase == TaskPhase.NEEDS_ATTENTION)
        if (r.phase == TaskPhase.WAITING) {
            val seconds = Duration.between(now, t.releaseAt).seconds.coerceAtLeast(0)
            Text("%02d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text("距放号 · 北京时间", color = Muted)
        }
        Text(r.note.ifBlank { "核对条件后开启自动挂号" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        r.order?.let { o ->
            Value("医院付款期限", o.invalidAt?.let(::timestamp) ?: "请尽快到医院页面核对付款期限")
            Value("医院订单金额", money(o.feeFen))
            Value("订单号", o.orderNo)
            if (o.insuranceVerified) Text("医保付款与医院挂号结果均已确认", color = Teal)
        }
    }
    Panel {
        Value("医院 / 渠道", "北京佑安医院 · 微信服务号")
        Value("科室 / 医生", "${t.condition.department.name} · ${t.condition.doctorName}")
        Value("就诊日期 / 时段", "${t.condition.visitDate} · ${periodLabel(t.condition.startMinute, t.condition.endMinute)}")
        Value("放号时间", timestamp(t.releaseAt))
        Value("停止查号", "${timestamp(t.deadline)} · 最长 ${t.maxRuntimeMinutes} 分钟")
        Value("费用上限", money(t.condition.maxFeeFen))
        Value("支付方式", if (t.paymentPreference == PaymentPreference.INSURANCE_FIRST) "优先医保；不可用时提醒处理" else "用户在微信全额付款")
        Value("是否专程来京就医", if (t.condition.purpose == "1") "是" else "否")
    }
}

@Composable private fun TaskEditorScreen(graph: AppGraph, session: PscSession?, reuse: BookingTask?, busy: Boolean,
    onBack: () -> Unit, onConnect: () -> Unit, onSave: (BookingTask, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var step by rememberSaveable { mutableIntStateOf(1) }
    var demo by remember { mutableStateOf(reuse?.demo ?: true) }
    var department by remember { mutableStateOf(reuse?.condition?.department ?: DemoGateway.department) }
    var doctorCode by remember { mutableStateOf(reuse?.condition?.doctorCode ?: "demo-doctor") }
    var doctorName by remember { mutableStateOf(reuse?.condition?.doctorName ?: "林医生（虚构）") }
    var date by remember { mutableStateOf(reuse?.condition?.visitDate?.takeIf { !it.isBefore(LocalDate.now(zone)) } ?: LocalDate.now(zone).plusDays(7)) }
    var release by remember { mutableStateOf(Instant.now().plusSeconds(120).atZone(zone).withNano(0)) }
    var minutes by rememberSaveable { mutableStateOf((reuse?.maxRuntimeMinutes ?: prefs.getInt("defaultMinutes", 30)).toString()) }
    var fee by rememberSaveable { mutableStateOf(reuse?.condition?.maxFeeFen?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "80") }
    var start by rememberSaveable { mutableIntStateOf(reuse?.condition?.startMinute ?: 0) }
    var end by rememberSaveable { mutableIntStateOf(reuse?.condition?.endMinute ?: 1440) }
    var purpose by rememberSaveable { mutableStateOf(reuse?.condition?.purpose ?: "") }
    var insurance by rememberSaveable { mutableStateOf(reuse?.paymentPreference?.let { it == PaymentPreference.INSURANCE_FIRST } ?: prefs.getBoolean("defaultInsurance", true)) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var departments by remember { mutableStateOf(emptyList<DepartmentRef>()) }
    var candidates by remember { mutableStateOf(emptyList<Candidate>()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val patient = if (demo) DemoGateway.patient else session?.reference
    fun condition(): VisitCondition? = runCatching { VisitCondition(patient ?: return null, department, doctorCode, doctorName, date, start, end, purpose, yuanToFen(fee)) }.getOrNull()
    fun loadDepartments() {
        if (patient == null) return
        loading = true; loadError = null
        scope.launch { try { departments = withContext(Dispatchers.IO) { graph.gateway.departments(patient) }; pick = "department" }
            catch (e: Exception) { loadError = (e as? HospitalException)?.safeMessage ?: "科室加载失败，请重新连接或稍后刷新" }
            finally { loading = false } }
    }
    fun loadDoctors() {
        if (patient == null || purpose.isBlank()) { loadError = "请先选择就诊人和是否专程来京就医"; return }
        loading = true; loadError = null
        scope.launch { try {
            val c = VisitCondition(patient, department, doctorCode.ifBlank { "pending" }, doctorName.ifBlank { "待选医生" }, date, start, end, purpose, runCatching { yuanToFen(fee) }.getOrDefault(8000))
            candidates = withContext(Dispatchers.IO) { graph.gateway.candidates(c) }; pick = "doctor"
        } catch (e: Exception) { loadError = (e as? HospitalException)?.safeMessage ?: "医生加载失败，请刷新科室或重新连接" }
        finally { loading = false } }
    }
    Content {
        BackTitle("新建挂号任务") { if (step > 1) step-- else onBack() }
        Text("$step / 3  ·  ${listOf("就诊条件", "执行设置", "确认启用")[step-1]}", color = Teal, fontWeight = FontWeight.SemiBold)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (step == 1) {
            Panel {
                Text("运行模式", fontWeight = FontWeight.SemiBold)
                Choice("演示模式 · 不连接医院", demo) { demo = true; department = DemoGateway.department; doctorCode = "demo-doctor"; doctorName = "林医生（虚构）" }
                Choice("真实挂号 · 使用本人服务号", !demo) { demo = false; department = DepartmentRef("", "", "", "请选择科室", ""); doctorCode = ""; doctorName = "" }
                if (demo) Text("演示使用眼科虚构人物和 50 元示例号源。", color = Muted)
                else if (session == null) Primary("先连接医院", !busy, onConnect)
                else Value("就诊人（请核对）", session.patientName)
            }
            Panel {
                Value("医院", "北京佑安医院")
                Text("是否专程来京就医", fontWeight = FontWeight.SemiBold)
                Choice("是", purpose == "1") { purpose = "1" }
                Choice("否", purpose == "2") { purpose = "2" }
                OutlinedButton(onClick = ::loadDepartments, enabled = patient != null && !loading, modifier = Modifier.fillMaxWidth()) { Text("科室：${department.name}") }
                OutlinedButton(onClick = ::loadDoctors, enabled = patient != null && department.code.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) { Text("医生：${doctorName.ifBlank { "请选择医生" }}") }
                OutlinedButton(onClick = { selectDate(context, date) { date = it } }, modifier = Modifier.fillMaxWidth()) { Text("就诊日期：$date") }
                Text("可接受就诊时段", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple("全天",0,1440), Triple("上午",0,720), Triple("下午",720,1440)).forEach { (label,s,e) -> FilterChip(selected = start == s && end == e, onClick = { start=s; end=e }, label = { Text(label) }) }
                }
                OutlinedButton(onClick = { selectTime(context, start / 60, start % 60) { h,m -> start=h*60+m; selectTime(context, minOf(end / 60,23), end%60) { eh,em -> end=eh*60+em } } }, modifier = Modifier.fillMaxWidth()) { Text("自定义时段 · ${periodLabel(start,end)}") }
                Text("没有目标日期排班时，可以保留该科室已确认的医生；放号时只匹配该医生。", color = Muted, fontSize = 13.sp)
            }
            Primary("下一步 · 执行设置", patient != null && department.code.isNotBlank() && doctorCode.isNotBlank() && purpose.isNotBlank() && start < end && !date.isBefore(LocalDate.now(zone))) { step = 2 }
        } else if (step == 2) {
            Panel {
                Text("何时开始查号", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { selectDate(context, release.toLocalDate()) { release = it.atTime(release.toLocalTime()).atZone(zone) } }, modifier = Modifier.fillMaxWidth()) { Text("放号日期：${release.toLocalDate()}") }
                OutlinedButton(onClick = { selectTime(context, release.hour, release.minute) { h,m -> release=release.withHour(h).withMinute(m).withSecond(0) } }, modifier = Modifier.fillMaxWidth()) { Text("放号时刻：${release.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}（北京时间）") }
                if (demo) {
                    OutlinedButton(
                        onClick = { release = Instant.now().plusSeconds(60).atZone(zone).withNano(0) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, Teal),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Teal.copy(alpha = 0.06f))
                    ) { Text("快捷设置为 1 分钟后") }
                    Text("演示任务也按上方选择的日期和时刻执行。", color = Muted, fontSize = 13.sp)
                }
                OutlinedTextField(minutes, { minutes=it }, label = { Text("最长运行时长（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), isError = minutes.toIntOrNull()?.let { it>0 } != true)
                minutes.toIntOrNull()?.takeIf { it>0 }?.let { Text("停止查号：${timestamp(release.toInstant().plusSeconds(it.toLong()*60))}", color = Teal) }
                Text("从放号时刻开始计时。医院付款期限独立计算。", color = Muted, fontSize = 13.sp)
            }
            Panel {
                OutlinedTextField(fee, { fee=it }, label = { Text("费用上限（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), isError = runCatching { yuanToFen(fee) }.isFailure)
                Choice("优先医保移动支付", insurance) { insurance=true }
                Choice("我在微信全额付款", !insurance) { insurance=false }
                Text("医保不可用时提醒你处理，不自动切换支付方式。付款由你在微信完成。", color = Muted)
            }
            Primary("下一步 · 核对并启用", condition() != null && minutes.toIntOrNull()?.let { it>0 } == true && release.toInstant().isAfter(Instant.now())) { step=3 }
        } else {
            val condition = condition()
            if (condition != null) {
                Panel {
                    Badge(if (demo) "演示任务" else "将自动提交真实医院挂号", !demo)
                    Value("就诊人", if (demo) "演示就诊人（虚构）" else session?.patientName ?: "尚未连接")
                    Value("科室 / 医生", "${department.name} · $doctorName")
                    Value("就诊日期 / 时段", "$date · ${periodLabel(start,end)}")
                    Value("放号时间", timestamp(release.toInstant()))
                    Value("最长运行", "$minutes 分钟")
                    Value("费用 / 支付", "${money(condition.maxFeeFen)} 以内 · ${if (insurance) "优先医保" else "全额支付"}")
                }
                RuntimeSettings(graph)
                Choice("我已核对就诊人和条件，允许到点自动提交一次符合条件的挂号", accepted) { accepted = !accepted }
                fun save(enable: Boolean) { onSave(BookingTask(UUID.randomUUID().toString(), condition, release.toInstant(), minutes.toInt(), if (insurance) PaymentPreference.INSURANCE_FIRST else PaymentPreference.FULL_AMOUNT_BY_USER, demo=demo), enable) }
                Primary(if (demo) "开启演示挂号任务" else "确认开启自动挂号", accepted && !busy && readiness(context, patient != null).ready) { save(true) }
                OutlinedButton(onClick = { save(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("先保存草稿") }
            }
        }
    }
    if (pick != null) AlertDialog(onDismissRequest = { pick = null; search = "" }, title = { Text(if (pick == "department") "选择科室" else "选择医生") }, text = {
        Column {
            OutlinedTextField(search, { search=it }, label = { Text("搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                if (pick == "department") departments.filter { it.name.contains(search) }.forEach { d -> TextButton(onClick = { department=d; doctorCode=""; doctorName=""; pick=null; search="" }, modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("${d.name} · ${d.parentName}") } }
                else {
                    val doctors = candidates.distinctBy { it.doctorCode }.filter { it.doctorName.contains(search) }
                    if (doctors.isEmpty()) Text("该科室暂未返回可核验的医生排班，请稍后刷新。", Modifier.padding(12.dp))
                    doctors.forEach { c -> TextButton(onClick = { doctorCode=c.doctorCode; doctorName=c.doctorName; pick=null; search="" }, modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("${c.doctorName} · ${money(c.feeFen)}") } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { pick=null; search="" }) { Text("关闭") } })
}
@Composable private fun Choice(label: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClick=action), verticalAlignment=Alignment.CenterVertically) { RadioButton(selected, onClick=null); Text(label, Modifier.weight(1f).padding(start=6.dp)) }
}
private fun selectDate(context: Context, date: LocalDate, selected: (LocalDate) -> Unit) { DatePickerDialog(context, { _,y,m,d -> selected(LocalDate.of(y,m+1,d)) }, date.year,date.monthValue-1,date.dayOfMonth).show() }
private fun selectTime(context: Context, hour: Int, minute: Int, selected: (Int,Int)->Unit) { TimePickerDialog(context, { _,h,m -> selected(h,m) }, hour,minute,true).show() }
