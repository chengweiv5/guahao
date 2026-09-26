package cn.guahao.hospital.beijing

import android.content.*
import android.os.*
import cn.guahao.core.RegistrationChannel
import cn.guahao.core.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Binds only for one query. Unbinding or disconnecting never causes a transparent retry. */
class BeijingBrowserClient(private val context: Context) : BeijingPlatformSource {
    override suspend fun execute(request: BeijingQueryRequest): BeijingQueryReply {
        BeijingQueryPolicy.validate(request)
        val result = exchange(request.channel, BeijingBrowserService.QUERY, Bundle().apply {
            putString("path", request.path); putString("suffix", request.suffix); putString("body", request.body?.toString())
        })
        return BeijingQueryReply(result.getInt("code"), result.getString("type") ?: invalid(), result.getString("body") ?: invalid())
    }

    override suspend fun loadAccount(channel: RegistrationChannel): BeijingAccountSnapshot {
        val result = exchange(channel, BeijingBrowserService.ACCOUNT_QUERY, Bundle())
        val account = runCatching { Json.decodeFromString<BeijingAccountSnapshot>(result.getString("account") ?: invalid()) }.getOrElse { invalid() }
        if (account.channel != channel || account.accountId.isBlank()) invalid()
        return account
    }

    override suspend fun orders(selection: BeijingPatientSelection, from: LocalDate, to: LocalDate): List<OrderSnapshot> {
        require(!to.isBefore(from) && !to.isAfter(from.plusDays(366)))
        val result = exchange(selection.channel, BeijingBrowserService.ORDER_QUERY, Bundle().apply {
            putString("selection", Json.encodeToString(selection)); putString("from", from.toString()); putString("to", to.toString())
        })
        return runCatching { Json.decodeFromString<List<OrderSnapshot>>(result.getString("orders") ?: invalid()) }.getOrElse { invalid() }
    }

    override suspend fun submit(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate, maySend: () -> Boolean): LockReply {
        val result = exchange(selection.channel, BeijingBrowserService.SUBMIT, Bundle().apply {
            putString("selection", Json.encodeToString(selection)); putString("task", Json.encodeToString(task)); putString("candidate", Json.encodeToString(candidate))
        }, maySend)
        return if (result.getString("submission") == "created") LockReply.OrderCreated(result.getString("orderId") ?: invalid()) else LockReply.OutcomeUnknown
    }

    override suspend fun receipt(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate): AsyncReply {
        val result = exchange(selection.channel, BeijingBrowserService.RECEIPT, Bundle().apply {
            putString("selection", Json.encodeToString(selection)); putString("task", Json.encodeToString(task)); putString("candidate", Json.encodeToString(candidate))
        })
        return result.getString("orderId")?.takeIf { it.isNotBlank() }?.let { AsyncReply.OrderFound(it) }
            ?: AsyncReply.Unknown("JINGTONG_RECEIPT_UNKNOWN")
    }

    private suspend fun exchange(channel: RegistrationChannel, operation: Int, input: Bundle, maySend: () -> Boolean = { false }): Bundle = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < 28) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
        val service = when (channel) {
            RegistrationChannel.JINGTONG -> JingtongBrowserService::class.java
            RegistrationChannel.BEIJING_114 -> Official114BrowserService::class.java
            else -> error("Unsupported browser channel")
        }
        var bound = false
        var remote: Messenger? = null
        lateinit var connection: ServiceConnection
        val id = ids.incrementAndGet()
        try {
            withTimeout(if (operation != BeijingBrowserService.QUERY) 120_000 else 45_000) {
                suspendCancellableCoroutine { continuation ->
                    val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(message: Message) {
                            if (message.arg1 != id || !continuation.isActive) return
                            if (message.sendingUid != context.applicationInfo.uid) return
                            if (message.what == BeijingBrowserService.CHECK_SEND && operation == BeijingBrowserService.SUBMIT) {
                                val permitted = runCatching { maySend() }.getOrDefault(false)
                                try { remote?.send(Message.obtain(null, BeijingBrowserService.SEND_PERMIT, id, if (permitted) 1 else 0)) }
                                catch (_: RemoteException) { }
                                return
                            }
                            if (message.what != BeijingBrowserService.RESULT) return
                            val bundle = message.data
                            val failure = bundle.getString("failure")
                            if (failure != null) continuation.resumeWithException(BeijingQueryException(
                                BeijingFailureKind.entries.find { it.name == failure } ?: BeijingFailureKind.INVALID_RESPONSE))
                            else continuation.resume(bundle)
                        }
                    })
                    fun fail() { if (continuation.isActive) continuation.resumeWithException(BeijingQueryException(BeijingFailureKind.NETWORK)) }
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            remote = Messenger(binder)
                            try { remote!!.send(Message.obtain(null, operation, id, 0).apply {
                                replyTo = receiver
                                data = input
                            }) } catch (_: RemoteException) { fail() }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = fail()
                        override fun onBindingDied(name: ComponentName) = fail()
                        override fun onNullBinding(name: ComponentName) = fail()
                    }
                    try { bound = context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE); if (!bound) fail() }
                    catch (_: Exception) { fail() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw BeijingQueryException(BeijingFailureKind.NETWORK)
        } finally {
            try { remote?.send(Message.obtain(null, BeijingBrowserService.CANCEL, id, 0)) } catch (_: RemoteException) { }
            if (bound) context.unbindService(connection)
        }
    }
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
    companion object { private val ids = AtomicInteger() }
}
