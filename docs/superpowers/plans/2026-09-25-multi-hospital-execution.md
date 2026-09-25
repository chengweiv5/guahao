# 多医院并行执行 Implementation Plan

> 执行方式：按用户“继续实现”授权，由当前会话逐项实现与验证。设计已确认，不重复请求实施批准。

**Goal:** 不同医院任务可同时执行，同平台账户的未知提交结果仍持久阻止重复提交。

**Architecture:** 任务冻结医院、平台、匿名身份索引和凭据版本；数据库原子保存提交范围及尝试。后台服务按任务管理 worker，付款与结果通知各自独立。

**Tech Stack:** Kotlin、Coroutines、SQLite v2、Android Keystore、Compose。

**Spec:** `docs/design/2026-09-25-multi-hospital-connections.md`

## Global Constraints

- 第二家真实医院待定；只增加 debug 合成医院，不请求真实锁号或付款。
- PSC 结果接口仅按账户查询，提交范围采用 provider/account，不使用随机 sessionId。
- 未知结果不因超时、重新导入或进程重启释放；排队不延长任务原截止时间。
- 旧任务、订单、凭据保留；schema v2 不能用 v1 APK 直接降级。
- 已授权推送的连接修复先交付；后续实现本地提交供验证。

## Review Focus

- 同账户换凭据、不同就诊人仍共享 PSC 结果保护（任务 1、2）。
- 升级前未知身份/多笔未决不能丢失保护（任务 2）。
- 第一个 worker 结束时第二个 intent 到达不能关闭服务（任务 3）。
- 静默汇总不能截断独立结果的系统提醒（任务 3）。
- 只读恢复换凭据不能引发锁号或医保初始化（任务 4）。

### Task 1: 连接身份和目录

Files: `BookingModels.kt`, `HospitalIdentity.kt`, `SessionRepository.kt`, `DemoGateway.kt`, `AppMode.kt`。

- [x] 添加 `ConnectionBinding`、`SubmissionScope`；`BookingTask.binding` 默认 null 兼容历史。
- [x] 本机密钥 HMAC 生成 account/patient 索引；目录保存多逻辑连接及每版凭据；原 session 文件不改写。
- [x] 添加 debug 医院 A/B，所有请求按 PatientRef 路由；release 拒绝所有 demo 前缀。
- [x] 测试同账户新版本 scope 相同、不同平台 scope 不同、失效状态互不覆盖。

接口：`HospitalIdentity.binding(ref): ConnectionBinding?`；`SessionRepository.connections(): List<HospitalConnection>`；`select(ref)` 只更新 UI 默认连接。

### Task 2: 持久范围及并发 engine

Files: `BookingPorts.kt`, `BookingDatabase.kt`, `BookingEngine.kt`, core 与 Android 测试。

- [x] `TaskStore.beginSubmission(id, generation, attempt, now): Boolean` 在事务中检查 owner、原期限、已有未决范围，写入 attempt 后才返回 true。
- [x] schema v2 移除全局 owner 唯一索引，回填 binding 与 submission_scopes；无法识别的遗留记录持有平台通配范围。
- [x] 移除 engine 全局 Mutex；claim 仅同任务排他；NoStock、已关联订单、用户人工结案原子释放范围。
- [x] 使用 barrier 测试 A 阻塞时 B 仍进入提交；共享范围等待至原期限；重启及重导入不绕过未知结果。
- [x] SQLite v1 fixture 原位升级，逐项比较旧条件、尝试、订单、时间；多 owner 与保护重开后有效。

### Task 3: 并行后台服务和付款

Files: `AppGraph.kt`, `BookingService.kt`, `BookingNotifications.kt`, instrumentation。

- [x] 删除 enable 全局门禁，paymentGate 改为 task key。
- [x] 主线程管理 worker map、每任务 WakeLock；最后一个工作完成才 stopSelfResult；重复 intent 按 task/generation 去重。
- [x] 前台汇总使用静默渠道；结果使用 `(tag=taskId,id=固定结果ID)`，避免 hash 冲突，展示医院。先更新汇总再发结果。
- [x] 验证两个服务 intent、停止 A、B 完成与结果 PendingIntent 隔离；回归已有通知渠道属性。

### Task 4: UI 与显式恢复

Files: `GuahaoApp.kt`, `AppGraph.kt`, `PaymentPolicy.kt`。

- [x] 医院连接目录可选；任务编辑固定本次选择；任务详情读取自己的版本状态。
- [x] 新建页选择 debug A/B；卡片、详情和通知显示医院。
- [x] 同一 principal 新版本可显式替换尚未提交且未运行任务的凭据，保持时间并回到草稿。
- [x] 对未决/订单提供同身份新连接的只读核对，保留原 attempt/binding；不得调用 lock 或 initializeInsurance。

### Task 5: 交付验收

- [x] `./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --console=plain`。
- [x] 仅对隔离合成数据运行模拟器迁移、并行、通知/连接回归；安装采用 `adb install -r`。
- [x] 真机操作前检查是否有正在运行任务；不覆盖/删除用户任务、不发真实医院请求。具备条件时执行合成并行验收，锁屏可靠性单独标明。
- [x] 记录证据、迁移回滚边界、设计实现状态，本地提交；发送完成通知并精确回读。

源文件备份：`/tmp/guahao-parallel-before/source.tar`。代码回退使用本次实现的反向提交；数据升级后保留 v2 读取能力，不以恢复旧数据库覆盖新增业务。

完成说明：各步骤的实际证据及仍未验收的深度 Doze/真实医院边界见 `docs/testing/2026-09-25-multi-hospital-execution.md`。当前轮完成实施与本地验证，真实第二医院继续待定。
