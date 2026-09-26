package cn.guahao.hospital.beijing

import android.webkit.WebView
import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Allowlisted reads in an already initialized, channel-isolated official WebView.
 * The owner controls its visible initialization/login and lifecycle. No Cookie export or JS interface.
 */
internal class BeijingWebQueryTransport(
    private val channel: RegistrationChannel,
    private val webView: WebView
) : BeijingQueryTransport {
    private val gate = Mutex()
    private var closed = false

    override suspend fun execute(request: BeijingQueryRequest): BeijingQueryReply = gate.withLock {
        require(request.channel == channel) { "Browser channel mismatch" }
        BeijingReadPolicy.validate(request)
        val id = UUID.randomUUID().toString().replace("-", "")
        try {
            withTimeout(30_000) {
                withContext(Dispatchers.Main.immediate) {
                    if (closed) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
                    if (evaluate(BeijingReadPolicy.browserScript(request, id)) != "true")
                        throw BeijingQueryException(BeijingFailureKind.RECONNECT)
                    var result: JsonObject? = null
                    while (result == null) {
                        delay(100)
                        if (closed) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
                        val raw = evaluate("JSON.stringify(window.__guahaoPublicQueries?.['$id']?.result ?? null)")
                        val string = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
                        if (string == null || string == "null") continue
                        if (string.length > 1_100_000) throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                        result = runCatching { Json.parseToJsonElement(string) as? JsonObject }.getOrNull()
                            ?: throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                    }
                    when ((result["error"] as? JsonPrimitive)?.contentOrNull) {
                        "network" -> throw BeijingQueryException(BeijingFailureKind.NETWORK)
                        "invalid_response" -> throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                    }
                    BeijingQueryReply(
                        (result["code"] as? JsonPrimitive)?.intOrNull ?: invalid(),
                        (result["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(),
                        (result["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Preserve external cancellation; only our own timeout becomes a network failure.
            currentCoroutineContext().ensureActive()
            throw BeijingQueryException(BeijingFailureKind.NETWORK)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!closed) webView.evaluateJavascript(
                    "(function(){const jobs=window.__guahaoPublicQueries;const j=jobs?.['$id'];if(j){j.abort.abort();delete jobs['$id'];}})()", null)
            }
        }
    }

    /** Call on the main thread before destroying/replacing the owning WebView. */
    fun close() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        closed = true
        webView.evaluateJavascript("(function(){const jobs=window.__guahaoPublicQueries;if(jobs){Object.values(jobs).forEach(j=>j.abort.abort());delete window.__guahaoPublicQueries;}})()", null)
    }
    private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "null") }
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
