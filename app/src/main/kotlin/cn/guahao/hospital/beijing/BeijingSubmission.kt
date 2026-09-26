package cn.guahao.hospital.beijing

import cn.guahao.core.*
import cn.guahao.storage.SecretStore
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun interface BeijingMutationTransport { suspend fun executeMutation(request: BeijingQueryRequest): BeijingQueryReply }

internal object BeijingMutationPolicy {
    fun validate(request: BeijingQueryRequest) {
        require(request.channel == RegistrationChannel.JINGTONG && request.suffix == null)
        val body = requireNotNull(request.body)
        when (request.path) {
            "product/confirmV2" -> require(body.keys == setOf("hosCode", "firstDeptCode", "secondDeptCode", "dutyDate", "doctorCode", "uniqueProductKey", "uniqueProductTimeKey"))
            "auth/order/save" -> require(body.keys == setOf("patientId", "hosCode", "firstDeptCode", "secondDeptCode", "treatmentDay", "doctorCode", "uniqProductKey", "uniqueProductTimeKey", "cardNo", "cardType"))
            else -> error("Unsupported booking operation")
        }
        require(body.size <= 12 && body.toString().length < 16384)
    }
}

/** The caller must hold the channel operation mutex. A persisted send marker survives browser process death. */
internal class BeijingSubmission(private val reads: BeijingQueryTransport, private val mutations: BeijingMutationTransport,
    private val vault: SecretStore, private val now: () -> Instant = Instant::now) {
    suspend fun submit(selection: BeijingPatientSelection, account: BeijingAccountSnapshot,
        task: BookingTask, candidate: Candidate, maySend: suspend () -> Boolean): LockReply {
        require(!task.demo && task.condition.patient == selection.reference && selection.channel == RegistrationChannel.JINGTONG)
        val product = candidate.platform ?: error("Missing platform product")
        require(product.providerId == selection.channel.id && product.hospitalId == selection.hospitalId && eligible(task, candidate, now()))
        require(task.binding?.credentialVersionId == selection.reference.sessionId)
        val key = key(task)
        val fingerprint = fingerprint(task, candidate)
        // Return a receipt when known, otherwise stay uncertain. Never replay a sent request.
        vault.read(key)?.let { saved ->
            val record = Json.parseToJsonElement(saved).jsonObject
            if (record.optionalText("fingerprint") != fingerprint) return LockReply.OutcomeUnknown
            val id = record.optionalText("orderId")
            return if (id != null) LockReply.OrderCreated(id) else LockReply.OutcomeUnknown
        }
        if (!maySend()) throw HospitalException("任务已停止，未发送预约")
        if (!account.realNameVerified || account.accountId != selection.accountId || selection.patient.patientType != 0)
            throw HospitalException("请在京通官方页面完成实名与就诊人核验")
        for ((path, forbidden) in listOf(
            "auth/patient/nation/hos_switch" to true,
            "auth/patient/virtual_phone/hos_switch" to selection.patient.virtualPhone,
            "auth/patient/face_verify/hos_switch" to true
        )) {
            val enabled = BeijingReplyParser.data(reads.execute(BeijingQueryRequest(selection.channel, path, suffix = selection.hospitalId)))
            val flag = (enabled as? JsonPrimitive)?.booleanOrNull ?: throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
            if (flag && forbidden) throw HospitalException("本医院要求补充就诊核验，请在京通官方页面完成后处理")
        }
        val confirmation = buildJsonObject {
            put("hosCode", selection.hospitalId); put("firstDeptCode", candidate.department.parentCode)
            put("secondDeptCode", candidate.department.code); put("dutyDate", candidate.date.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("doctorCode", candidate.doctorCode); put("uniqueProductKey", product.productKey); put("uniqueProductTimeKey", product.productTimeKey)
        }
        if (!maySend() || !eligible(task, candidate, now())) throw HospitalException("已停止或超过原提交期限，未发送预约")
        val confirmed = BeijingReplyParser.data(mutations.executeMutation(BeijingQueryRequest(selection.channel, "product/confirmV2", confirmation))) as? JsonObject
            ?: throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
        val confirmedFee = runCatching { BigDecimal(confirmed.requiredScalar("totalFee")).movePointRight(2).longValueExact() }.getOrNull()
        if (confirmedFee != candidate.feeFen || confirmed.requiredText("doctorName") != candidate.doctorName ||
            confirmed.requiredScalar("regHalf") != candidate.half) throw HospitalException("京通确认页号源或费用已变化，请重新核对")
        if (listOf("takeTimeTips", "refundTips").any { !confirmed.optionalText(it).isNullOrBlank() })
            throw HospitalException("医院有需确认的取号或退号提示，请在京通官方页面处理")
        if (!maySend() || !eligible(task, candidate, now())) throw HospitalException("已停止或超过原提交期限，未发送预约")
        val body = buildJsonObject {
            put("patientId", selection.patient.id); put("hosCode", selection.hospitalId)
            put("firstDeptCode", candidate.department.parentCode); put("secondDeptCode", candidate.department.code)
            put("treatmentDay", candidate.date.format(DateTimeFormatter.BASIC_ISO_DATE)); put("doctorCode", candidate.doctorCode)
            put("uniqProductKey", product.productKey); put("uniqueProductTimeKey", product.productTimeKey)
            put("cardNo", selection.card.number); put("cardType", selection.card.medicareType)
        }
        vault.write(key, buildJsonObject { put("sent", true); put("fingerprint", fingerprint) }.toString())
        return try {
            val data = BeijingReplyParser.data(mutations.executeMutation(BeijingQueryRequest(selection.channel, "auth/order/save", body))) as? JsonObject
                ?: return LockReply.OutcomeUnknown
            if (data.requiredText("patientId") != selection.patient.id) return LockReply.OutcomeUnknown
            val id = data.requiredText("orderId")
            vault.write(key, buildJsonObject { put("sent", true); put("fingerprint", fingerprint); put("orderId", id) }.toString())
            LockReply.OrderCreated(id)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { LockReply.OutcomeUnknown }
    }

    fun receipt(task: BookingTask, candidate: Candidate): AsyncReply {
        val saved = vault.read(key(task)) ?: return AsyncReply.Unknown("NO_LOCAL_RECEIPT")
        val record = runCatching { Json.parseToJsonElement(saved).jsonObject }.getOrNull()
            ?: return AsyncReply.Unknown("INVALID_LOCAL_RECEIPT")
        if (record.optionalText("fingerprint") != fingerprint(task, candidate)) return AsyncReply.Unknown("RECEIPT_MISMATCH")
        return record.optionalText("orderId")?.let { AsyncReply.OrderFound(it) } ?: AsyncReply.Unknown("SENT_WITHOUT_RECEIPT")
    }

    private fun key(task: BookingTask) = "beijing-send-" + hash("${task.id}:${task.generation}")
    private fun fingerprint(task: BookingTask, candidate: Candidate) = hash(Json.encodeToString(task) + "\n" + Json.encodeToString(candidate))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
