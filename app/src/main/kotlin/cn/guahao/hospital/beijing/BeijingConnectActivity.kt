package cn.guahao.hospital.beijing

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cn.guahao.core.RegistrationChannel
import cn.guahao.R

/** Login/consent remain in the official page. No custom phone/password/OTP fields. */
abstract class BeijingConnectActivity : Activity() {
    protected abstract val channel: RegistrationChannel
    private var runtime: BeijingBrowserRuntime? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
            fitsSystemWindows = true
        }
        setContentView(column)
        column.addView(TextView(this).apply {
            text = getString(R.string.beijing_connection_intro, channel.title)
            textSize = 16f; setPadding(8, 20, 8, 20)
        })
        if (Build.VERSION.SDK_INT < 28) {
            column.addView(TextView(this).apply { setText(R.string.beijing_requires_android9) })
        } else {
            val current = BeijingBrowserRuntime.get(this, channel).also { runtime = it }
            (current.webView.parent as? ViewGroup)?.removeView(current.webView)
            column.addView(current.webView, LinearLayout.LayoutParams(-1, 0, 1f))
            if (savedInstanceState == null) current.showHome()
            column.addView(Button(this).apply {
                setText(R.string.beijing_official_login)
                setOnClickListener { current.showLogin() }
            }, LinearLayout.LayoutParams(-1, 56.dp))
        }
        column.addView(Button(this).apply { setText(R.string.beijing_return_to_app); setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, 64.dp))
    }
    override fun onDestroy() {
        runtime?.webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        super.onDestroy()
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
class JingtongConnectActivity : BeijingConnectActivity() { override val channel = RegistrationChannel.JINGTONG }
class Official114ConnectActivity : BeijingConnectActivity() { override val channel = RegistrationChannel.BEIJING_114 }
