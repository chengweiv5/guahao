package cn.guahao.hospital.beijing

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.*
import androidx.core.net.toUri
import cn.guahao.core.RegistrationChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One channel and one WebView per private process; no credentials cross the process boundary. */
@SuppressLint("SetJavaScriptEnabled")
internal class BeijingBrowserRuntime private constructor(context: Context, val channel: RegistrationChannel) {
    val webView = WebView(context.applicationContext)
    private val transport = BeijingWebQueryTransport(channel, webView)
    val queries = BeijingQueryClient(transport)
    private var loaded = false
    private var loadFailure = false
    private var lastNavigation = 0L
    private var preparedVisibly = false
    private val queryGate = Mutex()
    val home = "https://www.114yygh.com/newhlwyl/mobile/appointmentRegisterHome?pathchannel=${channelParameter(channel)}"
    val login = "https://www.114yygh.com/newhlwyl/mobile/login?pathchannel=${channelParameter(channel)}&redirect=${Uri.encode(home)}"

    init {
        with(webView.settings) {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                loaded = false
                lastNavigation = android.os.SystemClock.elapsedRealtime()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return url.scheme != "https" || url.host != "www.114yygh.com"
            }
            override fun onPageFinished(view: WebView, url: String) {
                lastNavigation = android.os.SystemClock.elapsedRealtime()
                val parsed = url.toUri()
                loaded = parsed.scheme == "https" && parsed.host == "www.114yygh.com" &&
                    parsed.path?.startsWith("/newhlwyl/mobile/") == true &&
                    parsed.getQueryParameter("pathchannel") == channelParameter(channel)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadFailure = true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                loaded = false; loadFailure = true
                return false
            }
        }
    }

    fun showLogin() { preparedVisibly = true; loaded = false; loadFailure = false; webView.loadUrl(login) }
    fun showHome() { preparedVisibly = true; loaded = false; loadFailure = false; webView.loadUrl(home) }
    suspend fun prepare() {
        // A fresh service-only WebView was rejected by the official doctor endpoint.
        // Require normal visible initialization again after process death; do not silently retry it.
        if (!preparedVisibly) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
        if (!loaded) {
            loadFailure = false
            webView.loadUrl(home)
        }
        try {
            withTimeout(30_000) {
                // Normal official initialization may navigate several times. Do not inject into a document being replaced.
                while (!loaded || android.os.SystemClock.elapsedRealtime() - lastNavigation < 1000) {
                    if (loadFailure) throw BeijingQueryException(BeijingFailureKind.NETWORK)
                    delay(150)
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw BeijingQueryException(BeijingFailureKind.NETWORK)
        }
    }
    suspend fun execute(query: BeijingQueryRequest): BeijingQueryReply = queryGate.withLock {
        prepare()
        transport.execute(query)
    }

    companion object {
        // Intentional process-lifetime owner built with applicationContext; no Activity is retained.
        @SuppressLint("StaticFieldLeak") private var instance: BeijingBrowserRuntime? = null
        fun channelParameter(channel: RegistrationChannel) = when (channel) {
            RegistrationChannel.JINGTONG -> "jtwechat"
            RegistrationChannel.BEIJING_114 -> "wechat"
            else -> error("Unsupported browser channel")
        }
        fun get(context: Context, channel: RegistrationChannel): BeijingBrowserRuntime {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (Build.VERSION.SDK_INT < 28) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
            val suffix = when (channel) {
                RegistrationChannel.JINGTONG -> "beijing_jt"
                RegistrationChannel.BEIJING_114 -> "beijing_114"
                else -> error("Unsupported browser channel")
            }
            check(Application.getProcessName() == "${context.packageName}:$suffix")
            return instance?.also { check(it.channel == channel) } ?: run {
                WebView.setDataDirectorySuffix(suffix)
                BeijingBrowserRuntime(context, channel).also { instance = it }
            }
        }
    }
}
