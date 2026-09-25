package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.storage.SecretStore
import kotlinx.serialization.decodeFromString
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reads encrypted identity only; usable during migration even if credentials have expired. */
class HospitalIdentity(private val vault: SecretStore) {
    companion object { private val keyGate = Any() }
    private fun index(vararg parts: String): String = synchronized(keyGate) {
        val stored = vault.read("identity-index-key") ?: Base64.getEncoder().encodeToString(ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }).also { vault.write("identity-index-key", it) }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(stored), "HmacSHA256"))
        mac.doFinal(parts.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun psc(session: PscSession): ConnectionBinding {
        val provider = "psc-youan"
        val account = index(provider, "account", session.userId)
        val patient = index(provider, "patient", session.ptno)
        return ConnectionBinding("beijing-youan", "北京佑安医院", provider, "微信服务号", account, patient,
            index("connection", "beijing-youan", provider, account, patient), session.reference.sessionId,
            SubmissionScope(provider, account))
    }
    fun resolve(ref: PatientRef): ConnectionBinding? {
        if (ref.isDemo) return demoBinding(ref)
        val session = vault.read("session-${ref.sessionId}")?.let { pscJson.decodeFromString<PscSession>(it) } ?: return null
        if (session.reference != ref || session.ptno != ref.patientId) return null
        return psc(session)
    }
}

fun demoBinding(ref: PatientRef): ConnectionBinding {
    require(ref.isDemo)
    val hospital = if (ref.sessionId == "demo-b") "b" else "a"
    return ConnectionBinding("demo-$hospital", "演示医院 ${hospital.uppercase()}", "demo-$hospital", "本机演示",
        "synthetic-account", ref.patientId, "demo-$hospital-${ref.patientId}", ref.sessionId,
        SubmissionScope("demo-$hospital", "synthetic-account"))
}
