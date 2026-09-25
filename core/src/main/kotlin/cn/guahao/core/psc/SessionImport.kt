package cn.guahao.core.psc

import cn.guahao.core.PatientRef
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

class ImportedSession(val userId: String, val userKey: String, val ptno: String) {
    override fun toString() = "ImportedSession([redacted])"
}
@Serializable class PscSession(val reference: PatientRef, val userId: String, val userKey: String,
    val ptno: String, val ptnoKey: String, val patientName: String) {
    override fun toString() = "PscSession([redacted])"
}
/** Only fixed, credential-free copy may be shown to the user. */
class SessionLinkException(val safeMessage: String) : IllegalArgumentException(safeMessage)

fun parseSessionLink(raw: String): ImportedSession {
    fun invalid(): Nothing = throw SessionLinkException("请导入有效的佑安服务号 HTTPS 页面链接；请在微信中复制完整链接")
    val link = raw.trim()
    if (raw.length > 8192 || link.any { it <= ' ' || it == '\u007f' }) invalid()
    val uri = try { URI(link) } catch (_: Exception) { invalid() }
    if (uri.scheme != "https" || uri.host != "psc.hkinfo.net" || uri.port !in setOf(-1, 443) ||
        uri.rawUserInfo != null || uri.rawFragment != null) invalid()
    if (uri.rawPath !in setOf("/admin/youmanage", "/regis/initDept", "/regis/initRegis", "/regis/initRegisList"))
        throw SessionLinkException("暂不支持这个服务号页面，请进入「就诊服务 → 预约挂号」，确认就诊人后复制完整链接")
    val values = mutableMapOf<String, String>()
    for (pair in (uri.rawQuery ?: invalid()).split('&')) {
        val parts = pair.split('=', limit = 2)
        if (parts.size != 2) invalid()
        fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            catch (_: Exception) { invalid() }
        val key = decode(parts[0]); val value = decode(parts[1])
        if (key.isBlank() || values.containsKey(key) || key.any { it < ' ' || it == '\u007f' || it == '\uFFFD' } || value.any { it < ' ' || it == '\u007f' || it == '\uFFFD' }) invalid()
        values[key] = value
    }
    fun required(k: String) = values[k]?.takeIf { it.isNotBlank() }
        ?: throw SessionLinkException(if (k == "userIdKey")
            "链接缺少医院登录凭据，请在微信进入「预约挂号」后重新复制完整链接"
            else "链接缺少就诊人身份信息，请在微信进入「预约挂号」，确认就诊人后重新复制完整链接")
    return ImportedSession(required("userId"), required("userIdKey"), required("ptno"))
}
fun requireMatchingIdentity(imported: ImportedSession, returnedUserId: String, returnedPtno: String) {
    require(returnedUserId == imported.userId && returnedPtno == imported.ptno) { "医院返回的就诊人与导入身份不一致" }
}
