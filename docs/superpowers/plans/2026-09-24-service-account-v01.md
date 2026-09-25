# 佑安微信服务号 v0.1 开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended by the planning skill) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本计划推荐由当前会话原生逐任务执行，需用户评审并选择；当前技能目录未提供上述两个执行技能，执行前先检查其是否存在，不得虚称使用。若没有可用实现，按用户明确选择的原生流程执行，不擅自安装技能或启动多代理。执行时同时阅读下方 Spec。

**Goal:** 交付可安装在 Mate 60 Pro / 鸿蒙 4.2.0 的 Android 首版：提前设定就诊条件，到放号时间自动尝试服务号预约，形成订单后通知用户在官方渠道付款。

**Architecture:** 手机本地执行，`:core` 保存任务规则、协议模型与状态迁移，`:app` 提供服务号适配、加密存储、Android 调度和简洁页面。用户主动导入本人微信服务号页面链接建立会话；医院请求单路串行，提交记录先持久化，结果不明时先核对订单。互联网医院接口不接入本次运行流程。

**Tech Stack:** Kotlin 2.0.21、Gradle 8.13、AGP 8.13.2、JDK 17 编译目标、compileSdk/targetSdk 36、minSdk 26；Compose BOM 2024.09.03、activity-compose 1.9.3、coroutines 1.9.0、kotlinx-serialization-json 1.7.3、OkHttp/MockWebServer 4.12.0、JUnit 4.13.2。任务存储用 Android SQLite 与 Android Keystore AES-GCM，不引入 Room 或云服务。版本是计划固定值；本机已有 SDK 36、AGP 8.13.2、Gradle 8.13 和部分依赖缓存，目标机 API 级别仍需实测。

**Spec:** [v0.1 需求与实现方向](../../design/2026-09-24-v0.1-requirements-and-approach.md)。用户已同意进入开发计划，并明确服务号为主。本文补充的链接导入、参数和验收方法随计划评审。

**UI:** [18 页 UI 设计与交互约定](../../design/ui/2026-09-25-v0.1-ui-design.md)及 [Pencil 源稿](../../../design/guahao-v0.1.pen)已形成，使用眼科示例。产品界面统一使用“挂号任务”“自动挂号”“挂号记录”；锁定号源后显示“锁号成功，待付款”，付款确认后显示“挂号已完成”。官方菜单与接口状态原文保持不变。当前只交付设计和计划，Android 实现尚未开始。

## 2026-09-25 执行记录

已按本计划原生顺序实现 `:core` / `:app`，产出可安装调试 APK。实现与验收明细见 [Mate 60 v0.1 验收记录](../../testing/mate60-v01-acceptance.md)。下方原始检查框保留设计时含义，不能把涉及真实手机的复合检查项整体勾选。

- Task 1–5：代码、合成契约测试、加密持久化、Compose UI、后台定时和医保交接已实现；Android 12 模拟器演示通过。
- Task 6：本机与模拟器验收完成；Mate 60 Pro（鸿蒙 4.2.0.223 / API 31）已安装，4 项存储/演示/UI 测试及实际付款提醒通过。短时任务模拟请求比计划晚 3328ms，请求前屏幕已亮，末尾灭屏断言失败；真实独立会话导入、长时间锁屏/Doze、手机重启和真实只读结果格式未执行。详细时序与边界见验收记录。
- 测试优先级调整：首次红灯运行因依赖缓存与 Java 目标配置失败，不声称获得了规则断言红灯；修正后以新增的行为回归测试验证实现。
- 实现保守偏差：用户级异步 `2011` 不足以排除未决提交，仅锁号端点明确 `2/3` 才允许窗口内重查；没有唯一订单号不会自动认领新订单。医保初始化必须取得完整异步支付适用标记；缺失时提示官方处理。
- 真机接入如遇未覆盖的订单日期/时段格式，将明确报结构变化并停止，需依据本人主动导入后的只读响应补齐合成契约；不得吞掉成空列表。

## Global Constraints

- 目标设备为华为 Mate 60 Pro，鸿蒙 4.2.0；首个医院为北京佑安医院。
- 最长运行时长可配置，默认 30 分钟，从放号时间开始计算；提前取得有效订单就提前结束。
- 医院、渠道固定佑安微信服务号；与互联网医院会话和订单分开。
- 手机锁屏或 App 后台时也要执行；不使用 Google 服务作为必需依赖。
- 支付偏好优先医保移动支付；医保不可用时通知处理，不自行改为全额支付。
- 同一时间只执行一个任务，每个任务只取得一个符合条件的预约；不自动更换医生或科室，不自动付款。
- 查询至少间隔 5 秒，单请求在途；网络错误按 5、10、20、40、60 秒退避，遵守服务端等待要求。
- 达到截止时间后不发起新的查询或提交；已发出的提交仍有限核对。自动核对最多持续到该次提交发出后 120 秒，之后转人工核对。
- 普通待付/完成状态为 `1/2`，医保待付/完成状态为 `7/6`；付款截止取医院 `invalidtime`，不能写死为 30 分钟。医保完成结合 `queryRegisYbPayState code=0` 与同单状态 6 判断。
- 导入链接与凭据只保存在本机加密私有存储；不读取微信存储、不公开身份 URL、不绕过正常认证。
- 首次正常会话接入、异常恢复和真机后台行为仍是 Android 验收项；已有单笔服务号预约与付款证据直接复用，不重复占号证明接口可用。
- 保留现有医保研究及其它并行工作；只提交当前任务产生的设计/实现变更。开发在独立分支，远端推送仅按项目规则快进到 `origin/main`，禁止强推。

## Review Focus

