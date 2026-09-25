# 手机微信内置调试：114 两院查询与京通协议对照

2026-09-25，仅本地。**已通过手机微信内置 WebView 调试读取真实查询响应：114 两家医院的科室、日期日历和医生排班均成功；京通内嵌页面使用相同 114 域名与查询端点。** 本轮解决了“不安装 CA 如何观察请求”的问题。独立 Android 客户端认证、请求构造和预约提交尚未验证。

证据：[xweb-read-query-verification.json](evidence/2026-09-25-beijing/xweb-read-query-verification.json)。之前的代理故障与用户 CA 限制保留为历史记录：[代理修复](2026-09-25-proxy-repair-and-client-trust.md)。

## 验证方式与链接错误

微信开放社区的[调试说明](https://developers.weixin.qq.com/community/develop/doc/0000c64a19c7983bd422391d261c00)在搜索索引中给出 `http://debugxweb.qq.com/?inspector=true`；正文读取未成功，因此它只是待验证线索。用户打开后截图显示 `ERR_NAME_NOT_RESOLVED`，宿主 DNS 查询也失败，不能声称页面跳转成功或链接已成功启用调试。

随后独立观察到 `webview_devtools_remote` socket；用户重新进入 114 后，`/json/list` 出现官方医疗页面，WebSocket 接受 `Network.enable` 并返回实际请求与响应。**调试能力由实际 CDP 连接和业务响应证明，DNS 错误与调试开启之间的因果关系未证明。** 此前仅因发现端口就称“入口已生效”的判断已更正。

链路为“手机微信业务页 → 已暴露的 WebView CDP → USB ADB 回环转发 → 本机脱敏监听”。无 HTTPS 代理、用户 CA、证书替换、客户端修改或 TLS 校验关闭。仅连接用户当前打开的官方医疗页面，不连接微信搜索、聊天或其它页面。

微信刷新可能销毁旧调试目标。首次监听连接成功后，用户刷新换了 target，未取得响应；修正为重新识别当前目标并自动跟踪新页面。对用户已经打开的排班页执行正常 `Page.reload`，没有调用预约、患者或订单接口。京通小程序使用另一 WebView 进程，需要单独识别已暴露的调试 socket。

## 实测结果

| 入口与医院 | 科室 | 日期日历条目 | 医生条目 | 业务结果 |
| --- | --- | --- | --- | --- |
| 114，北京大学人民医院 | 内科 → 风湿免疫科门诊 | 9 | 9 | 三类查询 HTTP 200、`code="0000"` |
| 114，医科院肿瘤医院 | 头颈及脑肿瘤 → 头外门诊 | 8 | 2 | 三类查询 HTTP 200、`code="0000"` |
| 京通，北京大学人民医院 | 内科 → 风湿免疫科门诊 | 9 | 9 | 三类查询 HTTP 200、`code="0000"` |

医院名称来自科室接口的公开 `hosName` 字段，科室来自 `firstDeptName`、`secondDeptName`。用户按“中国医学科学院肿瘤医院”搜索，接口显示“医科院肿瘤医院”，保留服务端名称。条目数是当时该页面的数据，不等于可预约名额、全部医生数或支持医院数。

用户明确 114 未登录、京通已登录；本轮沿用这一现场状态，未核对认证接口，也未导出凭据。114 可浏览公开查询不证明匿名客户端能直接调用全部 API，更不证明预约权限。京通通用门户登录也不能代替医疗服务端身份核对。

## 已识别的查询契约

共同域名：`https://www.114yygh.com`；共同业务前缀：`/jtjk/mobile-service`。

| 方法与路径（前缀略） | 已取得的结构 | 尚待明确 |
| --- | --- | --- |
| `POST /hospital/list` | 过滤条件、位置字段、`pageNo/pageSize/total`；响应 `count/branchCount/list`，一页 15 条 | 总量、完整分页、过滤枚举及默认值 |
| `GET /search/query` | 响应 `list[{code,name}]`、`healthServicelist` | 搜索参数经传输层处理，原始查询键尚未还原 |
| `GET /hospital/detail/{hospital}` | 医院详情 JSON 成功响应 | 远端医院标识、院区映射及字段语义 |
| `GET /department/list/{hospital}` | 一级科室及 `subList`，`code/name/parentCode/advanceDay/searchType` | 不同医院的层级例外与标识稳定性 |
| `GET /department/productDepartmentDetail` | 医院/科室名称、`bookRange/openTimeView/sameDayOpenTimeView` 等 | 查询参数构造与规则适用范围 |
| `POST /product/calendar` | JSON 请求 `hosCode/firstDeptCode/secondDeptCode`；响应日期、状态、刷新信息 | 状态枚举、无号与未放号区别、服务端时钟语义 |
| `POST /product/doctor/detail` | 医生、`detail/period`、日期、时段、`productStatus` 和号源标识结构 | 请求正文不是普通 JSON，额外转换尚未识别；号源状态/标识/费用仍待核对 |

这些是 **7 种核心查询端点的官方页面成功证据**。日期、科室、医生等基础字段已可进入适配器契约设计；未验证的语义继续标记未知。`autoSubmit` 等响应字段名称不构成自动预约授权或能力证据。

排班详情请求在观测层声明 `application/json`，但正文无法按 JSON 解析；只记录长度及“非普通 JSON”的类型，不保存原始正文、不猜加密算法。请求还带 `Request-Source` 和额外动态请求头/查询参数。本轮未保留或复现其值，不能把省略这些字段的 HTTP 请求称为可用客户端。

CDP `requestWillBeSent` 的 headers 不完整代表线上所有 Cookie；未收集 ExtraInfo，因此“未在此事件看到 Cookie”不作为无 Cookie 或匿名 API 证据。早期监听中页面自动调用 `/user/get`，只临时记录了字段类型，未取得个人字段值；该记录及遥测结构已移出交付证据，并将监听排除规则补齐。交付只含核心查询端点，标量值只保留业务成功码、公开医院/科室名和条目数量。

## 京通与 114 的关系

京通小程序实际打开：`www.114yygh.com/newhlwyl/mobile/appointmentRegister`。人民医院的科室信息、日历和医生排班三个端点，与 114 入口的方法、路径和响应结构一致，均 `0000`。这证明它们在本次样本中复用同一组对外查询 API；不证明后端内部部署完全相同、所有渠道能力相同或订单可跨入口互查。

设计采用**可复用的查询协议实现＋独立入口会话**：京通和 114 分别维护登录状态、凭据、失效恢复与身份/订单范围。既不重复实现已证实相同的查询解析，也不把京通登录传播到 114。

## 静态核对与下一项验证

实际页面加载的官方公开业务脚本：

- [index.3a6153fa.js](https://img.114yygh.com/staticjtjk/mobile/assets/index.3a6153fa.js)：读到上述科室、日历和医生排班的路径与 HTTP 方法定义，与现场一致。
- [AppointmentRegister.3a6153fa.js](https://img.114yygh.com/staticjtjk/mobile/assets/AppointmentRegister.3a6153fa.js)：确认排班页调用对应查询函数。

脚本哈希在 JSON 证据中，全文仅在临时目录；未提取密钥或复刻安全挑战。未实施独立请求重放。

下一项应在已取得的协议证据上核对请求参数、正文转换及正常认证，再制作只读最小验证器。独立验证器必须获得平台接受的正常会话/请求上下文，区分“官方页面请求成功”和“本 App 可独立查询”。随后才评估预约与同单回查，本任务继续保持设计与验证范围。

## 恢复与本地保留

采样进程已停止；本次两处 ADB forward 已删除并回读恢复原始状态，没有设置系统代理、安装 CA 或改变 DNS。内置调试开关的关闭结果以用户操作与 socket 回读为准，记录在 JSON 的 cleanup 中。临时脚本在 `/tmp/guahao-v02-xweb-20260925`，敏感会话值未写入文件，原始截图不复制到仓库。全部资料仅本地，不推送。
