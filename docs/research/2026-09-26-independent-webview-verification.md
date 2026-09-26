# 京通独立 WebView 登录与跨医院查询验证

日期：2026-09-26。**独立查询和官方手机号登录已通过；完整 v0.2 尚未交付。** 本轮以用户接回的 Mate 60 Pro / API 31 验证。结果不再依赖微信内置调试或复制微信登录态；[机器证据](../testing/evidence/v0.2/independent-webview-query.json)记录渠道差异和源码哈希。

## 已验证的路径

临时独立包 `cn.guahao.platformprobe` 使用系统 WebView `com.huawei.webview 114.0.5.302`、默认 UA、正常 HTTPS、JavaScript 与 DOM storage。两个渠道分别使用 `WebView.setDataDirectorySuffix`，不同进程运行；没有代理、测试 CA、微信 Cookie 导入、JS 原生接口、root、hook、改包或 TLS 校验关闭。

先打开官方京通医疗首页 `https://www.114yygh.com/newhlwyl/mobile/appointmentRegisterHome?pathchannel=jtwechat`，由页面自行建立正常浏览器环境，再执行普通同源 `fetch`，请求只包含公开 JSON 字段及 `Request-Source: JT_WECHAT`。无需复制、分析或复刻动态安全参数。

| 验证 | 结果 |
| --- | --- |
| 全新京通独立 WebView | 目录 HTTP 200 / `0000`，第一页 15 家；不据此宣称支持全部医院 |
| 人民医院 / 风湿免疫科门诊 | 科室、日历、医生排班均 HTTP 200 / `0000` |
| 中国医学科学院肿瘤医院 / 头外门诊 | 相同查询均成功；无号日医生为空，有号日返回 2 位医生 |
| 全新 114 独立 WebView / `WE_CHAT` | 目录与科室返回 `3087`，页面转向微信 OAuth；不能复用京通成功结论 |
| 京通官方手机号登录 | 官方 `/login?pathchannel=jtwechat` 正常显示手机号与验证码入口；用户自行完成登录并确认返回首页 |
| 登录持久性 | 临时包保留数据升级并重启后，`POST auth/user/get` 返回 HTTP 200 / `0000`，仅记录 `authenticated=true`，不保存身份值 |

宿主原生 HTTP 返回 202 / HTML 的[历史结论](../testing/evidence/v0.2/native-query-probe.json)仍成立，但不能再概括为所有独立客户端都不可用。当前可行路径是 App 拥有的官方 WebView 环境。114 的 `3087` 对应公开业务脚本中的 `UNAUTHORIZEDOTHER`，正常页面会转到 `/mobile-service/wechat/go`，不能改写渠道或复用别的登录态来消除该要求。

## 实际 Kotlin 代码真机验证

将项目里的 `BeijingQueryClient`、`BeijingQueryTransport`、`BeijingWebQueryTransport`、`BeijingDoctorSchedule` 原样复制进隔离临时包，使用同一已登录 WebView 执行查询、JSON 校验和 Kotlin 解析。正式挂号 App 没有被覆盖安装，现有任务和数据没有修改。

| 医院 | 子科室 | 日历天数 | 所选日期 | 医生 | 含价格时段 | 精确时间段 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| 北京大学人民医院 | 109 | 9 | 2026-09-26 | 10 | 15 | 17 |
| 中国医学科学院肿瘤医院 | 52 | 7 | 2026-09-29 | 2 | 3 | 3 |

这些是当次查询快照，不能作为未来余号保证。科室组数与子科室数不同。`fcode` 按官方显示为元，解析成分时要求精确整数；`ncode` 未提供时保持未知。`dutyTime` 包含完整日期，必须与所选日期一致。`uniqueProductKey` 和 `uniqProductKey` 分别保留，不混进佑安的候选参数。候补、未知状态与无号分开；尚未把这些产品转成可提交预约。

新增公开查询传输契约只允许目录、科室、日历、医生四类操作；拒绝认证、患者、订单或任意 URL。WebView 执行前核验官方来源和固定渠道；请求设超时、禁止重定向跟随与透明重试，取消后中止并清理临时结果。已接入正式 App 的官方登录 Activity 与只读查询服务，两个渠道各自独立进程；患者连接选择和自动任务仍未开放。跨进程真机验证单独记录。

## 独立进程与正常初始化对照