1. 身份链接含 `%2B`、`+`、重复参数或恶意主机：解码一次，不能把凭据改坏或发到其它域名；Task 2 的导入与重定向测试覆盖。
2. 返回的就诊人与选定对象不同、官方页面同时生成订单：不能错误认领，也不能带着另一人的凭据提交；Task 2 身份绑定及 Task 3 订单关联测试覆盖。
3. 提交已送达但响应丢失，或持久化后进程立刻死亡：恢复时只能核对，不重复锁号；Task 3 的崩溃点和断流测试覆盖。
4. 设备延迟唤醒、闹钟重复触发、系统时钟变动：不能延长运行窗口或启动第二条执行循环；Task 4 的时钟与调度测试覆盖。
5. 医保初始化响应丢失、状态 7 但 isyb 仍为 0、付款后回跳缺失：不能重复初始化、静默全额支付或漏认医保已完成；Task 5 的交接策略测试及真机验收覆盖。

## 现有证据与尚未实现的部分

| 事项 | 证据与本计划处理 |
| --- | --- |
| 服务号单笔闭环 | [单笔摘要](../../research/2026-09-24-youan-service-account-booking-result.md)与 `psc-live-booking-verified.json`：只锁号一次，订单同号，手机可见，状态 `1 → 2`；用户确认已付款 |
| 订单异步查询 | `lockRegis code=0` 表示接受；`queryRegisStat code=2` 等待、`0` 得到结果，`2011` 静态代码表示无号；每个接口分别解释 code |
| 已有会话 | 链接中的用户凭据、同人 `changePatient` 及功能校验已有实测；Android 导入独立 Cookie 获取与持久保存尚待 Task 2 |
| 医保 | [医保核对](../../research/2026-09-24-youan-medical-insurance-payment.md)与 `psc-insurance-live-verified.json`：同单 `1 → 7 → 6`、独立医保支付查询成功、用户确认手机流程。状态 7 的结果页 `sendYbPay()` 在手机重新获取授权参数；前轮遗漏该分支的结论已纠正 |
| Android | 当前只有文档和脱敏材料，没有 Gradle 工程或 APK。不会把 macOS 脚本验证当成锁屏执行验证 |

## 目录与责任

```text
settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
gradle/wrapper/* / gradlew / gradlew.bat
core/src/main/kotlin/cn/guahao/core/
  BookingModels.kt          条件、候选号、订单和状态，不含 Android 类型
  BookingPolicy.kt          时间、金额、时段和患者匹配
  BookingEngine.kt          单任务推进，外部副作用经接口执行
  BookingPorts.kt           医院、持久化和时钟接口
  PaymentPolicy.kt          医保、手工付款和特殊条件决策
  psc/PscModels.kt          服务号模型与各接口响应语义
  psc/SessionImport.kt      身份链接解析，严格来源校验
  psc/PscPageParser.kt      HTML 内嵌数据提取，不执行 JavaScript
core/src/test/kotlin/cn/guahao/core/*Test.kt
core/src/test/resources/psc/ 合成数据与公开脱敏号源夹具
app/src/main/kotlin/cn/guahao/
  MainActivity.kt / AppGraph.kt
  hospital/PscClient.kt / hospital/PscCookieJar.kt
  storage/EncryptedVault.kt / storage/BookingDatabase.kt
  runtime/BookingService.kt / runtime/AlarmScheduler.kt
  runtime/TaskReceiver.kt / runtime/RuntimeReadiness.kt
  notifications/BookingNotifications.kt
  ui/SessionScreen.kt / ui/TaskEditorScreen.kt / ui/TaskStatusScreen.kt
  payment/OfficialPaymentHandoff.kt
app/src/main/AndroidManifest.xml / res/xml/backup_rules.xml
app/src/test/* / app/src/androidTest/*
docs/testing/mate60-v01-acceptance.md
```

通用模型与接口签名在 Task 1 定义；后续任务不得各自创建同名、不同字段的订单或任务对象。原始凭据类型不使用自动打印所有属性的 `data class`，`toString()` 固定为已遮盖文本。

## Task 1：可安装的模拟流程与任务规则

**Files:** 创建构建文件、`:core`、`:app`、`BookingModels.kt`、`BookingPolicy.kt`、`BookingPorts.kt`、三个基础页面；创建 `BookingPolicyTest.kt` 和合成模拟医院实现。修改 README 的构建方法，但保留现有医保研究内容。

**Interfaces:**

```kotlin
enum class Channel { PSC_SERVICE_ACCOUNT }
enum class PaymentPreference { INSURANCE_FIRST, FULL_AMOUNT_BY_USER }
enum class TaskPhase {
    DRAFT, READY, WAITING, SEARCHING, SUBMITTING, RECONCILING,
    AWAITING_PAYMENT, BOOKED, EXPIRED, STOPPED, NEEDS_ATTENTION
}
data class PatientRef(val sessionId: String, val patientId: String)
data class DepartmentRef(
    val parentCode: String, val code: String,
    val parentName: String, val name: String, val originCode: String
)
data class VisitCondition(
    val patient: PatientRef, val department: DepartmentRef,
    val doctorCode: String, val visitDate: java.time.LocalDate,
    val startMinute: Int, val endMinute: Int,
    val purpose: String, val maxFeeFen: Long
)
data class BookingTask(
    val id: String, val condition: VisitCondition,
    val releaseAt: java.time.Instant, val maxRuntimeMinutes: Int = 30,
    val paymentPreference: PaymentPreference = PaymentPreference.INSURANCE_FIRST,
    val generation: Long = 1
) {
    init { require(maxRuntimeMinutes > 0) }
    val deadline: java.time.Instant get() = releaseAt.plusSeconds(maxRuntimeMinutes.toLong() * 60)
}
data class Candidate(
    val department: DepartmentRef, val doctorCode: String, val doctorName: String,
    val date: java.time.LocalDate, val half: String, val hour: String,
    val startMinute: Int, val endMinute: Int, val feeFen: Long,
    val remaining: Int, val titleType: String, val standby: Boolean
)
fun eligible(task: BookingTask, candidate: Candidate, now: java.time.Instant): Boolean
```

