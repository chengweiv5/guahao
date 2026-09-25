# 挂号 v0.1 Android 验收记录

2026-09-25。已实现首版代码并形成调试安装包，Mate 60 Pro 安装、4 项真机测试及实际演示付款提醒通过。**短时定时任务已执行，但请求前屏幕已亮，末尾灭屏断言失败；长时间锁屏和真实服务号 Android 集成尚未通过。** 原有普通/医保真实付款证据继续有效，本轮不重复占号。

## 安装包

- 应用：挂号，`cn.guahao`，`0.1.0`（versionCode 1）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，约 11 MB，Android Debug 签名，v2 签名验证通过。
- SHA-256：`5778ca9fd33c267aaf51d6c6baafcc5bc2fe017b4da4bb5b75f9fd24582ed32e`。
- minSdk 26 / targetSdk 36 / compileSdk 36；不依赖 Google 服务。目标手机已实测 Android API 31，详见下方真机首轮记录。
- 构建环境：macOS arm64，JDK 21.0.7（编译目标 17），Gradle 8.13，AGP 8.13.2，Kotlin 2.0.21。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 以下 connected 流程用于模拟器，可能在结束时卸载应用
./gradlew :app:connectedDebugAndroidTest
```

APK 不入库；同一机器可按上述命令重建。调试签名不是长期正式发布密钥。

## 已实现

- 暖白 / 深青绿 Compose 页面：任务首页、医院连接、三步创建、医生/科室搜索、执行与结果详情、记录、设置；眼科虚构演示，产品用语为“挂号任务”和“锁号成功，待付款”。
- 演示/真实通道显式选择。真实通道需用户主动导入 `psc.hkinfo.net` 官方页面链接，并经过同人列表、同人会话、功能校验；新导入不会覆盖旧任务凭据。
- 条件精确匹配（就诊人、科室、医生、日期、完整时段、费用）；时长默认 30 分钟，支持正整数配置；任务截止时间不会因为网络重试或重启重置。
- 串行医院请求，5 秒最小查号间隔，网络退避并遵守 Retry-After；提交 / 医保初始化关闭网络自动重试及跳转。
- Keystore AES-GCM 加密会话、Cookie、任务、订单与提交记录；SQLite 事务先持久化后提交，唯一执行权，备份和设备迁移禁用。正式窗口保留 FLAG_SECURE。
- 精确闹钟、前台通知、有限唤醒锁、停止入口、开机/时间变化/权限恢复后的重新调度。进程单例和数据库执行权防止重复执行。
- 提交响应不明时只核对、最多 120 秒；缺少唯一订单号不认领；同条件已有订单不重复锁号。人工只读核对和显式结束保留历史提交记录。
- 医保适用标记完整时最多一次初始化，特殊/零元/优惠/不适用情形交给用户；状态 7 不依据 isyb=0 降级；同单状态 6 加医保查询成功才显示医保完成。
- 本地隐私通知及微信操作指引；“打开微信”仅打开应用，未声称能直达服务号订单。医院付款截止按 invalidtime 展示，不用任务时长代替。

## 已通过的验证

| 检查 | 结果 / 证据 |
| --- | --- |
| 核心单元测试 | 28 项通过；条件/价格/时长、严格链接解析、号源余量、截止、重复执行、持久尝试、断流恢复、已有订单、时间回拨、支付策略与未知初始化 |
| Android 本机契约测试 | 6 项通过；严格锁号字段、单次 POST 断开响应、拒绝重定向、429 退避、Cookie 路径/安全/更新、状态 7 与医保入口 |
| 设备存储测试 | API 31 模拟器：随机 IV、AAD 绑定、加密重开、数据库重开保留尝试及基线、独占执行权通过 |
| 设备演示医保流程 | 模拟锁号 → 医保待付 → 模拟用户付款 → 同单完成通过；无医院网络请求 |
| 可见 UI 创建 | 三步创建并保存 60 分钟任务；开始/截止差恰好 3600 秒，详情可滚动；[界面](evidence/v0.1/task-detail.jpeg)已目视检查，主按钮白字修正后复查 |
| 灭屏定时 | 模拟器 API 31，放号计划 `01:28:28.551Z`，演示请求 `01:28:28.865Z`，延迟 314ms；屏幕仍关闭，已到医保待付；此前一轮为 233ms |
| lint | 0 errors，9 个 UseKtx 便利 API 建议，不涉及运行正确性 |
| APK | assembleDebug 成功，aapt 核对应用 ID/版本/SDK，apksigner 验证通过 |

共 **34 项单元测试 + 5 项设备测试，零失败**。设备为 `easy_trip_p60pro` AVD，Android 12 / API 31，arm64，显示 1073×2321（覆盖值），440 dpi。不是华为真机，也未进入长时间深度 Doze；短时灭屏结果不能等同于锁屏半小时后的联网保证。

结构化结果：[verification.json](evidence/v0.1/verification.json)。本机构建报告在 `core/build/reports/tests/`、`app/build/reports/tests/`、`app/build/reports/androidTests/connected/debug/`；模拟器附加产物在 `app/build/outputs/connected_android_test_additional_output/`。

## 真机首轮验收（2026-09-25）

实际设备：华为 `ALN-AL00`（Mate 60 Pro），鸿蒙 `4.2.0.223(C00E215R10P2)`，Android 12 / API 31；1260×2720，520 dpi，arm64。设备序列号不入库。

- **通过**：调试 APK 安装；首次冷启动返回 Status ok（966ms），收尾重新打开为 766ms；首页显示“挂号任务”“新建挂号任务”。APK 与原实现交付哈希一致，本轮未修改生产或测试代码。
- **通过**：直接运行 StorageAndRuntimeTest，`OK (3 tests)`，包括 Keystore 随机 IV/AAD、数据库重开与独占执行权、演示医保流程。
- **通过**：原始 `createSixtyMinuteDraftFromVisibleUi` 测试，`OK (1 test)`（2.324s）。三步创建并保存 60 分钟草稿，开始/截止差 3600 秒；[真机详情页](evidence/v0.1/mate60-task-detail.jpeg)已目视检查，眼科、虚构医生、60 分钟与白字主按钮可读。截图仅绘制本 App 虚构内容，未关闭 FLAG_SECURE。
- **通过**：系统设置中仅开启本 App 的通知开关并回读 POST_NOTIFICATIONS granted=true；execution、results 两个类别启用。实际读到 results 通知“演示 · 锁号成功，请在 10:23 前付款”，PRIVATE 可见性、公开文本为“解锁后查看详情”。App 运行准备页通知和精确定时均显示就绪。
- **部分行为通过、测试失败**：原始 `exactAlarmStartsServiceWithScreenOff` 在 15.958s 结束，前三个断言通过（已持久化 attempt、订单为 INSURANCE_PENDING、未提前提交）；最后 `assertFalse(device.isScreenOn)` 失败。任务已到 AWAITING_PAYMENT，不能将整个测试记为通过。

该短时测试的时序（北京时间）：

| 事件 | 时间 / 结果 |
| --- | --- |
| 系统完成灭屏 | 09:53:39.291，power_screen_state=0 |
| 计划放号 | 09:53:48.836 |
| 系统开始亮屏 | 09:53:50.711，screen_toggled=1 |
| 系统完成亮屏 | 09:53:51.180，power_screen_state=1 |
| 模拟提交记录 | 09:53:52.164，比计划晚 3328ms |
| 结果 | AWAITING_PAYMENT / INSURANCE_PENDING，screenOn=true |

计划放号时处于灭屏阶段，但模拟请求前已亮屏。现有日志未确定唤醒原因，不能归因为付款通知，也不能把 3328ms 直接认作 AlarmManager 唤醒延迟。测试时 USB 供电，系统已有 `stay_on_while_plugged_in=7`；未修改该设置，也未进入深度 Doze。原始时间证据：[mate60-alarm-evidence.txt](evidence/v0.1/mate60-alarm-evidence.txt)；限定电源事件：[mate60-screen-events.txt](evidence/v0.1/mate60-screen-events.txt)。

前序失败与复验：首次 UI 测试受锁屏/系统权限页干扰，曾观察到华为 BACKGROUND Activity 拦截；临时前台启动试验未解决且已撤回。处理通知授权后，原代码 UI 测试通过，不能再将该问题记为当前页面缺陷。授权前的闹钟测试被主动结束，Process crashed 是停止测试的结果；另一个 `uiautomator dump` 进程出现 `UiAutomationService already registered`，属于与 instrumentation 并发注册冲突，不是 App 自发崩溃证据。

精确闹钟、前台服务和唤醒锁均 granted。该鸿蒙设备虽报告 API 31，仍提供独立 POST_NOTIFICATIONS 权限；App 通过通知总开关能正确识别未授权。App 不在电池优化白名单；华为后台管理开关未自动更改。临时 `batteryAcknowledged` 已恢复 false 并回读；未清除应用数据、未卸载。虚构测试记录保留且明确标记演示。

共 **4 项真机 instrumentation 测试完整通过，1 项末尾灭屏断言失败**。不能与先前模拟器的 5/5 混为同一组结果。结构化记录：[mate60-first-pass.json](evidence/v0.1/mate60-first-pass.json)。本轮没有真实医院请求、锁号或付款。

真机复现命令（显式选择设备，避免影响同时连接的模拟器）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class cn.guahao.StorageAndRuntimeTest cn.guahao.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -r -e class 'cn.guahao.UiAndAlarmTest#createSixtyMinuteDraftFromVisibleUi' cn.guahao.test/androidx.test.runner.AndroidJUnitRunner
# 当前真机结果：业务状态已到待付款，末尾 screenOn=false 断言失败
adb -s <serial> shell am instrument -w -r -e class 'cn.guahao.UiAndAlarmTest#exactAlarmStartsServiceWithScreenOff' cn.guahao.test/androidx.test.runner.AndroidJUnitRunner
```

