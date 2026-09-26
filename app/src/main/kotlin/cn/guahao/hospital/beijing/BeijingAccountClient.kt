package cn.guahao.hospital.beijing

import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable class BeijingAccountSnapshot internal constructor(
    val channel: RegistrationChannel,
    internal val accountId: String,
    val patients: List<BeijingPatient>
) {
    override fun toString() = "BeijingAccountSnapshot(redacted)"
}

fun interface BeijingAccountSource {
    suspend fun loadAccount(channel: RegistrationChannel): BeijingAccountSnapshot
}

/** Fixed authenticated reads. No caller can supply a private endpoint or arbitrary body. */
internal class BeijingAccountClient(private val transport: BeijingQueryTransport,
    private val intervalMillis: Long = 1000) : BeijingAccountSource {
    override suspend fun loadAccount(channel: RegistrationChannel): BeijingAccountSnapshot {
        require(channel.requestSource != null)
        val before = accountId(channel)
        delay(intervalMillis)
        val patients = BeijingPatientParser.parse(read(channel, "auth/patient/list"))
        delay(intervalMillis)
        if (before != accountId(channel)) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
        return BeijingAccountSnapshot(channel, before, patients)
    }

    private suspend fun accountId(channel: RegistrationChannel): String {
        val data = read(channel, "auth/user/get", buildJsonObject {}) as? JsonObject ?: invalid()
        return (data["userId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: invalid()
    }

    private suspend fun read(channel: RegistrationChannel, path: String, body: JsonObject? = null): JsonElement {
        val request = BeijingQueryRequest(channel, path, body)
        BeijingReadPolicy.validate(request)
        return BeijingReplyParser.data(transport.execute(request))
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}

internal object BeijingReplyParser {
    fun data(reply: BeijingQueryReply): JsonElement {
        if (reply.code == 429) throw BeijingQueryException(BeijingFailureKind.RATE_LIMITED)
        if (reply.code == 401) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
        if (reply.type.contains("text/html", true) || reply.code in setOf(202, 467))
            throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
        if (reply.code != 200 || !reply.type.contains("application/json", true) || reply.body.length > 1_048_576) invalid()
        val root = runCatching { Json.parseToJsonElement(reply.body) as? JsonObject }.getOrNull() ?: invalid()
        val code = (root["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        return when (code) {
            "0000" -> root["data"]?.takeUnless { it == JsonNull } ?: invalid()
            "0001", "3087", "102" -> throw BeijingQueryException(BeijingFailureKind.RECONNECT)
            "429" -> throw BeijingQueryException(BeijingFailureKind.RATE_LIMITED)
            else -> throw BeijingQueryException(BeijingFailureKind.BUSINESS)
        }
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