- [ ] **先写规则测试。** 使用 `fixtureTask()` 固定合成患者 `test-patient`、眼科、医生 `test-doctor`、北京时间 2026-10-01 08:00 放号、10-08 就诊、全天、80 元上限；`fixtureCandidate()` 与其匹配，余 1，50 元。辅助函数写在测试文件，所有值为合成值。

```kotlin
@Test fun configuredSixtyMinutesDoesNotExpireAtThirty() {
    val task = fixtureTask().copy(maxRuntimeMinutes = 60)
    assertTrue(eligible(task, fixtureCandidate(), task.releaseAt.plusSeconds(31 * 60)))
    assertFalse(eligible(task, fixtureCandidate(), task.releaseAt.plusSeconds(60 * 60)))
}
@Test fun wrongDoctorAndPriceNeverMatch() {
    val t = fixtureTask()
    assertFalse(eligible(t, fixtureCandidate().copy(doctorCode = "other"), t.releaseAt))
    assertFalse(eligible(t, fixtureCandidate().copy(feeFen = 8001), t.releaseAt))
}
```

- [ ] **运行红灯测试。** `./gradlew :core:test --tests '*BookingPolicyTest'`；预期因尚未实现规则而失败。构建先用本机已有 SDK/JDK，缺失依赖在正常构建时取得，不全局升级工具。
- [ ] **实现规则和模拟页面。** 默认运行模式为“演示”，保存任务和手动触发模拟后显示待付款。所有模拟结果显著标记，不产生 HTTP 请求；真实模式在 Task 2、3 完成前不可启用。

```kotlin
fun eligible(task: BookingTask, candidate: Candidate, now: java.time.Instant): Boolean {
    val c = task.condition
    return !now.isBefore(task.releaseAt) && now.isBefore(task.deadline) &&
        candidate.department == c.department && candidate.doctorCode == c.doctorCode &&
        candidate.date == c.visitDate && candidate.remaining > 0 && !candidate.standby &&
        candidate.feeFen in 0..c.maxFeeFen &&
        candidate.startMinute >= c.startMinute && candidate.endMinute <= c.endMinute
}
```

金额由 `BigDecimal` 元精确转分，拒绝负数、超过两位小数和溢出。时段按完整包含匹配，明确显示上午/下午或自定义范围，不隐式缩放。用户所选医生尚无排班时保留医生编码，但任务就绪前需核对官方科室与医生关联；不能从历史医生名猜代码。

- [ ] **验证交付。** `./gradlew :core:test :app:assembleDebug :app:lintDebug`；启动模拟模式，填 60 分钟确认显示的截止确为放号后 60 分钟。通过后按上述文件逐项 `git add`，提交 `feat: 建立挂号任务与模拟演示流程`。

## Task 2：服务号会话导入与真实只读查询

**Files:** 创建 `SessionImport.kt`、`PscModels.kt`、`PscPageParser.kt`、`PscClient.kt`、`PscCookieJar.kt`、`EncryptedVault.kt`；扩展 `SessionScreen.kt`。测试 `SessionImportTest.kt`、`PscParserTest.kt`、`PscClientTest.kt`，Android 加密往返测试 `EncryptedVaultTest.kt`。

**Interfaces:** 消费 Task 1 的 `PatientRef`、`DepartmentRef`、`Candidate`；产出：

```kotlin
class ImportedSession(val userId: String, val userKey: String, val ptno: String) {
    override fun toString() = "ImportedSession([redacted])"
}
class PscSession(
    val reference: PatientRef, val userId: String,
    val userKey: String, val ptno: String, val ptnoKey: String
) { override fun toString() = "PscSession([redacted])" }
fun parseSessionLink(raw: String): ImportedSession
fun requireMatchingIdentity(imported: ImportedSession, returnedUserId: String, returnedPtno: String)
interface SessionRepository {
    suspend fun importAndVerify(raw: String): PatientRef
    suspend fun load(ref: PatientRef): PscSession
    suspend fun invalidate(ref: PatientRef)
}
interface PscReadGateway {
    suspend fun departments(patient: PatientRef): List<DepartmentRef>
    suspend fun candidates(condition: VisitCondition): List<Candidate>
    suspend fun validateBookingAccess(patient: PatientRef): Boolean
}
```

- [ ] **先写导入与协议失败测试。**

```kotlin
@Test fun keyIsDecodedOnce() {
    val parsed = parseSessionLink("https://psc.hkinfo.net/regis/initDept?userId=test&userIdKey=AB%2BC%3D&ptno=patient")
    assertEquals("AB+C=", parsed.userKey)
}
@Test fun identityCannotChangeDuringImport() {
    val imported = ImportedSession("test", "synthetic", "patient")
    assertThrows(IllegalArgumentException::class.java) {
        requireMatchingIdentity(imported, "other", "patient")
    }
}
@Test fun externalOriginIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
        parseSessionLink("https://psc.hkinfo.net.example.org/regis/initDept?userId=test&userIdKey=x&ptno=p")
    }
}
```

增加重复 `userIdKey`、userinfo、非 HTTPS、非 443 端口、fragment、控制字符、空键、原始 `+` 和 URL 中 `%25` 的测试。原始 `+` 不当空格；输出 URL 参数一律按组件编码，JSON 凭据则按相应端点官方协议编码，不能全局重复 `urlencode`。

