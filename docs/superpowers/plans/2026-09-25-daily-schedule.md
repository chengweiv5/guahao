# 按日期查询排班与医生预选实施计划

> 执行方式：本任务直接实施。用户已审核 UI 与状态区分规则，并明确要求“继续实现”。

**Goal:** 将已确认的按日期选医生、未放号预选及独立号源状态落实到 Android。

**Architecture:** 医院查询返回日期状态、医生身份与具体号源三个部分。界面按目标日投影，任务保存最近一次排班观察；执行时重新查询，继续沿用原有单笔提交、订单核对和付款流程。

**Tech Stack:** Kotlin、Compose、kotlinx.serialization、JUnit、Android instrumentation。

**Spec:** [已确认设计](../../design/ui/2026-09-25-daily-schedule-ui.md)

## 全局约束

- 未放号、当日无号、未知、查询错误不得混同；空医生列表不能用来推断状态。
- 医生预选只使用医院返回的本科室身份；不冒充目标日费用、时段或库存。
- 精确匹配原医院、科室、日期、医生、时段、费用上限；不替换条件。
- 兼容已有加密任务数据，不变更数据库表结构；不清除用户数据。
- 不推送，不恢复宣武接入，不提交真实挂号或付款。

## 重点检查

1. 未放号和无号同时出现空列表：分别投影为两个状态。
2. 医生存在但分时段为空：仍可确认身份，不伪造可挂号源。
3. 换日期、科室、就诊人或就医目的期间旧查询返回：不得回填旧医生。
4. 预选医生只在别的日期有排班：目标日未确认，执行不越界。
5. 旧任务 JSON 没有新字段：正常加载，新状态默认未知。

## 实施与验收

- [x] 核心查询模型：新增 `ScheduleQuery`、`DoctorRef`、`DepartmentSchedule` 和独立 `DateAvailability`；`PscPageParser` 从原始日期状态映射，保留零时段医生。新增 `ScheduleTest` 覆盖上述 1、2、4、5，再运行 `:core:test`。
- [x] 医院与执行链：`BookingGateway.schedule(query)` 由 `PscClient`、`DemoGateway` 和路由实现；`BookingEngine` 保存最近状态，禁止未放号/无号状态下提交，即使响应同时含旧库存。`AppGraph.enable` 核验整个科室医生身份。引擎回归覆盖状态变化、未匹配不提交、截止时间和单笔提交保护。
- [x] 编辑器：新建 `DailySchedulePicker.kt` 承载日期结果和医生卡片；`TaskEditorScreen` 按科室→日期→查询→医生排列。以查询条件为 Compose key，取消离开条件的查询；父级清空选择。确认页、详情和任务卡保留最近查询状态及排班待确认信息。
- [x] 界面验证：新增合成数据 Compose 测试，覆盖未放号预选、无号分支、换日期后禁用下一步、查询中换条件。更新现有演示和只读 live 查询测试以适应新顺序。
- [x] 构建 `:core:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease`。可用设备使用 `adb install -r` 保留数据；有活动任务则先不安装。仅执行无真实提交的验收。
- [x] 更新验证记录、TASK_STATE、通知并回读。本地提交仅包含本次实现；保留独立的宣武研究修改，等待用户另行授权推送。

## 回退

修改前源码在 `/tmp/guahao-daily-implementation-20260925/before`。回退使用本次提交的反向变更；安装前备份旧 APK，必要时在无活动任务时覆盖安装，不卸载或恢复旧任务数据。

验收结果见 [实现验证记录](../../testing/2026-09-25-daily-schedule.md)。核心 46/46，应用单测 debug/release 各 24/24；真机 4 项合成 UI、1 次真实查询和 3 次真实编辑器验收通过。仅本地交付，未推送。
