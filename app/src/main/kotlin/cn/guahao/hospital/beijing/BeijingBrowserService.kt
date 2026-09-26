package cn.guahao.hospital.beijing

import android.app.Service
import android.content.Intent
import android.os.*
import cn.guahao.core.RegistrationChannel
import cn.guahao.core.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Private IPC accepts public queries or one fixed account read, with bounded concurrency. */
abstract class BeijingBrowserService : Service() {
    protected abstract val channel: RegistrationChannel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableMapOf<Int, Job>()
    private val permits = mutableMapOf<Int, CompletableDeferred<Boolean>>()
    private val endpoint by lazy { Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != applicationInfo.uid) return
            val id = message.arg1
            if (message.what == SEND_PERMIT) { permits.remove(id)?.complete(message.arg2 == 1); return }
            if (message.what == CANCEL) { requests.remove(id)?.cancel(); return }
            if (message.what !in setOf(QUERY, ACCOUNT_QUERY, ORDER_QUERY, SUBMIT, RECEIPT) || requests.containsKey(id)) return
            val receiver = message.replyTo ?: return
            if (requests.size >= 4) { respond(receiver, id, Bundle().apply { putString("failure", BeijingFailureKind.RATE_LIMITED.name) }); return }
            val input = message.data
            val operation = message.what
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = Bundle()
                try {
                    val runtime = BeijingBrowserRuntime.get(this@BeijingBrowserService, channel)
                    if (operation in setOf(ORDER_QUERY, SUBMIT, RECEIPT)) {
                        val selection = Json.decodeFromString<BeijingPatientSelection>(input.getString("selection") ?: error("Missing selection"))
                        require(selection.channel == channel && channel == RegistrationChannel.JINGTONG)
                        if (operation == ORDER_QUERY) {
                            val orders = Json.encodeToString(runtime.orders(selection, LocalDate.parse(input.getString("from")), LocalDate.parse(input.getString("to"))))
                            if (orders.toByteArray().size > 196_608) throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
                            result.putString("orders", orders)
                        } else {
                            val task = Json.decodeFromString<BookingTask>(input.getString("task") ?: error("Missing task"))
                            val candidate = Json.decodeFromString<Candidate>(input.getString("candidate") ?: error("Missing candidate"))
                            if (operation == RECEIPT) {
                                val receipt = runtime.receipt(selection, task, candidate)
                                if (receipt is AsyncReply.OrderFound) result.putString("orderId", receipt.orderNo)
                            } else {
                                val reply = runtime.submit(selection, task, candidate) { requestPermit(receiver, id) }
                                result.putString("submission", when (reply) { is LockReply.OrderCreated -> "created"; else -> "unknown" })
                                if (reply is LockReply.OrderCreated) result.putString("orderId", reply.orderNo)
                            }
                        }
                    } else if (operation == ACCOUNT_QUERY) {
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
                finally { requests.remove(id); permits.remove(id)?.cancel() }
                respond(receiver, id, result)
            }
            requests[id] = job
            job.start()
        }
    }) }
    override fun onBind(intent: Intent): IBinder = endpoint.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private suspend fun requestPermit(target: Messenger, id: Int): Boolean {
        val permit = CompletableDeferred<Boolean>()
        permits[id] = permit
        return try {
            target.send(Message.obtain(null, CHECK_SEND, id, 0))
            withTimeoutOrNull(3000) { permit.await() } == true
        } catch (_: RemoteException) { false }
        finally { permits.remove(id) }
    }
    private fun respond(target: Messenger, id: Int, result: Bundle) {
        try { target.send(Message.obtain(null, RESULT, id, 0).apply { data = result }) } catch (_: RemoteException) { }
    }
    companion object { const val QUERY = 1; const val CANCEL = 2; const val RESULT = 3; const val ACCOUNT_QUERY = 4; const val ORDER_QUERY = 5; const val SUBMIT = 6; const val RECEIPT = 7; const val CHECK_SEND = 8; const val SEND_PERMIT = 9 }
}
class JingtongBrowserService : BeijingBrowserService() { override val channel = RegistrationChannel.JINGTONG }
class Official114BrowserService : BeijingBrowserService() { override val channel = RegistrationChannel.BEIJING_114 }
