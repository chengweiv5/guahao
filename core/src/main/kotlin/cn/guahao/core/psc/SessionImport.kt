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
fun parseSessionLink(raw: String): ImportedSession {
    fun invalid(): Nothing = throw IllegalArgumentException("请导入有效的佑安服务号 HTTPS 页面链接")
    if (raw.length > 8192 || raw.any { it <= ' ' || it == '\u007f' }) invalid()
    val uri = try { URI(raw) } catch (_: Exception) { invalid() }
    if (uri.scheme != "https" || uri.host != "psc.hkinfo.net" || uri.port !in setOf(-1, 443) ||
        uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath !in setOf(
            "/admin/youmanage", "/regis/initDept", "/regis/initRegis", "/regis/initRegisList")) invalid()
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
    fun required(k: String) = values[k]?.takeIf { it.isNotBlank() } ?: invalid()
    return ImportedSession(required("userId"), required("userIdKey"), required("ptno"))
}
fun requireMatchingIdentity(imported: ImportedSession, returnedUserId: String, returnedPtno: String) {
    require(returnedUserId == imported.userId && returnedPtno == imported.ptno) { "医院返回的就诊人与导入身份不一致" }
}
