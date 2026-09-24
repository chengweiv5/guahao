# 北京佑安医院挂号数据通路验证

最新进展（2026-09-24 23:36）：用户要求的微信服务号单笔预约已通过接口完成锁号，并在手机“挂号结果查询”可见；同一订单随后两次回查返回“已预约”。本笔服务号订单付款窗口约 30 分钟；此前互联网医院网页订单约 5 分钟。详见[单笔验证结果](2026-09-24-youan-service-account-booking-result.md)。以下早期静态分析和只读探针按各自时间保留，不能将其“未执行”理解为最新整体验证状态。没有开始 Android 产品开发。

医保补验修正：锁号后已实测一次 `POST /order/sxPayCN`，同一订单从 `status=1` 转为 `7`（医保未支付）。结果列表按状态分流：1 显示普通“缴费”，7 显示“医保缴费”并在手机重新取得授权参数。前轮漏查了 `sendYbPay()` 分支。随后医保支付查询返回成功，同一订单为状态 6（医保已支付／已预约），服务号医保付款闭环已取得后端证据。详见[医保分支核对](2026-09-24-youan-medical-insurance-payment.md)。

## 结论

独立 HTTP 客户端已走通互联网医院的短信登录、会话检查、已绑定就诊人读取、科室日期、医生号源和分时余号查询。服务号使用用户已有会话，也已取得当前同一就诊人凭据并通过挂号前功能校验。两个入口使用不同域名、不同认证协议，不能混用会话或订单参数：

| 渠道 | 后端 | 已实际验证 | 尚未验证 |
| --- | --- | --- | --- |
| 北京佑安医院服务号 | `https://psc.hkinfo.net` | 已有会话认证、就诊人列表、科室排班、分时余号；一次锁号与异步结果成功，手机可见同一订单，付款操作后回查“已预约” | 无已有凭据时微信初次授权、失效恢复、跨就诊人切换、异常幂等恢复、独立支付流水及 Android 真机；当前主闭环采用服务号 |
| 佑安互联网医院 App / 网页 | `https://aceso.bjhsyuntai.com/api/mobile` | 独立 HTTP 正常短信登录取得票据；已绑定就诊人、科室日历、医生明细和分时余号查询全部成功 | 真实会话有效期及恢复、Android 真机适配、独立 HTTP 锁号及支付；用户曾通过官方网页生成待支付订单，未完成该渠道付款 |

**已有服务号会话下，真实单笔锁号、手机订单可见与医院已预约状态回查已经验证；另一次医保单已取得支付查询成功及同单状态 6 的证据。** 这不包含全新微信认证、自动续期或 Android 后台能力；医保结算字段已有值，完整支付流水和医保明细票据尚未取得。早期查询样例不构成其他真实预约的授权。

## 微信服务号：直接 HTTP 实测

用户截图确认服务号「北京佑安医院服务号」，原始 ID `gh_f1c906bf0e7e`；用户在手机打开预约挂号后提供实际 `psc.hkinfo.net/regis/initDept` 链接。本报告不保存该链接的身份参数值。

| 请求 | 结果与证据 |
| --- | --- |
| `GET /regis/initDept`，携带用户提供的身份参数 | HTTP 200，标题为首都医科大学附属北京佑安医院；22 个一级分组、137 个二级映射条目。条目含专病门诊和重复映射，不是 137 个唯一科室 |
| `GET /regis/initRegis`，沿页面导航查询眼科 `1308` | HTTP 200；`regisInfo.dayViews` 返回 16 个日期状态，包括当天与尚未放号日，不能理解为开放 16 天预约 |
| 排班页内的 `registryList` | 包含医生、职称、价格、上下午、总余号、分时段余号与候补标记 |

查询样例（2026-09-24 约 22:32，眼科）：

| 日期 | 医生 | 时段 | 页面费用 | 余号 | 分时样例 |
| --- | --- | --- | --- | --- | --- |
| 2026-09-26 | 张薇，主治医师 | 上午 | 50 元 | 10 | 08:00–08:30：3；09:00–09:30：2；10:30–11:00：4 |
| 2026-09-26 | 张薇，主治医师 | 下午 | 50 元 | 22 | 13:00–13:30：5；15:30–16:00：3 |
| 2026-09-28 | 董宏伟，主任医师 | 上午 | 80 元 | 16 | 08:00–08:30：3 |