请先正常解锁、处理本 App 系统权限提示；不要在 instrumentation 运行中同时执行 `uiautomator dump`。真机直接运行 instrumentation，避免 Gradle connected 流程结束时卸载应用。

## 真机剩余验收

| 检查 | 后续操作与通过标准 |
| --- | --- |
| Mate 60 Pro 安装与 API | 已通过：ALN-AL00、鸿蒙 4.2.0.223、Android API 31，安装及冷启动成功 |
| 短时全程灭屏 | 已观察到自动执行和提醒；请求前屏幕已亮，需在用户确认的测试条件下复验，记录实际闹钟接收和亮屏原因 |
| 服务号独立会话 | 用户在手机主动粘贴本人页面链接；确认医院返回的姓名、ptno、功能权限一致 |
| 真实只读数据 | 科室/医生/号源及既有订单读取；确认 `actdate/ampm/reserved_date/invalidtime` 的实际格式。当前解析拒绝未知结构，不退化为空列表 |
| 长时间锁屏 | 演示任务设为至少 30 分钟后，锁屏等待；记录实际唤醒与通知。手机省电、自启动和后台联网以实测为准 |
| 网络变化 | Wi-Fi/移动网络切换、短时断网、超时、恢复；不延长原截止、不重发未决提交 |
| 回收和开机 | 系统回收后保留未决尝试；首次解锁前不运行凭据任务；解锁后恢复未来任务，过期任务不重计时 |
| 权限改变 | 拒绝/撤销通知、通知类别、精确闹钟、省电设置后，页面明确说明未就绪 |
| 微信返回 | 用户完成已有订单付款后，App 同单回查确认；未回跳时手动刷新可用 |

