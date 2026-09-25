# 北京通用平台与四步挂号 UI 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留 v0.1 任务与连接数据的前提下，实现已批准的四步 UI、医院／渠道隔离和北京通用挂号平台接入。

**Architecture:** 核心任务引擎继续负责提交持久化与未决保护；医院目录与渠道能力独立于就诊人连接。UI 按医院／渠道、就诊条件、执行设置、确认启用分步，接入代码只使用真实证据确定的请求与响应。

**Tech Stack:** Kotlin、Jetpack Compose、OkHttp、kotlinx.serialization、SQLite／Android Keystore、JUnit／Compose UI tests。

**Spec:** [已批准 UI](../../design/ui/2026-09-25-v0.2-ui-design.md)、[平台设计](../../design/v0.2/2026-09-25-beijing-platform-design.md)。

## Global Constraints

- 用户已确认设计并明确“v0.1 已经完成了，不用等了”；本计划随实施更新，无需重复开工批准。
- v0.1.0 基线 `3d58fb0d0a108fd5169a3fd9add34ded27d28ce5`；保留已有任务、凭据、并行与恢复逻辑。
- 114 与京通凭据、就诊人、订单按渠道隔离；共享域名不能证明共享登录态。
- 日期排班与医生预选分开；上游条件变化清除下游选择；未知状态不能显示“无号”。
- 放号时间、就诊日期、任务执行窗口、付款期限分开；提交结果不明时先核对，不能透明重试。
- 不创建无真实就医需要的预约；不自动付款、取消、退款或绕过认证挑战。
- 完整开发验证后才快进推送与通知 v0.3；查询观察或 UI 完成不能代替平台接入完成。

## Review Focus

1. 同一医院两种渠道不能串用连接、就诊人或订单：目录能力按渠道匹配，身份比较测试覆盖。
2. 医院与患者切换后迟到的旧排班不能覆盖新选择：完整查询键取消旧请求，设备测试覆盖。
3. 未确认日期、未知放号时间、零号源仍须保留原意：回归日期状态与手动时间恢复测试。
4. 小屏／大字号时底部主操作可见且内容可滚动：360dp、412dp 和放大字体布局测试。
5. HTTP 成功但业务失败、HTML 挑战或非 JSON 回包不可当成功：独立客户端测试与真实只读核验分别记录。

---

### Task 1：四步任务编辑与清晰任务列表

**Files:** 修改 `app/src/main/kotlin/cn/guahao/ui/GuahaoApp.kt`；新增 `ui/TaskComponents.kt`；更新 `app/src/androidTest/kotlin/cn/guahao/DailyScheduleUiTest.kt` 与相关编辑流程测试。

**Interfaces:** 保留 `TaskEditorScreen(..., onSave: (BookingTask, Boolean) -> Unit)`、`DailySchedulePicker` 和 `AppGraph.saveDraft/enable`；新增 `TaskListCard(record, onClick)`、`EditorProgress(step)`、固定底部操作容器。

- [x] 把既有流程测试改成四步，固定验证以下路径：`医院与渠道 → 下一步 · 就诊条件 → 查询当日排班 → 下一步 · 执行设置 → 下一步 · 确认启用`。返回修改患者／日期后断言医生与医院自动时间失效。
- [x] 保留当前编辑器状态与查询取消机制，拆出第 1 步的医院、渠道、就诊人选择；后续三步沿用 v0.1 条件校验，主操作移出滚动内容。
- [x] 首页主操作固定在导航上方；卡片展示医院／渠道、状态、医生、就诊日期与下一步时间，详细查询信息留在详情。
- [x] 运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；模拟器跑编辑与日期 UI 测试，核验小屏与大字号。

### Task 2：平台目录、身份与能力边界

**Files:** 新增 `core/.../RegistrationPlatform.kt`、`core/.../RegistrationPlatformTest.kt`；新增 `app/.../hospital/beijing/BeijingQueryClient.kt`、`app/.../hospital/beijing/BeijingQueryClientTest.kt`；接入 `AppGraph.kt` 与 UI 选择器。

**Interfaces:** `RegistrationChannel` 区分 `YOUAN_WECHAT`、`BEIJING_114`、`JINGTONG`；`HospitalRoute` 固定医院／院区／渠道；`PlatformCapabilities` 单独标明目录、排班、认证、患者、提交和订单验证范围。未验证提交路径不会向引擎提供可提交号源。

- [ ] 核验公开业务脚本与官方端点，按证据建立查询请求，独立记录 HTTP／业务码及回包类型；遇到挑战停止该路径并记录，不伪造动态参数。
- [x] 编写渠道隔离、严格业务码、异常／HTML 回包、空目录、未知排班状态测试。
- [ ] 实现官方医院／科室／日历／排班解析，将真实独立查询与离线 fixture 结果明确区分。
- [ ] 核验两个新增医院；官方页面观察与原生客户端请求分别出具结果，不把目录数当支持医院数。

### Task 3：认证、提交与同单核对

**Files:** 按真实协议在 `app/.../hospital/beijing/` 增加会话及网关实现；修改 `AppGraph.kt` 路由与连接 UI；测试放入同路径的 unit tests。

**Interfaces:** 复用 `BookingGateway` 的 `binding`、`validateBookingAccess`、`orders`、`lock`、`querySubmission`；任何未确认提交返回 `LockReply.Unknown` 并进入已有核对流程。身份字段从官方成功认证响应获取，不能猜测或复用另一入口凭据。

- [ ] 使用官方正常认证流程确认凭据作用域、患者枚举与失效响应；只在证据充分时实现持久化，并保持加密与日志脱敏。
- [ ] 根据官方业务代码与脱敏实测证据实现订单匹配、状态与付款期限；测试同患者／日期／科室／医生匹配、重复订单和暂空列表。
- [ ] 提交请求禁止自动网络重试；测试丢失响应只进入核对、不重复提交；不同渠道不能接管旧任务。
- [ ] 缺少用户手机操作或实际就医条件时，记录具体阻塞并继续独立 UI／离线测试，不能宣布完整 v0.2 或触发下游。

### Task 4：集成验证和交付

**Files:** 更新 `README.md`、平台设计当前状态和 `docs/testing/2026-09-25-v02-implementation.md`；忽略目录 `.codex/TASK_STATE.md` 只记录本机执行状态。

- [ ] 运行 core、app debug/release unit、lint、assemble；模拟器验证四步编辑、状态、会话和并行回归。
- [ ] 真机测试使用保留数据的方式；不得清空应用、卸载或覆盖用户已有任务。
- [ ] 将实际实现、验证结果与仍未确认的能力分别写入验收记录。
- [ ] 全部验收满足后获取远端、确认祖先关系、`git push origin HEAD:main`，独立回读 SHA；向 v0.3 发送最终提交、验证与限制，回读收件结果。

## 当前执行记录

- 2026-09-25：v0.1 依赖解除；已读取最终代码、完成 rebase、创建备份分支与修改前文件备份，开始 Task 1／Task 2。

- 2026-09-26：已实现四步流程、固定操作区、医院筛选、院区身份兼容、渠道与目录 UI，以及独立只读目录／科室／日历客户端。113 项单元测试和双构建通过；真实目录原生请求返回 HTTP 202 / text/html，完整认证／医生详情／提交／订单仍阻塞。手机 USB 未连接，已向用户请求接回。