字段语义来自页面代码及数据：

- `syqty` 是日期状态，不是余号数量。代码将 `1` 显示为有号，`0` 为无号，`-2/-3` 进入待放号逻辑。
- `registryList[].count` 是医生该半日余号；`regHourList` 每项为 `[时段, 余号]`。
- `reg_half`：`0` 全天、`1` 上午、`2` 下午、`3` 晚间。
- `iscanceled=1` 在客户端进入候补确认流程，不能简单当作普通可用号源。
- `purpose` 对应「是否专程来京就医」，页面默认 `2`（否）。早期探测使用过 `1`，现已按默认 `2` 重读；持久证据采用重读结果。本次单笔服务号验证沿用官方默认 `2`，未另行确认该问题；产品启用任务时应明确展示并让用户核对就医方式，不将其硬编码为通用条件。

### 服务号业务接口与身份边界

以下为服务号接口。基础查询及会话已验证；23:33 起追加验证一次 `lockRegis`、`queryRegisStat` 和 `getRegisList`，同一订单返回“已预约”。付款由用户在手机完成；代理在后续医保验证中调用了医保初始化与支付状态查询接口，未执行授权付款。

| 方法与路径 | 用途 | 关键条件 |
| --- | --- | --- |
| `GET /regis/initDept` | 科室页面，内嵌 `deptOneList`、`deptTwoMap` | 身份参数 `userId`、`userIdKey`、`ptno`；本次沿用原域会话 Cookie |
| `GET /regis/initRegis` | 排班页面，内嵌 `regisInfo.dayViews` | 上述身份参数、`deptCode1/2`、`deptNm1/2`、`purpose`、`oriDeptTwo` |
| `GET /admin/youmanage` | 用户提供的上一级首页 | 返回带“切换就诊人”入口的页面；仅打开页面不代表获得客户端本地凭据 |
| `POST /admin/getchargename` | 列出当前会话的已登记就诊人 | `{id,userIdKey}`；实测 `code=0`，当前身份匹配；列表的 `ptnoKey/userIdKey` 值为空 |
| `POST /patient/changePatient` | 为选定已登记就诊人返回会话信息 | `{id,oldId,userIdKey}`；本次限定 `id=oldId=当前身份`，实测 `code=2` 且返回同一用户和同一就诊人及有效 `ptnoKey/userIdKey`；未验证跨人切换 |
| `POST /function/functionControl` | 提交前功能权限校验 | `functionid=002`、`ptno`、`ptnoKey`；缺凭据时 `-200`，使用会话返回凭据后 `code=0 功能开放` |
| `POST /regis/lockRegis` | 发起锁号处理 | 身份、科室、日期、医生、职称、时段、候补标记等；会产生业务影响 |
| `POST /regis/queryRegisStat` | 锁号后的异步结果查询 | 页面传入 `userId`；应关联本人刚发起的操作 |
| `GET /regis/initRegisList` | 预约结果页面 | 已读取页面协议；实际数据通过 `POST /regis/getRegisList` 查询 |
| `POST /regis/getRegisList` | 预约结果数据 | `ptno,ptnoKey,actdate,enddate`；本笔从 `status=1` 回查至 `status=2`，同一订单号匹配 |
| `/order/regisOrderSend`、`/order/sxPayCN`、`/regis/regisYbPayState` | 全额支付入口、医保初始化与手机接续 | 普通付款及医保初始化、手机服务号付款接续已验证；独立原生 App 直达/回跳未验证 |
| `POST /order/queryRegisYbPayState` | 医保支付状态查询 | 同单由 `code=2` 待支付转为 `code=0` 支付成功，与预约列表状态 6 一致 |

`lockRegis` 页面构造的 JSON 字段：`userId`、`userIdKey`、`dept_code1`、`dept_code2`、`purpose`、`dept_nm1`、`dept_nm2`、`reg_date`、`reg_half`、`reg_hour`、`doctor_code`、`title_type`、`iscanceled`、`doctor`、`dept_code2_from`。