- [ ] **运行。** `./gradlew :core:test --tests '*SessionImportTest' --tests '*PscParserTest' :app:testDebugUnitTest --tests '*PscClientTest'`，确认身份不一致和未知结构会失败而非退化成空列表。
- [ ] **实现严格导入。** 路径只接受 `/admin/youmanage`、`/regis/initDept`、`/regis/initRegis`、`/regis/initRegisList`；仅提取需要的身份参数。先以自身 CookieJar 读取同源入口，再调用已验证的列表与同人会话接口，要求返回当前同一人且 `ptnoKey`、`userIdKey` 非空；最后以 `functionid=002` 校验。不是 `code=0` 就一律成功：同人会话成功码为 `2`，列表为 `0`。维护页、登录重定向及身份不符都显示需要接入。

```kotlin
fun requireMatchingIdentity(imported: ImportedSession, returnedUserId: String, returnedPtno: String) {
    require(returnedUserId == imported.userId && returnedPtno == imported.ptno)
}
```

Cookie 仅发送到它的合法同源域与路径，尊重 Secure、过期时间和更新；拒绝向外域跳转，禁止把身份 query 自动跟随到别的主机。普通读取 connect timeout 10 秒、call timeout 15 秒；锁号另在 Task 3 关闭透明重试。所有日志只输出端点路径、耗时、业务状态，不输出 query、headers、请求或响应体。

- [ ] **实现无脚本执行的页面解析。** 以脚本变量名定位 `deptOneList`、`deptTwoMap` 和 `regisInfo`，用追踪字符串、转义和括号深度的扫描器截出完整 JSON，再用 kotlinx.serialization 解析；不 `eval`、不运行整个 HTML。若实际数据不是严格 JSON，则返回结构变化错误并保存脱敏结构描述；不能引入 JavaScript 执行器吞掉变化。测试把 `"}"`、转义引号及多个 script 标签放进合成夹具。`syqty` 保存为日期状态；真实余号取 `registryList[].count` 与 `regHourList`，费用取 `fee`，半日码保留字符串。读取现有 `psc-schedule-verified.json` 建立固定夹具，确认 9 月 26 日眼科上午 6 段余号合计为 10。
- [ ] **实现加密会话。** AES/GCM/NoPadding，每次独立随机 12 字节 IV；密钥由 Android Keystore 生成、不要求每次用户认证，密文放私有目录。禁用系统备份与跨设备恢复；清理导入输入框，不自动读取剪贴板。当前会话失效只使相关任务进入需要处理，不删除其它历史任务。
- [ ] **验证。** MockWebServer 验证请求方法、白名单字段、Cookie 更新和外域 Location 被拒；设备测试验证重启 App 后可解密且未解锁时不运行。真实只读接入由用户在手机主动导入一次，记录验证结果，不录屏包含身份的链接。提交 `feat: 接入服务号会话和号源查询`。

## Task 3：持久化自动预约与不重复提交

**Files:** 创建 `BookingEngine.kt`、`BookingDatabase.kt`；扩展 `BookingPorts.kt`、`PscClient.kt`；创建 `BookingEngineTest.kt`、`PscBookingContractTest.kt`、`BookingDatabaseTest.kt`。新增 `core/src/test/resources/psc/` 下合成订单和异步响应。

**Interfaces:**

```kotlin
sealed interface LockReply {
    data object Accepted : LockReply
    data object NoStock : LockReply
    data class Rejected(val code: String) : LockReply
    data object OutcomeUnknown : LockReply
}
sealed interface AsyncReply {
    data object Pending : AsyncReply
    data class OrderFound(val orderNo: String) : AsyncReply
    data object NoStock : AsyncReply
    data class Unknown(val code: String) : AsyncReply
}
enum class OrderPhase { LOCKED, INSURANCE_PENDING, BOOKED, INSURANCE_PAID, OTHER }
data class OrderSnapshot(
    val orderNo: String, val patient: PatientRef, val doctorCode: String?,
    val departmentCode: String?, val doctorName: String, val departmentName: String,
    val source: String, val visitDate: java.time.LocalDate,
    val half: String, val hour: String, val feeFen: Long,
    val phase: OrderPhase, val rawStatus: String,
    val invalidAt: java.time.Instant?, val specialPaymentCondition: Boolean
)
data class SubmissionAttempt(
    val id: String, val taskId: String, val candidate: Candidate,
    val sentAt: java.time.Instant, val baselineOrderNos: Set<String>
)
interface BookingGateway : PscReadGateway {
    suspend fun orders(patient: PatientRef, from: java.time.LocalDate, to: java.time.LocalDate): List<OrderSnapshot>
    suspend fun lock(task: BookingTask, candidate: Candidate): LockReply
    suspend fun querySubmission(patient: PatientRef): AsyncReply
}
interface TaskStore {
    suspend fun task(id: String): BookingTask
    suspend fun claim(id: String, generation: Long, owner: String): Boolean
    suspend fun attempt(id: String): SubmissionAttempt?
    suspend fun recordBeforeSubmit(attempt: SubmissionAttempt)
    suspend fun setPhase(id: String, phase: TaskPhase, order: OrderSnapshot? = null)
    suspend fun release(id: String, owner: String)
}
interface BookingClock { fun now(): java.time.Instant; suspend fun delayMillis(value: Long) }
class BookingEngine(val gateway: BookingGateway, val store: TaskStore, val clock: BookingClock) {
    suspend fun run(taskId: String, generation: Long, owner: String)
    suspend fun stop(taskId: String)
}
```

上述类声明表示待实现的公开签名，不是可直接编译的完整类；`run` 与 `stop` 的方法体在本任务按以下状态规则实现。测试框架 `EngineHarness` 还提供只读 `phase`，从内存 `TaskStore` 取得最后状态，与测试中的断言一致。

