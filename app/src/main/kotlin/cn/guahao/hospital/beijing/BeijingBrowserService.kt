package cn.guahao.hospital.beijing

import android.app.Service
import android.content.Intent
import android.os.*
import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Private IPC accepts public queries or one fixed account read, with bounded concurrency. */
abstract class BeijingBrowserService : Service() {
    protected abstract val channel: RegistrationChannel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableMapOf<Int, Job>()
    private val endpoint by lazy { Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != applicationInfo.uid) return
            val id = message.arg1
            if (message.what == CANCEL) { requests.remove(id)?.cancel(); return }
            if (message.what !in setOf(QUERY, ACCOUNT_QUERY) || requests.containsKey(id)) return
            val receiver = message.replyTo ?: return
            if (requests.size >= 4) { respond(receiver, id, Bundle().apply { putString("failure", BeijingFailureKind.RATE_LIMITED.name) }); return }
            val input = message.data
            val operation = message.what
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = Bundle()
                try {
                    val runtime = BeijingBrowserRuntime.get(this@BeijingBrowserService, channel)
                    if (operation == ACCOUNT_QUERY) {
                        val account = Json.encodeToString(runtime.loadAccount())
                        if (account.toByteArray(Charsets.UTF_8).size > 196_608) throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                        result.putString("account", account)
                    } else {
                        val body = input.getString("body")?.let { Json.parseToJsonElement(it) as? JsonObject ?: throw IllegalArgumentException() }
                        val query = BeijingQueryRequest(channel, input.getString("path").orEmpty(), body, input.getString("suffix"))
                        BeijingQueryPolicy.validate(query)
                        val reply = runtime.execute(query)
                        // Binder has a shared transaction limit; never pass an unbounded raw response.
                        if (reply.body.toByteArray(Charsets.UTF_8).size > 196_608) throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                        result.putInt("code", reply.code); result.putString("type", reply.type); result.putString("body", reply.body)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { result.putString("failure", (e as? BeijingQueryException)?.kind?.name ?: BeijingFailureKind.INVALID_RESPONSE.name) }
                finally { requests.remove(id) }
                respond(receiver, id, result)
            }
            requests[id] = job
            job.start()
        }
    }) }
    override fun onBind(intent: Intent): IBinder = endpoint.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun respond(target: Messenger, id: Int, result: Bundle) {
        try { target.send(Message.obtain(null, RESULT, id, 0).apply { data = result }) } catch (_: RemoteException) { }
    }
    companion object { const val QUERY = 1; const val CANCEL = 2; const val RESULT = 3; const val ACCOUNT_QUERY = 4 }
}
class JingtongBrowserService : BeijingBrowserService() { override val channel = RegistrationChannel.JINGTONG }
class Official114BrowserService : BeijingBrowserService() { override val channel = RegistrationChannel.BEIJING_114 }
