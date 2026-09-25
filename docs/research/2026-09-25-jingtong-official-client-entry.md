# 京通官方发行包：医疗入口与接入决策

2026-09-25，本地研究；不推送。用户目标保持“通用平台、多家医院挂号”，不是仅提供链接或提醒。本轮无需手机操作，未安装客户端、未设置代理、未登录或提交预约。

后续实机更新：代理已修复，京通医疗网关在微信内拒绝用户 CA；官方页面可达排班，未解密取得业务接口。用户确认京通已登录而114未登录，两边页面相同，会话按入口隔离。详见[代理修复与信任核验](2026-09-25-proxy-repair-and-client-trust.md)。

## 本轮新增结果

**已从京通官方 1.0.6 发行包定位两个真实医疗入口，并完成无凭据请求；目前阻塞从“找不到入口”收敛为“缺少正常医疗鉴权上下文”。** 官方包的静态配置不是实时目录，网关返回也不是预约 API 成功。机器可读证据见 [jingtong-official-apk-entry.json](evidence/2026-09-25-beijing/jingtong-official-apk-entry.json)。

### 第一方来源链

1. [官方登录页](https://portal.bjt.beijing.gov.cn/p/login/login.html)引用的[下载二维码](https://portal.bjt.beijing.gov.cn/p/assets/imgs/qr-download-app2.jpg)，本轮在宿主机解码为[官方客户端引导页](https://image.jt.beijing.gov.cn/jingtong-secondary-page/index.html#/deepLinkHandler?accessDetail=34_J1_05&jumpUrl=jingtong://app.detail)。
2. 引导页实际引用[公开脚本](https://image.jt.beijing.gov.cn/jingtong-secondary-page/static/js/main.66e83989.chunk.js)，其中直接给出[官方 APK 下载入口](https://app.jt.beijing.gov.cn/api/file/external/jtfs/download/latest)。临时下载跳转签名不保存在证据里。
3. 下载返回 APK，79,277,331 字节；`aapt` 读取包名 `com.bjbdc.jingtong`、版本 `1.0.6`、versionCode `106000`。SHA-256 为 `e0151ba069eb890f95c93a38994e7479eb9a7fe093cd0bf0912a60ef1adbd828`。第一方来源链已核实；未独立核对签名证书主体，不声称做过完整软件审计。
4. 仅解析 APK 内原有 JSON 与公开资源；未执行包内代码，未取用客户端密钥或构造签名。APK 和全文资源留在 `/tmp`，不放入仓库。

### 医疗入口配置

| 配置 | 预约挂号 | 健康服务 |
| --- | --- | --- |
| 第一份匹配资源 | `assets/tnData/tem_1783_3127.json` | `assets/tnData/tem_1783_4101.json` |
| `appId` | `5dc3895dfaac4f22a2cc1408fdaf5f4a` | `2f1f8a4ce0be48d583fcad59cb3a1e3a` |
| `authLevel` | `L3` | `L3` |
| `caStatus` 解码含义 | `needLogin:true`，`path:appointmentRegisterHome` | `needLogin:true`，`path:jtHealthService` |
| `gwUrl` | [预约挂号网关](https://zyff.bjt.beijing.gov.cn/h5/bjswsjkwyhmryy/jkfwyyghscappdjqzdl) | [健康服务网关](https://zyff.bjt.beijing.gov.cn/h5/bjswsjkwyhmryy/jkfwscappdjqzdl) |

这里的 `appId` 是包内服务配置标识，**不能直接拿来充当统一身份登录页的 OAuth/clientId**。`L3` 的业务映射尚未证实，不擅自等同于某一种实名或人脸步骤。`needLogin` 证明这份客户端配置要求登录，不证明所有入口均禁止匿名医院查询。

### 实际网关返回

对上表两个原样 URL 各执行一次普通 GET，不带 Cookie、Token、签名或时间戳。均 HTTP 200，但正文是错误：

```json
{
  "errcode": "AGW.1433",
  "errmsg": "您好!您当前使用的手机、电脑等设备的时间与北京时间存在一定偏差，影响了您的访问，请您校准设备时间后再次访问，谢谢! （Request signature error or the difference between the request server timestamp and the actual time is greater than 180 seconds）"
}
```

结论是**裸请求没有通过网关校验**。英文信息同时包含签名错误和时间差两种条件；请求没有提交时间戳，不能把它诊断成电脑时钟故障，不因此改系统时间，也不靠猜签名继续请求。两个响应未包含医院、科室、排班或用户信息。

官方引导页脚本和 APK Manifest 均存在 `jingtong://app.detail` 路由；脚本可将 `detailUrl` 编入跳转链接。这只证明存在唤起官方 App 的机制，未实际验证医疗页面直达，更没有证据显示该机制会把医疗凭据或 API 授权返回给本 App。

## 公开实现交叉核查

通过 agent-reach 的 Exa 与 GitHub CLI 搜索、读取项目原始 README/元数据，未执行第三方代码。此次查到的线索如下：

| 项目 | 最后代码推送 | 原始说明 | 判断 |
| --- | --- | --- | --- |
| [hjwang1024/guahao114](https://github.com/hjwang1024/guahao114) | 2023-10-11 | 依赖 114 网站登录后手动获取 Cookie | 位于网站关闭之前，不作为当前实现依据 |
| [AndyXiaoyu/beijingguahao](https://github.com/AndyXiaoyu/beijingguahao) | 2022-06-14 | 旧 `bjguahao.gov.cn` 脚本 | 历史协议 |
| [uzdz/114_robot](https://github.com/uzdz/114_robot) | 2023-12-18 | 项目描述明确“渠道已关闭”，README 仍为网站 Cookie 路径 | 已明确失效的入口 |
| [johnshazhu/114_register](https://github.com/johnshazhu/114_register) | 2024-07-11 | 作者称公众号升级后参数加密，暂无法维护 | 是旧公众号实现，不能当作当前可用移动接口 |

这些项目的 `updated_at` 有的在 2026 年，不等于代码在 2026 年维护，也不等于接口仍有效。作者的加密说明只是社区项目维护状态，不能据此确定本次 TLS 失败机制。

另读取[腾讯健康“挂号 API 接入”原文](https://docs.wecity.qq.com/hospital/register.html)，明确写的是“从合作方接口获取数据”，医院/科室/号源多项标为“渠道提供”。这份接口是平台接收合作渠道的数据契约，不能当作本 App 直接消费北京 114 号源的开放 API。

## 路线决策（按用户最新指令修正）

| 路线 | 处理 | 原因 |
| --- | --- | --- |
| 114／京通官方页面的限定代理采样 | 继续，先修代理并验证恢复 | 用户明确要求；前次代理退出过早与客户端证书失败需要分别诊断，拒绝证书后停止解密并透传恢复 |
| 京通官方医疗入口＋正式支持的鉴权/第三方接入 | 保留为下一项技术前置条件 | 已找到真实入口和网关；但尚缺面向本 App 的授权方式、文档与正常只读回读 |
| 先做链接导航、提醒或只搭空适配器 | 不作为本次 v0.2 交付方案 | 无法满足用户要求的通用平台挂号能力，不擅自降级或转入开发 |
| 医院向平台提供数据的 HIS/渠道接口 | 排除为个人 App 默认路径 | 对接方向与参与主体不匹配，需要机构合作才可能适用 |

**下一项验收只有一个：证明本 App 可以按平台支持的方式获得医疗查询权限，正常读回同一平台两家医院的科室与排班。** 有效输入是正式第三方接入文档/SDK/申请渠道，或明确支持把授权结果交给本 App 的正常用户授权契约。只知道官方 App 自己能登录、能跳转，不满足条件。

本轮公开检索和发行包检查未找到上述第三方鉴权契约；也没有证据能判断平台是否接受合作申请。用户随后明确继续 114／京通限定抓包，并先解决代理故障，因此文档缺失不再作为停止研究的唯一理由。先分层验证传输、客户端信任与业务接口，再核验独立授权和失效恢复；未满足前不进入通用平台适配器实现或真实提交。

若需要向平台询问，可使用以下已准备的问题，**本轮未联系或发送给平台**：

1. 是否接受个人/第三方 Android App 申请北京统一预约平台的医院、科室与排班查询，以及预约接口？有哪些主体资格要求？
2. 京通医疗服务是否提供公开 SDK、OAuth 或其他用户授权契约？能否由用户在官方客户端确认后，将限定权限返回本 App？
3. 文档版本、测试环境、可用医院范围、请求频率与签名凭据申请方式是什么？
4. 是否允许本地定时查询和单次自动提交？提交幂等、未知结果查单、会话续期及用户验证的正式语义是什么？

## 对设计交付的判断

v0.2 的医院/渠道/会话隔离、能力开关、订单核对等设计可以评审；**通用平台技术可行性尚未通过，版本支持目标未完成，也未进入开发就绪状态**。当前不能承诺新增支持医院数。接入前置条件长期得不到解决时，应明确暂缓该能力，由用户决定是否调整版本范围，而非把辅助功能替代原目标。