- [ ] **先写崩溃点、身份关联和截止测试。** `EngineHarness` 是测试辅助器：由同文件 `FakeGateway`、内存 `TaskStore` 和可推进 `BookingClock` 组合，计数真实接口调用；提供 `task`、`gateway.lockCalls`、`store`、`engine`、`persistAttemptThenRecreateEngine()`。假订单使用 `test-order`，与真实患者和订单无关。

```kotlin
@Test fun durableAttemptIsNeverSubmittedAgain() = runBlocking {
    val h = EngineHarness()
    h.persistAttemptThenRecreateEngine()
    h.engine.run(h.task.id, h.task.generation, "test-runner")
    assertEquals(0, h.gateway.lockCalls)
}
@Test fun acceptedRequestAndBrokenResponseDoNotTriggerSecondLock() = runBlocking {
    val h = EngineHarness(lockReply = LockReply.OutcomeUnknown)
    h.engine.run(h.task.id, h.task.generation, "test-runner")
    assertEquals(1, h.gateway.lockCalls)
    assertEquals(TaskPhase.NEEDS_ATTENTION, h.phase)
}
```

增加同用户其它订单号、医生不符、列表延迟为空、双闹钟并行 `claim`、停止恰好发生在 `recordBeforeSubmit` 后、deadline 恰好等于 now、截止后已发请求返回成功的测试；最后一种仍应保存订单并提示付款。

- [ ] **运行红灯测试。** `./gradlew :core:test --tests '*BookingEngineTest' :app:testDebugUnitTest --tests '*PscBookingContractTest'`。
- [ ] **实现数据库事务与执行顺序。** 单独表保存 task id、generation、phase、lease 与 encrypted payload；attempt 有 task id 与未决状态唯一约束。凭据、患者及订单内容加密，调度列不含姓名。先成功持久化 attempt，再调用网络；如果进程死在二者之间，保守进入核对，不自动补发。执行循环的 Mutex 与数据库 claim 同时使用；进程恢复只可回收已确认不在运行的本进程执行权，不把尚有效的 lease 当成孤儿。

```kotlin
val previous = store.attempt(task.id)
if (previous != null) {
    // run() 在这个分支只允许 querySubmission / orders，不允许 lock。
    store.setPhase(task.id, TaskPhase.RECONCILING)
} else if (!clock.now().isBefore(task.deadline)) {
    store.setPhase(task.id, TaskPhase.EXPIRED)
}
```

Task 1 的 `eligible()` 是唯一候选判定入口；提交前重新检查截止、停止标记、患者凭据版本和费用。预先读取基线订单，已有同条件有效订单时不锁第二个。`recordBeforeSubmit` 与停止操作必须在同一执行锁内决定先后。HTTP 发送期间用户停止只设停止标记，不将无法撤回的提交标为取消成功。

- [ ] **实现服务号提交协议。** `lockRegis` JSON 字段严格按已保存官方函数：`userId,userIdKey,dept_code1,dept_code2,purpose,dept_nm1,dept_nm2,reg_date,reg_half,reg_hour,doctor_code,title_type,iscanceled,doctor,dept_code2_from`，不添加医保字段。`reg_date` 保留服务号 `yyyyMMdd` 语义；`reg_hour` 使用服务器返回的完整时段值。锁号 client 设置 `retryOnConnectionFailure(false)`，禁重定向和应用层重试。

```kotlin
fun decodeLockCode(code: String): LockReply = when (code) {
    "0" -> LockReply.Accepted
    "2", "3" -> LockReply.NoStock
    else -> LockReply.Rejected(code)
}
fun decodeAsyncCode(code: String, orderNo: String?): AsyncReply = when (code) {
    "2" -> AsyncReply.Pending
    "2011" -> AsyncReply.NoStock
    "0" -> orderNo?.takeIf(String::isNotBlank)?.let(AsyncReply::OrderFound)
        ?: AsyncReply.Unknown("missing-order")
    else -> AsyncReply.Unknown(code)
}
```

网络超时不进入 `Rejected`，而是 `OutcomeUnknown`；未知拒绝码停止并保留状态，不擅自继续。确定无号且服务端明确未生成订单时才清理本次尝试并在剩余窗口重查；单号成功后不继续。异步 `data[2]` 的订单号来自已保存官方函数，长度与类型先校验；订单列表 `code=200` 才解析，不能和其它端点的 `0` 混淆。