**就诊人凭据来源已核实：** 排班页 `getPtnoKey()` 从客户端本地会话读取凭据；用户最初复制的链接没有提供它。后来用户提供 `/admin/youmanage` 上一级页面，其代码显示已登记就诊人会话接口会返回 `ptnoKey` 并由网页保存。本次使用原有有效用户凭据请求当前同一位就诊人，取得服务器返回值并通过功能校验；没有读取微信本地存储、猜测或自行生成凭据。`userIdKey` 初次取得与失效恢复仍未验证，Cookie 是否为每一步必需条件也未单独测试。

页面代码表明，`lockRegis` 返回接受处理后，还需 `queryRegisStat` 查询结果，再进入零元订单或待支付路径。**锁号接口返回 `code=0` 本身不能作为挂号完成证据。** 最终应依据对应订单号、预约状态和支付要求验证。

本次以静态 HTTP 读取 HTML，没有执行其脚本。页面包含第三方脚本引用；含身份的原始页面没有作为报告或浏览器成品外发。

### 早期服务号前置补验（22:55–22:59）

早期补验分为缺参和补全凭据两个阶段；在 22:59 时仅有前置查询证据。后续 23:33–23:36 的真实锁号与已预约回查见本文顶部单笔验证结果。

早期只读补验按页面真实调用，携带此前同源 Cookie 和用户提供的就诊人编号，向 `POST /function/functionControl` 发送 `functionid=002, ptnoKey=null`，复现从复制链接进入页面但没有本地就诊人凭据的状态。服务器返回 HTTP 200、`code=-200`；补读一次相同请求确认说明字段为 `name="参数不正确或缺少参数"`，`data=null`，不是 `msg` 字段。两次都是只读功能校验；没有调用锁号或订单接口。

这证明当前缺参状态无法通过客户端提交前校验，不证明服务号正常登录失败，也不能据此断言已有会话过期。仅凭该错误码不能确认服务端完整认证规则。

科室页和排班页都未写入 `localStorage` 中的就诊人凭据；排班页只通过 `getPtnoKey()` 读取它。直接读取站点根路径 `/` 返回“此功能正在维护，稍后发布”页面，没有可用登录入口。这只说明根路径不是当前可用入口，不代表整个服务号维护或不可用。

用户随后提供 `/admin/youmanage` 首页，并说明就诊人已提前登记。后续执行结果：

1. 首页 HTTP 200；从“切换就诊人”逻辑确认 `POST /admin/getchargename` 用于列举已登记就诊人。
2. 使用用户原先提供的 `userIdKey` 查询列表，返回 `code=0`，且含当前身份。只保存数量与字段类型，不保存患者原始字段值。列表中的两个凭据字段均为空。
3. 源码显示确认切换调用 `POST /patient/changePatient`，成功码为 `2`，响应提供就诊人和用户凭据。为避免改变就诊对象，本次仅请求 `id=oldId=当前身份`；服务器返回 `code=2`，已核对用户编号及就诊人编号均与原来相同，且两个凭据均存在。官方 UI 对同一选择通常不发请求，因此该测试证明接口接受“同一当前对象”，不等于已验证普通跨就诊人切换流程，也不证明医院承诺支持该行为。
4. 直接在进程内使用服务器返回的 `ptnoKey`，再次调用 `functionControl(functionid=002)`，得到 HTTP 200、`code=0`、`name=功能开放`。返回凭据未写入证据文件；进程结束后不再保留。
5. 随后使用原有用户凭据重读眼科排班，HTTP 200；9 月 26 日张薇 50 元，上午余 10、下午余 22，各分时数量合计与半日总量一致。与互联网医院 22:49 的同日同医生样例相符；不据此断言两渠道共享库存、配额或完全相同的放号规则。

尚需补验的是**全新会话入口与失效恢复**，不是重新登记就诊人。服务号不能直接使用互联网医院的短信票据。功能开放仅是客户端前置校验结果，不代表患者资料、就诊规则、最终号源和订单一定满足提交条件；当时锁号、订单状态与支付衔接尚未验证，后续已追加单笔实测。