正式 App 新增每渠道独立进程、独立 WebView 数据目录、非导出连接 Activity 与查询 Service。主 App 只通过受限 IPC 发公开查询；浏览器进程不会初始化任务数据库或恢复任务。连接入口已加入医院选择页，患者绑定选择及自动任务尚未接通。

真实对照发现两个问题：首次 `onPageFinished` 后官方页面仍会继续重载，过早执行的脚本丢失；纯 Service 初始化的医生请求有时返回 HTTP 467。已用页面连续稳定检测处理前者；后者不绕过校验，要求用户在正常可见官方连接页完成初始化。

同一临时包正常显示官方首页 12 秒后返回 App，用同一个后台 WebView 通过 Binder 请求两院，目录、科室、日历与医生全部成功；随后移除诊断用每次医生查询额外等待，标准请求间隔仍通过。标准间隔结果文件时间为 1790384346；最终源码在“官方首页优先＋主动登录按钮”路径复验通过，结果文件时间为 1790384551，人民医院17个精确时段、肿瘤医院12个精确时段。人民医院剩余精确时间段从17降到16，是不同时间的库存快照。

这只证明同一进程、完成可见初始化后的后台查询；未验证锁屏、长时间后台或进程死亡自动恢复。正式实现遇浏览器进程重建要求重新进入官方连接页，不能用未验证的服务初始化继续自动任务。

## 后续协议事实与边界

官方公开脚本确认以下业务端点，其中患者列表与最近订单列表已获用户授权并完成只读核验，提交未调用：

- `GET auth/patient/list`：患者集合，公开界面使用 `patientList` / `hisPatientList`，含卡片列表。
- `POST product/confirmV2`：预约确认信息；是否存在资源占用副作用尚未实测，因此本轮未调用。
- `POST auth/order/save`：真实预约提交；参数包含患者、医院、科室、日期、产品、产品时段、卡类型/卡号等，响应由页面使用 `orderId` / `patientId`。
- `POST auth/order/getOrderListV2` 与 `POST auth/order/detail`：订单列表与详情。公开列表界面使用就诊人证件、时间范围、状态和分页条件，实际响应与同单匹配规则待核验。

公开代码里的重试逻辑不自动继承。提交超时、未知结果、规则校验、用户确认、实名/人脸验证等仍须按本项目“先同单核对、禁止重复提交”的要求实现。**用户已明确允许只读核验已绑定就诊人及最近订单。** 患者列表 HTTP 200 / `0000`，含 1 位患者、2 张卡；按该患者身份查询最近 30 天订单 HTTP 200 / `0000`，列表为空。只保存数量与字段类型；身份值仅用于 WebView 内存中的同源请求，不写日志或仓库。空列表无法验证订单归属、详情、状态与同单匹配，已询问用户是否有可供核验的历史订单。 没有实际就医条件，本轮不制造预约、付款或取消。

公开来源：

- [主业务脚本](https://img.114yygh.com/staticjtjk/mobile/assets/index.3a6153fa.js)
- [排班页面](https://img.114yygh.com/staticjtjk/mobile/assets/AppointmentRegister.3a6153fa.js)
- [登录页面](https://img.114yygh.com/staticjtjk/mobile/assets/LoginPage.3a6153fa.js)
- [患者接口](https://img.114yygh.com/staticjtjk/mobile/assets/index.3a6153fa10.js)
- [订单接口](https://img.114yygh.com/staticjtjk/mobile/assets/index.3a6153fa17.js)
- [预约确认页面](https://img.114yygh.com/staticjtjk/mobile/assets/OrderConfirm.3a6153fa.js)
- [订单列表页面](https://img.114yygh.com/staticjtjk/mobile/assets/AlternateRecord.3a6153fa.js)

## 环境恢复与剩余工作

微信 CDP 连接已关闭，本次 tcp:61951 映射已移除；未设置代理或 CA。用户开启的微信 inspector 已请求关闭；末次仍有2个调试 socket，尚待用户确认，不能称全部关闭。五个代理键均为 null，forward/reverse 均为空。临时包全部进程已停止，保留其独立登录数据供已授权的后续订单验证，不读取或导出 Cookie；结束验证后应卸载这一临时包。

正式接入仍需完成隔离 WebView 生命周期、连接身份持久化、患者/卡解析、提交结果和订单核对，再接入已批准四步 UI。API 26–27 不具备当前验证使用的数据目录隔离接口，官方连接入口显示版本限制，不让两个渠道共享默认 WebView。未完成这些工作前不启用自动任务、不推送为完整 v0.2、不通知 v0.3 开工。