- [ ] **落实订单列表协议。** 最新 `psc-insurance-results-routing-static.js` 已确认 `data.data[]` 与 `orderno,ptno,dept,doctor,actdate,ampm,reserved_date,fee,status,source,invalidtime,isyb,iscanceled` 等字段。日期筛选仍使用 `ptno,ptnoKey,actdate,enddate`；日期/时段的实际格式在用户主动导入后的只读查询中生成字段类型与合成样例测试，不保存真实订单。科室/医生代码若响应未提供则保留 null，以精确订单号、实际患者、日期时段、金额及本次提交保存的官方名称共同核对，不从名称伪造代码。名称无法唯一关联、字段缺失或相互冲突时保持 `NEEDS_ATTENTION`。`source=2,status=7` 路由到医保缴费；`status=6` 是医保已付，`isyb` 不参与入口选择。候补单即使状态 2/6 也不能显示普通预约完成，按 `iscanceled` 分支处理。
- [ ] **验证。** MockWebServer 使用断开响应的策略模拟医院接受后丢包，HTTP 计数必须只有一次 `/regis/lockRegis`。数据库重开后仍有 attempt。`./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过后提交 `feat: 持久化预约执行与订单核对`。真实锁号按钮仍需用户启用具体条件，不在开发测试自动调用医院写接口。

## Task 4：定时唤醒、前台执行和锁屏状态

**Files:** 创建 `AlarmScheduler.kt`、`TaskReceiver.kt`、`BookingService.kt`、`RuntimeReadiness.kt`、`BookingNotifications.kt`；修改 Manifest 和 `TaskStatusScreen.kt`。测试 `AlarmSchedulerTest.kt`、`RuntimeReadinessTest.kt`、`BookingServiceTest.kt`。

**Interfaces:** 消费 `BookingEngine.run/stop` 和 `TaskStore`；产出：

```kotlin
data class RuntimeReadiness(
    val sessionValid: Boolean, val notificationsAllowed: Boolean,
    val exactAlarmAllowed: Boolean, val batteryRestrictionAcknowledged: Boolean,
    val unlocked: Boolean
) { val ready: Boolean get() = sessionValid && notificationsAllowed && exactAlarmAllowed && batteryRestrictionAcknowledged && unlocked }
interface TaskScheduler {
    fun schedule(task: BookingTask)
    fun cancel(taskId: String)
}
fun shouldStart(task: BookingTask, deliveredGeneration: Long, now: java.time.Instant): Boolean =
    task.generation == deliveredGeneration && now.isBefore(task.deadline)
```

- [ ] **先写时间测试。**

```kotlin
@Test fun delayedAlarmDoesNotRestartWindow() {
    val task = fixtureTask()
    assertFalse(shouldStart(task, task.generation, task.deadline))
    assertFalse(shouldStart(task, task.generation - 1, task.releaseAt))
    assertTrue(shouldStart(task, task.generation, task.releaseAt.plusSeconds(60)))
}
@Test fun missingNotificationPermissionIsNotReady() {
    assertFalse(RuntimeReadiness(true, false, true, true, true).ready)
}
```

- [ ] **运行。** `./gradlew :app:testDebugUnitTest --tests '*AlarmSchedulerTest' --tests '*RuntimeReadinessTest'`；Android 测试用替身 alarm/service，不发真实医院请求。
- [ ] **实现权限与调度。** `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)` 仅安排用户主动启用的任务；提前检查设为尽力而为，不能短间隔连续精确闹钟假设绕过 Doze。放号闹钟广播只启动前台服务，5 秒内显示可见通知，然后 engine 等待或执行。Manifest 声明 INTERNET、POST_NOTIFICATIONS、RECEIVE_BOOT_COMPLETED、SCHEDULE_EXACT_ALARM、FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、WAKE_LOCK；服务 API 34+ 使用 `specialUse`，subtype 明确为用户设定的有限放号窗口预约，低版本按兼容方式启动。该选择用于当前个人安装，后续应用商店上架需单独评估，不能滥用 mediaPlayback 等无关类型。

```xml
<service android:name=".runtime.BookingService"
    android:exported="false" android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Execute a user-scheduled hospital booking task during a bounded release window" />