静态订单流程已进一步核对：`lockRegis code=0` 表示开始处理；后续 `queryRegisStat code=2` 表示继续等待，`2011` 表示时段无足够号源，`0` 才进入订单后续分支。部分支付分支会调用医保小程序相关路径。因此一次提交结果不明时，不能直接切换渠道再提交；需要先查明原渠道订单，避免重复占号。其中成功分支随后已由真实单笔订单验证；失败与超时分支仍是静态语义。

## 互联网医院 App：HTTP 与登录后页面实测

| 验证方式 | 请求或页面 | 实测结果 |
| --- | --- | --- |
| 独立 HTTP | `GET /appm/current` | HTTP 200，`code=0 SUCCESS`，返回官方 App 信息 |
| 独立 HTTP | `GET /department/list` | HTTP 200，`code=0`，21 分组、105 末级科室 |
| 独立 HTTP | `GET /department/detail?deptCode=1308` | HTTP 200，`code=0`，眼科，所属五官中心 |
| 独立 HTTP，无登录票据 | `GET /source/dept/calendar?deptCode=1308` | HTTP 200，业务 `code=103 未登录`；不能将 HTTP 200 当查询成功 |
| 用户正常登录后的 IAB 页面 | `/youan/appv2/resource/list/department`，科室 `1044`，2026-09-29 | 上午陈新月 100 元、马丽娜 100 元，均约满；下午任姗 80 元余 17、张维 60 元余 24 |
| 独立 HTTP，短信登录所得票据 | `GET /patient/list` | HTTP 200，`code=0`，返回 1 位已绑定就诊人；证据仅保存数量与字段类型 |
| 独立 HTTP，短信登录所得票据 | `GET /source/dept/calendar?deptCode=1308` | HTTP 200，`code=0`，15 个日期状态，包含当天与待放号日 |
| 独立 HTTP，短信登录所得票据 | `GET /source/dept/detail?deptCode=1308&visitDate=2026-09-26` | HTTP 200，`code=0`，张薇，50 元，上午余 10、下午余 22 |
| 独立 HTTP，短信登录所得票据 | `GET /source/time/intervals`，上述上午号源 | HTTP 200，`code=0`，6 个分时段，余号合计 10，与半日明细一致 |

IAB 登录后曾出现空白页。登录代码在无跳转目标时执行返回操作，可能退回空白；手动打开科室页后恢复，用户确认可用。根因未独立复现，不将其定为已修复缺陷。

观察到一次日历与明细不一致：科室 `1044` 的 2026-09-28 日历显示「有号」，点击后显示「该日期下暂无可预约号源」；9 月 29 日显示上述真实明细。原因尚未定位。客户端应以明细及最终提交结果为准。

### App 内部接口与协议

| 方法与路径 | 客户端用途 | 验证等级 |
| --- | --- | --- |
| `GET /source/dept/calendar` | 科室日期，`deptCode` | 无票据拒绝、正常登录后 HTTP 查询成功均已实测 |
| `GET /source/dept/detail` | 当日科室号源，`deptCode,visitDate` | 正常登录后独立 HTTP 查询成功 |
| `GET /source/doctor/calendar`、`GET /source/doctor/detail` | 医生日历及当日号源 | 静态确认 |
| `GET /source/time/intervals` | 号源分时段，`deptCode,doctorCode,sourceCode,visitDate,periodType` | 正常登录后独立 HTTP 查询成功，数量合计已校验 |
| `POST /source/confirm` | 加载预约确认信息 | 静态确认，不是锁号提交 |
| `POST /source/locking` | 用户确认后锁号，返回订单号并进入订单详情/支付 | 未通过独立 HTTP 执行；后由用户在官方网页生成过待支付订单 |
| `POST /user-login/verify-code/send`、`POST /user-login/verify-code/login` | 手机验证码登录 | 用户通过本机探针主动完成；两次 HTTP 均 `code=0`，登录返回票据 |
| `POST /user-login/login/check` | 登录状态检查 | 带票据 `code=0, data.login=true`；移除票据后 `code=0, data.login=false`，必须判断布尔字段 |

