package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.storage.EncryptedVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID

class SessionRepository(private val vault: EncryptedVault, private val networkGate: Mutex) {
    private val importGate = Mutex()
    private val transports = mutableMapOf<String, PscTransport>()
    @Synchronized fun transport(id: String) = transports.getOrPut(id) {
        PscTransport(PscCookieJar(
            { vault.read("cookies-$id")?.let { pscJson.decodeFromString<List<String>>(it) } ?: emptyList() },
            { vault.write("cookies-$id", pscJson.encodeToString(it)) }), networkGate)
    }
    fun current(): PscSession? = vault.read("current-session")?.let { id ->
        vault.read("session-$id")?.let { pscJson.decodeFromString<PscSession>(it) }
    }
    fun load(ref: PatientRef): PscSession {
        if (vault.read("invalid-${ref.sessionId}") != null) throw HospitalException("医院会话已失效，请重新接入")
        val s = vault.read("session-${ref.sessionId}")?.let { pscJson.decodeFromString<PscSession>(it) }
            ?: throw HospitalException("未找到就诊人会话，请重新接入")
        if (s.reference != ref || s.ptno != ref.patientId) throw HospitalException("就诊身份不一致，请重新接入")
        return s
    }
    fun invalidate(ref: PatientRef) = vault.write("invalid-${ref.sessionId}", "true")
    suspend fun importAndVerify(raw: String): PscSession = importGate.withLock {
        val input = parseSessionLink(raw)
        val id = UUID.randomUUID().toString()
        val http = transport(id)
        http.get("/regis/initDept", mapOf("userId" to input.userId, "userIdKey" to input.userKey, "ptno" to input.ptno))
        val list = http.post("/admin/getchargename", mapOf("id" to input.userId, "userIdKey" to input.userKey))
        if (list.text("code") != "0") throw HospitalException("未能验证就诊人，请重新从微信接入")
        val current = list.getValue("data").jsonArray.map { it.jsonObject }.singleOrNull {
            it.text("id") == input.userId && it.text("ptno") == input.ptno
        } ?: throw HospitalException("未找到导入链接对应的唯一就诊人")
        val reply = http.post("/patient/changePatient", mapOf("id" to input.userId, "oldId" to input.userId, "userIdKey" to input.userKey))
        if (reply.text("code") != "2") throw HospitalException("医院未返回有效就诊会话")
        val person = reply.getValue("data").jsonObject
        requireMatchingIdentity(input, person.text("id"), person.text("ptno"))
        val s = PscSession(PatientRef(id, input.ptno), input.userId, person.text("userIdKey"), input.ptno, person.text("ptnoKey"), current.text("name"))
        if (s.userKey.isBlank() || s.ptnoKey.isBlank()) throw HospitalException("医院会话凭据不完整")
        val access = http.post("/function/functionControl", mapOf("functionid" to "002", "ptno" to s.ptno, "ptnoKey" to s.ptnoKey))
        if (access.text("code") != "0") throw HospitalException("医院挂号功能暂不可用，请在微信确认")
        vault.write("session-$id", pscJson.encodeToString(s))
        vault.write("current-session", id)
        s
    }
}
