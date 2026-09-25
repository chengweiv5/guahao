package cn.guahao.runtime

import android.content.*
import cn.guahao.graph
import kotlinx.coroutines.launch

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId") ?: return
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                val r = context.graph.store.get(id)
                if (!context.graph.mode.allows(r.task)) {
                    context.graph.scheduler.cancel(r.task)
                    context.graph.notifications.cancelResult(id)
                    return@launch
                }
                if (intent.action == "cn.guahao.STOP") context.graph.stop(id)
                else {
                    val generation = intent.getLongExtra("generation", -1)
                    if (r.task.generation != generation) return@launch
                    if (!readiness(context, true).ready) {
                        context.graph.store.update(id) { it.copy(phase = cn.guahao.core.TaskPhase.NEEDS_ATTENTION,
                            note = "定时运行条件缺失，请检查通知、精确定时与后台设置；未发起新的提交") }
                        context.graph.notifications.attention(); return@launch
                    }
                    // A single delivered exact alarm drains every due task. Android may coalesce
                    // simultaneous allow-while-idle alarms; no hospital loses its wakeup.
                    val now = context.graph.clock.now()
                    val due = context.graph.visibleRecords().filter {
                        it.task.id == id || (it.phase == cn.guahao.core.TaskPhase.WAITING && !it.task.releaseAt.isAfter(now) && !it.stopRequested)
                    }
                    for (task in due) context.startForegroundService(Intent(context, BookingService::class.java)
                        .putExtra("taskId", task.task.id).putExtra("generation", task.task.generation))
                }
            } catch (_: Exception) { context.graph.notifications.attention() }
            finally { pending.finish() }
        }
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        context.graph.scope.launch { try { context.graph.recover() } finally { pending.finish() } }
    }
}
