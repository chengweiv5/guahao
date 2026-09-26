package cn.guahao.hospital.beijing

import android.content.*
import android.os.*
import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Binds only for one query. Unbinding or disconnecting never causes a transparent retry. */
class BeijingBrowserClient(private val context: Context) : BeijingQueryTransport {
    override suspend fun execute(request: BeijingQueryRequest): BeijingQueryReply = withContext(Dispatchers.Main.immediate) {
        BeijingQueryPolicy.validate(request)
        if (Build.VERSION.SDK_INT < 28) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
        val service = when (request.channel) {
            RegistrationChannel.JINGTONG -> JingtongBrowserService::class.java
            RegistrationChannel.BEIJING_114 -> Official114BrowserService::class.java
            else -> error("Unsupported browser channel")
        }
        var bound = false
        var remote: Messenger? = null
        lateinit var connection: ServiceConnection
        val id = ids.incrementAndGet()
        try {
            withTimeout(45_000) {
                suspendCancellableCoroutine { continuation ->
                    val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(message: Message) {
                            if (message.what != BeijingBrowserService.RESULT || message.arg1 != id || !continuation.isActive) return
                            if (message.sendingUid != context.applicationInfo.uid) return
                            val bundle = message.data
                            val failure = bundle.getString("failure")
                            if (failure != null) continuation.resumeWithException(BeijingQueryException(
                                BeijingFailureKind.entries.find { it.name == failure } ?: BeijingFailureKind.INVALID_RESPONSE))
                            else {
                                val body = bundle.getString("body")
                                val type = bundle.getString("type")
                                if (body == null || type == null) continuation.resumeWithException(BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE))
                                else continuation.resume(BeijingQueryReply(bundle.getInt("code"), type, body))
                            }
                        }
                    })
                    fun fail() { if (continuation.isActive) continuation.resumeWithException(BeijingQueryException(BeijingFailureKind.NETWORK)) }
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            remote = Messenger(binder)
                            try { remote!!.send(Message.obtain(null, BeijingBrowserService.QUERY, id, 0).apply {
                                replyTo = receiver
                                data = Bundle().apply { putString("path", request.path); putString("suffix", request.suffix); putString("body", request.body?.toString()) }
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
    companion object { private val ids = AtomicInteger() }
}