请求头：`Hos-Code: 06154001`、`App-Code: 06154001_HUANZHEDUAN`、`Client-Channel: WEBSITE`、`Device-Id`、登录后的 `U-U-Ticket`。设备头按官方前端的毫秒时间戳 AES-128-ECB / PKCS7 / Base64 规则编码；直接传时间戳不兼容。缺头或错误编码导致的早期错误已纠正，不是当前阻塞。

`/source/locking` 使用 `deptCode,doctorCode,sourceCode,treatmentDate,treatmentPeriodType,timeIntervalCode,patientCode`。正常登录会话已可在用户 IAB 中运行；本次未抽取浏览器票据或将其复制到脚本。

### 独立客户端认证探针（22:49 已完成）

用户接受优先验证 App 通路后，已在 `/tmp/guahao-app-spike/probe.py` 建立一次性研究探针，仅监听本机。它通过官方 HTTP 接口重新登录，不读取已有 IAB 的 Cookie 或本地存储。此脚本是临时实验，不属于 Android 产品实现。

已实测 `POST /user-login/login/check` 在既无票据也无 Cookie 时返回 HTTP 200、`code=0`、`data.login=false`；`GET /patient/list` 无票据返回 `103`。脱敏前置证据为 `app-auth-preflight.json`。

官方网页静态协议：发送验证码使用 `{phone, verifyCodeType: "PHONE_LOGIN"}`；登录使用 `{phone, phoneVerifyCode}`，成功响应的 `data.ticket` 随后放入 `U-U-Ticket` 请求头。短信发送响应的 `expire` 被页面用于验证码按钮倒计时，不能直接当成登录会话有效期。

探针只允许短信发送、短信登录、登录状态检查，以及科室详情、科室日历、号源明细、分时段和就诊人列表读取。锁号、确认页和患者绑定路径均在本地禁止。手机号、验证码、票据、Cookie 和患者原始值不写入文件；患者证据仅保留数量与字段类型。

所检查的官方前端遇到业务 `103` 时，在网页环境跳回带有 `redirect` 的登录页，在原生 Flutter 环境调用登录桥。已下载的认证及公共脚本未发现刷新票据端点；这是有限源码观察，不能断言服务端不存在续期机制。真实过期时长、撤销行为及重复登录对其他设备的影响尚未测试。

用户在本机入口完成已有账号的短信登录后，探针按下列顺序执行 8 次请求，全部 HTTP 200、业务 `code=0`：

1. 发送登录验证码。
2. 提交验证码，响应包含 `data.ticket`。
3. 携带票据检查登录，`data.login=true`。
4. 读取已绑定就诊人，返回 1 位；具备 `patientCode`、`patientName`、默认标记、脱敏证件与手机等字段。仅验证列表读取，未验证患者资料完整性或提交资格。
5. 读取眼科日期日历，共 15 个日期状态。
6. 读取 2026-09-26 眼科号源：张薇，50 元；上午 `AM` 余 10、下午 `PM` 余 22。
7. 读取该上午号源的 6 个分时段，余号依次为 `3,0,2,0,1,4`，合计 10。
8. 本地移除票据后再次检查登录，`data.login=false`。

探针完成后丢弃票据、手机号与内存 Cookie；结果标记 `credentialsInMemory=false`。随后关闭临时页面及服务，并验证本机端口 57043 不再监听。未调用退出登录、撤销票据、患者绑定或预约写接口。“移除票据后检查未登录”只是本地缺凭据测试，不等同于服务端自然过期测试。

响应字段映射已可确定：日期为 `visitDate`；半日枚举为 `AM/PM`；医生及号源使用 `doctorCode/sourceCode`；价格 `price` 是字符串（样例 `50.00`，页面显示单位为元）；余号 `count` 是整数；分时段返回 `timeIntervalCode/timeIntervalView/count`。不同代码即使当前值相同也应分别保存，不通过医生代码拼接号源或时段代码。

同日两渠道眼科样例的价格与余号一致，但日期窗口并不一致：服务号早前快照包含 16 个日期状态，互联网医院本轮为 15 个；互联网医院 10 月 8 日标记待放号。不同时间、渠道的快照不能互相代替最终可预约判断。

