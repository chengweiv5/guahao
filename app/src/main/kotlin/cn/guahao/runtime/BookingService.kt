package cn.guahao.runtime

import android.app.Service
import android.content.Intent
import android.os.*
import android.content.pm.ServiceInfo
import cn.guahao.graph
import cn.guahao.core.*
import kotlinx.coroutines.*
import java.util.UUID
import java.time.Duration

/** Worker registry and foreground lifecycle are confined to the main dispatcher. */
class BookingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workers = mutableMapOf<String, Job>()
    private val wakeLocks = mutableMapOf<String, PowerManager.WakeLock>()
    private var updates: Job? = null
    private var latestStartId = 0
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val notification = graph.notifications.runningSummary(activeRecords())
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        val id = intent?.getStringExtra("taskId")
        if (id == null) {
            scope.launch { withContext(Dispatchers.IO) { graph.recover() }; finishIfIdle() }
            return START_REDELIVER_INTENT
        }
        // Duplicate and redelivered intents do not displace existing work.
        if (workers.containsKey(id)) return START_REDELIVER_INTENT
        val generation = intent.getLongExtra("generation", -1)
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            var result: TaskRecord? = null
            try {
                val r = graph.store.get(id)
                if (!graph.mode.allows(r.task)) { graph.scheduler.cancel(r.task); return@launch }
                if (r.task.generation != generation || !readiness(this@BookingService, true).ready) return@launch
                val limit = if (r.order != null) (r.insuranceStartedAt ?: graph.clock.now()).plusSeconds(120)
                    else r.attempt?.sentAt?.plusSeconds(240) ?: r.task.deadline.plusSeconds(240)
                val duration = Duration.between(graph.clock.now(), limit).toMillis().coerceAtLeast(1000)
                wakeLocks[id] = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guahao:bounded-booking").apply { acquire(duration) }
                withContext(Dispatchers.IO) {
                    graph.runTask(id, generation, UUID.randomUUID().toString())
                    if (graph.store.get(id).order != null) graph.preparePayment(id)
                    result = graph.store.get(id)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { graph.notifications.attention() }
            finally {
                wakeLocks.remove(id)?.let { if (it.isHeld) it.release() }
                workers.remove(id)
                // All summary updates use the silent execution group. Remove foreground only
                // for the last worker, before emitting this task's independent result alert.
                if (workers.isEmpty()) finishIfIdle() else graph.notifications.updateRunningSummary(activeRecords())
                result?.takeIf { it.phase != TaskPhase.STOPPED }?.let { graph.notifications.result(it) }
            }
        }
        workers[id] = worker
        worker.start()
        if (workers.isNotEmpty() && updates?.isActive != true) updates = scope.launch {
            while (isActive) { graph.notifications.updateRunningSummary(activeRecords()); delay(1000) }
        }
        return START_REDELIVER_INTENT
    }
    private fun activeRecords() = workers.keys.mapNotNull { runCatching { graph.store.get(it) }.getOrNull() }
    private fun finishIfIdle() {
        if (workers.isNotEmpty()) return
        updates?.cancel(); updates = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }
    override fun onDestroy() {
        scope.cancel()
        wakeLocks.values.forEach { if (it.isHeld) it.release() }; wakeLocks.clear()
        super.onDestroy()
    }
}
