package cn.guahao.hospital.beijing

import cn.guahao.core.RegistrationChannel
import kotlinx.serialization.json.*

data class BeijingQueryRequest(
    val channel: RegistrationChannel, val path: String, val body: JsonObject? = null, val suffix: String? = null
)
data class BeijingQueryReply(val code: Int, val type: String, val body: String)
fun interface BeijingQueryTransport {
    suspend fun execute(request: BeijingQueryRequest): BeijingQueryReply
}

/** Shared by native and browser transports. This contract cannot express auth or booking mutations. */
internal object BeijingQueryPolicy {
    fun validate(request: BeijingQueryRequest) {
        require(request.channel.requestSource != null)
        when (request.path) {
            "hospital/list" -> require(request.suffix == null && request.body != null)
            "department/list" -> require(request.body == null && request.suffix?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true)
            "product/calendar", "product/doctor/detail" -> require(request.suffix == null && request.body != null)
            else -> throw IllegalArgumentException("Unsupported public query")
        }
        require((request.body?.toString()?.length ?: 0) <= 16_384)
    }

    /** Evaluated only in an App-owned official page, never in a different channel's browser session. */
    fun browserScript(request: BeijingQueryRequest, operationId: String): String {
        validate(request)
        require(operationId.matches(Regex("[a-f0-9]{32}")))
        val path = request.path + (request.suffix?.let { "/$it" } ?: "")
        val config = buildJsonObject {
            put("id", operationId); put("path", path); put("source", request.channel.requestSource!!)
            put("channel", if (request.channel == RegistrationChannel.JINGTONG) "jtwechat" else "wechat")
            put("body", request.body ?: JsonNull)
        }
        return """
            (function(c) {
              const page = new URL(location.href);
              if (page.origin !== 'https://www.114yygh.com' ||
                  !page.pathname.startsWith('/newhlwyl/mobile/') ||
                  page.searchParams.get('pathchannel') !== c.channel) return false;
              const jobs = window.__guahaoPublicQueries || (window.__guahaoPublicQueries = Object.create(null));
              if (jobs[c.id]) return false;
              const abort = new AbortController();
              const job = {abort: abort, result: null};
              jobs[c.id] = job;
              const timer = setTimeout(function() { abort.abort(); }, 25000);
              fetch('/jtjk/mobile-service/' + c.path, {
                method: c.body === null ? 'GET' : 'POST', credentials: 'same-origin', redirect: 'error',
                signal: abort.signal, headers: {'Content-Type': 'application/json', 'Request-Source': c.source},
                ...(c.body === null ? {} : {body: JSON.stringify(c.body)})
              }).then(async function(r) {
                const body = await r.text();
                job.result = body.length <= 1048576
                  ? {code: r.status, type: r.headers.get('content-type') || '', body: body}
                  : {error: 'invalid_response'};
              }).catch(function() { job.result = {error: 'network'}; })
                .finally(function() { clearTimeout(timer); });
              return true;
            })($config)
        """.trimIndent()
    }
}