完整脱敏请求账本、结构和业务样例见 `app-authenticated-http-verified.json`。本轮独立验证运行在 macOS Python 中，尚不是 Android 真机测试。

### 订单与支付接口补充：仅静态确认

为设计自动预约任务，进一步读取了官方前端的预约记录、订单详情和收银台脚本。以下业务接口本轮均未调用：

| 方法与路径 | 参数 | 源码表明的用途 |
| --- | --- | --- |
| `GET /order/page` | `patientCode,pageSize,pageNo` | 分页读取某就诊人的预约记录；官方页面默认每页 10 条 |
| `GET /order/detail` | `orderNo` | 读取订单状态、支付信息以及允许的操作 |
| `GET /cash/pay/info` | `transactionNo` | 支付状态、支付剩余时间与可选支付方式 |
| `GET /cash/pay/form` | `transactionNo,payOption` | 获取支付参数，随后由客户端平台支付模块执行；虽为 GET，也不能当作无业务影响的通用读取 |

订单状态在源码中出现 `PROCESSING`、`WAIT_PAY`、`BOOKING_SUCCESS`、`TAKE`、`NO_SHOW`、`STOPPED`、`CANCELLING`、`REFUNDING`。支付状态独立位于 `payPart.payStatus`；`controlPart` 包含 `payable/cancellable/deletable/needPolling`。这些是观察到的状态子集，不是完整状态机契约。

官方订单页按服务端 `needPolling` 继续查结果，支付剩余时间来自 `payRemainSeconds`；倒计时归零会回查服务端，不能仅凭客户端倒计时断言订单取消。收银台在获取支付参数后调用平台支付模块，因此已有网页 URL 并不能证明第三方 App 能直接完成微信或支付宝支付。

自动提交设计必须区分：提交请求发出、收到订单号、待支付、预约成功、结果未知。服务端锁号幂等保证尚未查明；如果提交超时或进程退出，应先核对订单，不能直接重复锁号。订单列表能否可靠匹配本次提交、延迟可见窗口和重复预约限制仍需实测。

静态来源：[订单详情脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-18940f3a.f935a8e2.js)、[预约记录脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-12058653.ca38e671.js)、[收银台脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-5ca25856.e1e63557.js)。哈希及字段证据见 `app-order-payment-static.json`。

## 对 v0.1 数据接入的判断

1. **可以进入数据层方案讨论。** 服务号使用 HTML 内嵌 JSON，App 通路使用 JSON API；读取真实数据已有实证。
2. **按用户最新要求，主闭环采用微信服务号接口锁号，再由用户在服务号“挂号结果查询”付款。** 普通单笔路径已实测；医保初始化后订单变为 7，官方结果列表另有“医保缴费”接续入口，同单后续为状态 6、医保支付查询成功；原互联网医院短信 API 保留为独立研究渠道；两渠道就诊人和订单可见性不保证同步。
3. 两个渠道的科室映射、会话、可见号源和订单需分别保存，不能仅因部分科室编码相同就认为两者完全等价。
4. 尚需验证：服务号初次认证、真实会话失效恢复、跨就诊人切换、异常幂等恢复、独立支付流水，以及 Mate 60 Pro / 鸿蒙 4.2.0 的定时唤醒、锁屏网络和后台行为。已有会话下单笔锁号与同一订单“已预约”已核对。
5. 这些是官方客户端内部接口，尚未找到开发者开放 API、稳定性承诺或沙盒环境。不能据此承诺向任意用户发布后都能稳定独立挂号。

用户已明确 v0.1 的首要用途为「按预设就诊条件自动提交预约」：提前设置就诊人、科室、医生、目标就诊日期和放号时间，手机锁屏或 App 后台时也要执行；取得待支付订单后停止查号并通知用户付款。最长运行时长可配置，默认 30 分钟，从放号时间开始计算；围绕一次放号事件执行，不做全天等退号。手机本地定时唤醒与前台服务、查询节奏、重试和结果核对规则见需求与实现方向讨论稿，技术方案尚待评审。没有启用定时自动任务；用户随后明确授权了一次服务号真实预约验证，结果另见单笔验证记录。该授权不延伸至其他号源或新任务。

