package cn.guahao

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.guahao.ui.GuahaoApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { GuahaoApp(graph, intent.getStringExtra("taskId")) }
    }
    override fun onResume() {
        super.onResume()
        graph.scope.launch {
            runCatching { graph.recover() }
            graph.store.all().filter { it.order != null && it.phase != cn.guahao.core.TaskPhase.BOOKED }.forEach {
                runCatching { graph.refreshPayment(it.task.id) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { androidx.compose.runtime.key(intent.getStringExtra("taskId")) { GuahaoApp(graph, intent.getStringExtra("taskId")) } }
    }
}
