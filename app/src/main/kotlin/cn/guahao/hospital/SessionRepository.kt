package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID
import java.time.Instant
import okhttp3.CookieJar

class SessionRepository(private val vault: SecretStore, private val networkGate: Mutex,
    private val now: () -> Instant = Instant::now,
    private val transportFactory: ((CookieJar) -> PscTransport)? = null) {
    private val importGate = Mutex()
    private val checkGate = Mutex()
    private val transports = mutableMapOf<String, PscTransport>()
    private val verifiedInProcess = mutableSetOf<String>()
    private val lastChecks = mutableMapOf<String, Instant>()
    @Volatile private var checkingId: String? = null
    @Synchronized fun transport(id: String) = transports.getOrPut(id) {
        val cookies = PscCookieJar(
            { vault.read("cookies-$id")?.let { pscJson.decodeFromString<List<String>>(it) } ?: emptyList() },
            { vault.write("cookies-$id", pscJson.encodeToString(it)) })
        transportFactory?.invoke(cookies) ?: PscTransport(cookies, networkGate)
    }
    fun current(): PscSession? = vault.read("current-session")?.let { id ->
        vault.read("session-$id")?.let { pscJson.decodeFromString<PscSession>(it) }
    }
    fun load(ref: PatientRef): PscSession {
        if (health(ref.sessionId).status == ConnectionStatus.RECONNECT_REQUIRED)
            throw HospitalException("医院会话需重新连接，请到设置导入微信中的新链接", reconnectRequired = true)
        val s = vault.read("session-${ref.sessionId}")?.let { pscJson.decodeFromString<PscSession>(it) }
            ?: throw HospitalException("未找到就诊人会话，请重新接入")
        if (s.reference != ref || s.ptno != ref.patientId) throw HospitalException("就诊身份不一致，请重新接入")
        return s
    }
    private fun health(id: String): ConnectionHealth {
        if (vault.read("invalid-$id") != null) return ConnectionHealth(ConnectionStatus.RECONNECT_REQUIRED)
        return vault.read("connection-$id")?.let { pscJson.decodeFromString<ConnectionHealth>(it) } ?: ConnectionHealth()
    }
    @Synchronized fun connection(): HospitalConnection {
        val s = current() ?: return HospitalConnection()
        val h = health(s.reference.sessionId)
        val verifiedAt = h.verifiedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val recent = verifiedAt != null && !now().isBefore(verifiedAt) && now().isBefore(verifiedAt.plusSeconds(300))
        val visible = if (h.status == ConnectionStatus.VERIFIED && (s.reference.sessionId !in verifiedInProcess || !recent))
            h.copy(status = ConnectionStatus.SAVED) else h
        return HospitalConnection(s, visible, checkingId == s.reference.sessionId)
    }
    @Synchronized fun recordAccess(ref: PatientRef, available: Boolean) {
        val previous = health(ref.sessionId)
        // Only a fresh import can replace a session which explicitly requires login.
        if (previous.status == ConnectionStatus.RECONNECT_REQUIRED) return
        val at = now().toString()
        val next = ConnectionHealth(if (available) ConnectionStatus.VERIFIED else ConnectionStatus.BOOKING_UNAVAILABLE,
            at, if (available) at else previous.verifiedAt)
        vault.write("connection-${ref.sessionId}", pscJson.encodeToString(next))
        if (available) verifiedInProcess.add(ref.sessionId) else verifiedInProcess.remove(ref.sessionId)
    }
    @Synchronized fun recordFailure(ref: PatientRef, failure: Exception) {
        if (failure is CancellationException) return
        val previous = health(ref.sessionId)
        if (previous.status == ConnectionStatus.RECONNECT_REQUIRED) return
        val status = if (failure is HospitalException && failure.reconnectRequired) ConnectionStatus.RECONNECT_REQUIRED
            else ConnectionStatus.CHECK_FAILED
        vault.write("connection-${ref.sessionId}", pscJson.encodeToString(previous.copy(status = status, checkedAt = now().toString())))
        verifiedInProcess.remove(ref.sessionId)
    }
    suspend fun checkCurrent(force: Boolean = false): HospitalConnection = checkGate.withLock {
        val s = current() ?: return@withLock HospitalConnection()
        if (health(s.reference.sessionId).status == ConnectionStatus.RECONNECT_REQUIRED) return@withLock connection()
        val at = now()
        val last = lastChecks[s.reference.sessionId]
        // Foreground checks are bounded; force is reserved for the user's Retry button.
        if (!force && last != null && !at.isBefore(last) && at.isBefore(last.plusSeconds(60))) return@withLock connection()
        lastChecks[s.reference.sessionId] = at
        checkingId = s.reference.sessionId
        try {
            PscClient(this).validateBookingAccess(s.reference)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordFailure(s.reference, e) }
        finally { checkingId = null }
        connection()
    }
    suspend fun importAndVerify(raw: String): PscSession = importGate.withLock {
        val input = try { parseSessionLink(raw) }
            catch (e: SessionLinkException) { throw HospitalException(e.safeMessage) }
        val id = UUID.randomUUID().toString()
        val http = transport(id)
        importStep("打开医院页面") {
            http.get("/regis/initDept", mapOf("userId" to input.userId, "userIdKey" to input.userKey, "ptno" to input.ptno))
        }
        val current = importStep("核验就诊人") {
            val list = http.post("/admin/getchargename", mapOf("id" to input.userId, "userIdKey" to input.userKey))
            if (list.text("code") != "0") throw HospitalException("未能验证就诊人，请重新从微信接入")
            val people = list["data"] as? JsonArray ?: throw HospitalException("医院返回的就诊人列表格式异常，请稍后重试")
            people.map { it as? JsonObject ?: throw HospitalException("医院返回的就诊人列表格式异常，请稍后重试") }.singleOrNull {
                it.text("id") == input.userId && it.text("ptno") == input.ptno
            } ?: throw HospitalException("未找到导入链接对应的唯一就诊人")
        }
        val s = importStep("获取就诊会话") {
            val reply = http.post("/patient/changePatient", mapOf("id" to input.userId, "oldId" to input.userId, "userIdKey" to input.userKey))
            if (reply.text("code") != "2") throw HospitalException("医院未返回有效就诊会话")
            val person = reply["data"] as? JsonObject ?: throw HospitalException("医院返回的就诊会话格式异常，请稍后重试")
            try { requireMatchingIdentity(input, person.text("id"), person.text("ptno")) }
            catch (_: IllegalArgumentException) { throw HospitalException("医院返回的就诊人与链接不一致，请在微信确认就诊人后重新复制链接") }
            PscSession(PatientRef(id, input.ptno), input.userId, person.text("userIdKey"), input.ptno, person.text("ptnoKey"), current.text("name")).also {
                if (it.userKey.isBlank() || it.ptnoKey.isBlank()) throw HospitalException("医院会话凭据不完整")
            }
        }
        importStep("校验挂号功能") {
            val access = http.post("/function/functionControl", mapOf("functionid" to "002", "ptno" to s.ptno, "ptnoKey" to s.ptnoKey))
            if (access.text("code") != "0") throw HospitalException("医院挂号功能暂不可用，请在微信确认")
        }
        importStep("保存医院连接") {
            vault.write("session-$id", pscJson.encodeToString(s))
            recordAccess(s.reference, true)
            vault.write("current-session", id)
        }
        s
    }

    private suspend fun <T> importStep(stage: String, block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: HospitalException) {
        throw HospitalException("$stage：${e.safeMessage}", e.retryAfterMillis, e.retryable, e.reconnectRequired)
    } catch (_: Exception) {
        // Exception messages, request URLs and response bodies can contain patient credentials.
        throw HospitalException("$stage 未完成，请稍后重试；若仍失败，请反馈此提示")
    }
}