</service>
```

按 API 判断精确闹钟和通知权限，用户拒绝后保持草稿并提供设置入口。省电限制展示实际设置与真机检查状态，不因前台服务已启动便声称可锁屏联网。不得自动操作华为后台管理开关。只在运行或有限核对期间持有带 timeout 的 PARTIAL_WAKE_LOCK，所有出口释放。

- [ ] **实现恢复。** BOOT_COMPLETED、时间/时区变更、精确闹钟权限恢复、App 启动触发重算。开机广播不直接启动长期服务：用户首次解锁后，未来任务重设闹钟；未决 attempt 优先核对，错过截止则记录过期；受系统限制不能后台启动时发需打开 App 的通知。时区变更不改变原北京时间 instant；运行时用 `elapsedRealtime` 防止时钟后调延长窗口，前跳越过截止停止新请求。`PendingIntent` 带 task id 与 generation，并用 immutable，旧 generation 不复活任务。
- [ ] **验证。** 调度重复广播不会导致第二次锁号，停止通知生效且待核对状态保留。真机演示任务在锁屏至少 30 分钟后到点，记录计划/实际唤醒、通知出现、网络结果；断网恢复不延长截止。无需真实锁号即可测试运行机制。提交 `feat: 增加定时唤醒与锁屏执行状态`。

## Task 5：医保初始化、官方付款通知与同单回查

**Files:** 创建 `PaymentPolicy.kt`、`PaymentCoordinator.kt`、`OfficialPaymentHandoff.kt`；扩展 `PscClient.kt` 和 `BookingDatabase.kt` 的医保初始化记录；完善通知和订单详情。测试 `PaymentPolicyTest.kt`、`PaymentCoordinatorTest.kt`、`PaymentReturnTest.kt`。

**Interfaces:** 消费 Task 3 的 `OrderSnapshot`、`BookingGateway.orders` 与 `PaymentPreference`；产出：

```kotlin
sealed interface PaymentAction {
    data object ShowBooked : PaymentAction
    data object ShowOfficialResultEntry : PaymentAction
    data object InitializeInsurance : PaymentAction
    data object ShowInsuranceResultEntry : PaymentAction
    data class NeedsUserAction(val reason: String) : PaymentAction
}
sealed interface InsuranceReply {
    data object Accepted : InsuranceReply
    data object Pending : InsuranceReply
    data object Paid : InsuranceReply
    data object Unknown : InsuranceReply
    data class Rejected(val code: String) : InsuranceReply
}
interface InsuranceGateway {
    suspend fun initialize(patient: PatientRef, orderNo: String): InsuranceReply
    suspend fun paymentState(patient: PatientRef, orderNo: String): InsuranceReply
}
interface InsuranceAttemptStore {
    suspend fun recorded(orderNo: String): Boolean
    suspend fun recordBeforeInitialization(orderNo: String, startedAt: java.time.Instant): Boolean
    suspend fun recordResult(orderNo: String, reply: InsuranceReply)
}
fun paymentAction(order: OrderSnapshot, preference: PaymentPreference): PaymentAction = when {
    order.specialPaymentCondition -> PaymentAction.NeedsUserAction("请在官方页面核对优惠或特殊支付条件")
    order.phase == OrderPhase.BOOKED || order.phase == OrderPhase.INSURANCE_PAID -> PaymentAction.ShowBooked
    order.phase != OrderPhase.LOCKED && order.phase != OrderPhase.INSURANCE_PENDING ->
        PaymentAction.NeedsUserAction("请核对医院订单状态")
    order.source != "2" -> PaymentAction.NeedsUserAction("请在该订单原渠道处理付款")
    order.phase == OrderPhase.INSURANCE_PENDING -> PaymentAction.ShowInsuranceResultEntry
    preference == PaymentPreference.INSURANCE_FIRST -> PaymentAction.InitializeInsurance
    else -> PaymentAction.ShowOfficialResultEntry
}
class PaymentCoordinator {
    suspend fun prepare(task: BookingTask, order: OrderSnapshot): PaymentAction
    suspend fun refresh(patient: PatientRef, orderNo: String): OrderSnapshot
}
```

以上 `PaymentCoordinator` 为接口签名；构造依赖为 `InsuranceGateway`、`InsuranceAttemptStore`、`BookingGateway`、`BookingClock`。`prepare()` 在符合条件时至多初始化一次，遇到已有记录只回查；`refresh()` 合并同单列表与医保支付查询，不直接执行付款。`ShowBooked` 表示医院预约状态；显示“医保支付成功”还需同单医保查询成功，两者字段分开。

- [ ] **先写支付策略与响应丢失测试。** `fixtureOrder()` 采用 Task 3 的合成 test-order，source=2、LOCKED、50 元、未来 invalidAt、无特殊条件。

```kotlin
@Test fun insurancePreferenceInitializesAfterLocking() {
    assertEquals(PaymentAction.InitializeInsurance,
        paymentAction(fixtureOrder(), PaymentPreference.INSURANCE_FIRST))
}
@Test fun insurancePendingUsesResultsPageEvenWhenInsuranceFlagIsZero() {
    val order = fixtureOrder().copy(phase = OrderPhase.INSURANCE_PENDING, rawStatus = "7")
    assertEquals(PaymentAction.ShowInsuranceResultEntry,
        paymentAction(order, PaymentPreference.INSURANCE_FIRST))
}
@Test fun insurancePaidIsCompleted() {
    assertEquals(PaymentAction.ShowBooked,
        paymentAction(fixtureOrder().copy(phase = OrderPhase.INSURANCE_PAID, rawStatus = "6"), PaymentPreference.INSURANCE_FIRST))
}
```

`PaymentCoordinatorTest` 的 fake gateway 计数 initialize 调用：第一次接受但断开响应、重启后 `recorded(orderNo)=true`，再次 prepare 只能读列表和 paymentState，initialize 总数保持 1。再测状态 7 但 `isyb=0`、初始化后 invalidtime 不变、医保功能关闭/优惠分支、两来源状态冲突、用户取消付款和没有回跳。Fake 数据采用 `psc-insurance-live-verified.json` 的状态语义，个人值全部合成。

- [ ] **运行红灯。** `./gradlew :core:test --tests '*PaymentPolicyTest' --tests '*PaymentCoordinatorTest' :app:testDebugUnitTest --tests '*PaymentReturnTest'`。
- [ ] **实现一次医保初始化。** 锁号已取得本单且核对通过后立即停止查号。任务保存的 INSURANCE_FIRST 代表本次医保选择；检查功能 `001`、有效付款期限、零元/候补/特殊医保/献血优惠条件。需要用户决定的优惠不能自动放弃，医保不支持不能自行降为全额。先持久化初始化尝试再 POST `/order/sxPayCN`，参数 `userId,userIdKey,orderNo`；按官方该端点规则对 JSON 的 userIdKey URL 编码一次。此请求关闭 HTTP 透明重试和跨域重定向。

```kotlin
if (attemptStore.recordBeforeInitialization(order.orderNo, clock.now())) {
    val reply = insurance.initialize(order.patient, order.orderNo)
    attemptStore.recordResult(order.orderNo, reply)
}
// 无论响应是否得到，都按同一 orderNo 回查；已有记录不再自动 initialize。
```

`code=0` 只表示初始化成功；data 是带 `pageAuthCode,sceneId,transId` 的 JSON 字符串，不能作为 URL 打开，也不写日志或本地持久层。回查状态 7 后才提示医保缴费；响应未知但列表已是 7 可继续官方付款交接；仍是 1 或读取失败时在 120 秒内有限核对，之后“已锁号，医保准备结果需处理”。不重新锁号，不让初始化重试隐式延长医院截止时间。

- [ ] **实现已验证的手机付款入口。** 通知内容为“已锁号，请在医院期限内完成医保缴费”；点击进入 App 本单详情，展示“微信 → 北京佑安医院服务号 → 挂号结果查询 → 医保缴费”。官方结果页 `source=2,status=7` 调用 `sendYbPay()`，在手机重新请求 sxPayCN 并经 `wx-open-launch-weapp` 唤起医保小程序。App 不需要转移初始化返回的临时授权参数。第三方 APK 直达具体微信页没有已验证 deep link，首版提供准确操作步骤和打开微信入口；不得把打开微信主页说成已打开订单。全额付款仅在用户明确选择后按 status=1 的普通缴费路径处理。
- [ ] **实现同单付款查询。** 回到 App 或用户点刷新时，医保使用 POST `/order/queryRegisYbPayState`（userId,userIdKey,orderNo）并读取同单列表：查询 code=2 为待付/支付中；code=0 且同单 status=6 才显示医保支付完成。代码按端点保留各自状态码，不把初始化的 code=0 当付款成功。同单 6 但查询临时失败可显示“医院已预约，付款状态核对中”；只有查询成功但订单未更新时显示“支付成功，预约结果核对中”，继续有限只读回查，不创建新单。不使用 isyb=0 判定未选择医保；不从不熟悉的金额字段推导报销金额。
- [ ] **实现通知。** 使用高优先级普通本地通知，锁屏默认隐藏姓名/科室/医生；点入后显示本单金额、状态和医院 invalidtime。未知期限显示“请尽快到医院页面核对付款期限”，不补造 30 分钟。用户停止任务不调用取消或退款；有效待付款提醒仍可见。
- [ ] **验证交付。** MockWebServer 验证 `1 → 7 → 6` 和 `queryRegisYbPayState code=2 → 0`，状态 7 的入口为医保且无全额降级；初始化断流只发送一次；既有完成医保订单可用于用户授权后的只读回查，不重新付款。模拟通知→手机说明→付款回查完整运行，提交 `feat: 接入医保准备与官方结果页付款闭环`。医院协议与手机官方流程已有证据，本任务新增的是 Android 集成；真机验收另记，不能据旧流程直接勾选通过。

## Task 6：Mate 60 Pro 验收与交付记录

**Files:** 创建 `docs/testing/mate60-v01-acceptance.md`；更新 README 的实际构建/安装结果与限制。此任务不创建真实医院订单。

**Interfaces:** 消费 Task 1–5 的 APK、演示模式、真实只读入口；产出 APK SHA-256、设备/API 信息、测试结果矩阵和明确的未完成项。

- [ ] **记录构建与设备信息。**

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb install -r app/build/outputs/apk/debug/app-debug.apk
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

未发现设备时继续完成可本机验证项，设备行标“未执行”；不能写成通过。安装需手机正常授权 USB 调试。不要自动开启、修改系统时间、强停微信或清理用户数据；这些验收动作使用测试 App 的可逆操作和用户配合。

- [ ] **逐项验收并记录证据。**

| 条件 | 期望 |
| --- | --- |
| 时长设 1、30、60 分钟 | 显示与执行截止一致；60 分钟不会硬截在 30 分钟 |
| 长时间锁屏后放号 | 演示任务实际被唤醒，有前台通知；记录延迟，不能只截图倒计时 |
| 后台网络读取 | 用户导入后仅读本人已授权数据，核对返回与网络状态 |
| 网络在提交响应前断开 | 本地模拟服务已收到 1 次请求；恢复和重启仍不重发 |
| 重复闹钟和多次打开页面 | 同一 task generation 仅一个执行权、一个未决 attempt |
| 用户停止 / 到期 | 没有新提交，已发送的请求保留核对结果 |
| 会话失效 / 变换患者 | 显示需要接入，旧任务不会带新身份执行 |
| 手机重启 | 首次解锁后恢复未来任务；已经到期的不重新计时 |
| 拒绝权限 / 华为省电拦截 | 状态明确说明缺项，不假装“已就绪” |
| 微信付款后返回 | 普通同单 2；医保同单 6 且医保查询成功；无回跳也可主动刷新，不把 7 当成付款完成 |

- [ ] **形成准确交付。** 输出 APK 路径和哈希、可复现测试命令、通过/未执行/失败三类矩阵。模拟执行成功＋既有医院单笔证据不能合称“Android 自动预约真机全流程已通过”。已有普通与医保付款证据都直接复用；后续用户安排真实预约时再补 Android 集成验收，不重复验证医院接口可行性，不将未做的真机验收记为通过。
- [ ] **本地提交；明确授权后推送。** 提交仅本任务文件。按当前 AGENTS.md，不自动推送，开发、验证和本地提交不是推送授权。用户明确要求当次推送后，再 `git fetch origin`、核对祖先关系、必要时安全 rebase 并重跑受影响验证，随后执行 `git push origin HEAD:main`。远端主分支若有并行工作，保留其改动，不使用强推或覆盖。

```bash
git fetch origin
git merge-base --is-ancestor origin/main HEAD
git push origin HEAD:main
git ls-remote origin refs/heads/main
git rev-parse HEAD
```

上述推送命令仅在当次明确授权后运行；两次 SHA 相同才报告推送完成。rebase 前若有未提交的其它对话修改，不能自行丢弃或混入本次提交；先在本工作区保留备份和归属记录，再采用不覆盖的同步方式。

## 自审与评审结论

已将需求对应到任务：Task 1 条件和时长；Task 2 服务号会话和号源；Task 3 自动提交与恢复；Task 4 后台和锁屏；Task 5 通知与付款；Task 6 设备验收和交付。五类 Review Focus 均有对应测试。没有把服务号付款时限、互联网医院状态码或已有 macOS 证据误用为 Android 行为。

当前需要评审的是具体接入方式与执行计划，不再重复验证医院接口全流程。推荐原生逐任务执行，原因是会话、状态机和 Android 调度接口紧密关联，先在同一会话保持一致更直接；关键协议和崩溃恢复用独立测试约束。若用户选择多代理方式，再按任务边界分派并审阅，不在计划阶段启动代理。

计划修改前的设计备份与原有工作区 diff 位于 `/tmp/guahao-service-account-plan-20260924/`；恢复设计备份可撤回本轮细节补充，新计划是独立文件。文档回滚不会改变医院订单。