真实提交的 Android 集成验收只在用户安排下一次真实就医需求时进行。已打开手机“连接医院”页，等待用户主动粘贴本人的服务号链接；不从旧对话或微信私有数据自动导入。上表除安装/API 外尚未完成，不计入本轮通过数量。长时间锁屏、后台设置和网络切换需要用户配合安排测试时段。

## 实现取舍与风险

1. 订单与号源解析按已保存协议实现。未知日期/时段格式、字段缺失、身份冲突均停止并提示官方核对；首次真机只读数据可能需要补充合成契约。
2. 用户级异步 `2011` 不足以排除本次未决提交；仅锁号端点明确 `2/3` 才清理尝试并在剩余窗口重查。没有唯一匹配不会继续自动锁号。
3. 独立 APK 不能保证手机关机、强停、无网络或华为限制后台情况下执行。前台 specialUse 用于个人安装，商店上架需另评估。
4. 不包含云端任务、多医院、多医生并行、自动付款、自动取消退款；其他会话的后续规划与候选保持原状。

## 回滚

源码及本轮验收文档在独立分支本地提交；项目当前规则要求用户明确要求后才可推送，本轮未推送。如需撤回，对相应提交创建反向提交；只有获得当次推送授权才按快进规则交付，保留远端已有设计/调研。本轮文档修改前备份位于 `/tmp/guahao-mate60-acceptance/before-final-095704/`；原开发备份位于 `/tmp/guahao-v01-before-docs/`。撤回代码或停止任务不等于取消医院订单；卸载会删除本机加密数据，因此不作为默认回滚步骤。