后续每笔真实预约仍应完整约束就诊人、科室或医生、日期时段、就医方式和费用；本次验证不构成其他预约的默认选择。

## 来源与证据

1. [医院官网多渠道预约挂号指南](https://bjyayy.com.cn/Html/News/Articles/44135.html)。
2. [官网 App 二维码原图](https://bjyayy.com.cn/Sites/Uploaded/Image/2026/04/096391134773433742105234539.jpg)，本地解码为[官方分发页](https://aceso.bjhsyuntai.com/download/app/index?hosCode=06154001&appCode=06154001_HUANZHEDUAN)。
3. [分发页 JavaScript](https://aceso.bjhsyuntai.com/download/app/js/app.067d23da.js) 使用 `/api/mobile/appm/current`。
4. [腾讯应用宝入口](https://a.app.qq.com/o/simple.jsp?pkgname=com.bjhsyuntai.youan.patient)及[应用详情](https://sj.qq.com/appdetail/com.bjhsyuntai.youan.patient)确认医院主体与包名。正式 APK v1.4.11 大小 73,925,958 bytes，MD5 `de7b5e7b692ecf407a49ebbe81df5921` 与分发值一致；未安装执行。
5. APK 包含 `https://aceso.bjhsyuntai.com/api/mobile/` 与 [App 网页入口](https://aceso.bjhsyuntai.com/youan/appv2)。分发元数据当前报 1.4.7，应用宝报 1.4.11；实际静态分析后者。
6. 官方网页的[路由脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/app.9646b7fd.js)、[科室脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-4a7f63e8.5eeeb62e.js)、[预约确认及 HTTP 模块](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-38efd4d0.baa80d2a.js)、[登录脚本](https://aceso.bjhsyuntai.com/youan/appv2/js/chunk-4f9acd80.d9aaf892.js)。确认脚本 SHA-256：`21bad91003ed1a0cf440090efd36753a73ce3f58318b6ad372fb34df4ce75f1b`。
7. [官方隐私政策](https://static.bjhsyuntai.com/06154001/html/privacy.html)。
8. 服务号来源为用户手机提供的原始菜单链接及同源页面；报告只记录域名与路径，不公开带身份参数的链接。

本地脱敏证据位于 `evidence/2026-09-24-youan/`：

- `appm-current.json`、`youan-departments-verified.json`、`youan-calendar-detail-verified.json`：App 通路的实际 HTTP 响应。
- `psc-departments-verified.json`、`psc-schedule-verified.json`：服务号响应中的白名单业务字段，不含身份参数和 Cookie。
- `youan-iab-schedule-observation.json`：工具回读 UI 后整理的观察记录，明确区别于原始 API 响应。
- `app-auth-preflight.json`：无票据、无 Cookie 的登录检查，`login=false`。
- `app-authenticated-http-verified.json`：独立短信登录及带票据查询的脱敏账本和结构化样例；包含患者数量与字段类型，不含患者实际值和票据。
- `app-order-payment-static.json`：官方订单与支付脚本的来源、哈希、路径及状态字段；业务接口未执行。
- `psc-function-control-no-key.json`：服务号缺少 `ptnoKey` 时的功能校验请求摘要和错误回读，不含患者编号及 Cookie。
- `psc-patient-list-verified.json`：已登记就诊人列表查询的脱敏结构与身份匹配标记。
- `psc-current-patient-session-verified.json`：限定同一当前就诊人取得会话的结果，保存身份一致和凭据存在标记，不含凭据值。
- `psc-function-control-authenticated.json`：正常返回凭据后的挂号功能校验成功结果。
- `psc-post-auth-schedule-verified.json`：功能校验后的排班重读，与互联网医院样例对照。
- `psc-live-booking-verified.json`：单次锁号、异步结果、订单匹配、手机可见确认与两次“已预约”回查的公开技术摘要；不含个人资料、精确操作时刻、费用明细或订单号。

原始含身份 HTML、Cookie、订单明细及修改前备份只在本地保留，不纳入版本管理或公开交付。文档恢复不会撤销医院端会话或预约操作。研究阶段尚未实现 Android 产品或产品 UI；本机登录页面仅为临时研究入口。
