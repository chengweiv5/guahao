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

class BookingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, graph.notifications.running(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, graph.notifications.running(null))
        if (worker?.isActive == true) return START_REDELIVER_INTENT
        worker = scope.launch {
            val id = intent?.getStringExtra("taskId")
            try {
                if (id == null) { graph.recover(); return@launch }
                val r = graph.store.get(id)
                if (!graph.mode.allows(r.task)) {
                    graph.scheduler.cancel(r.task)
                    graph.notifications.cancelResult(id)
                    return@launch
                }
                val generation = intent.getLongExtra("generation", -1)
                if (r.task.generation != generation || !readiness(this@BookingService, true).ready) return@launch
                val limit = if (r.order != null) (r.insuranceStartedAt ?: graph.clock.now()).plusSeconds(120)
                    else r.attempt?.sentAt?.plusSeconds(240) ?: r.task.deadline.plusSeconds(240)
                val duration = Duration.between(graph.clock.now(), limit).toMillis().coerceAtLeast(1000)
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guahao:bounded-booking").apply { acquire(duration) }
                val updates = launch {
                    while (isActive) { graph.notifications.updateRunning(graph.store.get(id)); delay(1000) }
                }
                try {
                    graph.engine.run(id, generation, UUID.randomUUID().toString())
                    if (graph.store.get(id).order != null) graph.preparePayment(id)
                } finally { updates.cancelAndJoin() }
                // Finish/cancel the progress notification before the result takes over
                // sound and vibration. No progress update may race with the result.
                stopForeground(STOP_FOREGROUND_REMOVE)
                val result = graph.store.get(id)
                if (result.phase != TaskPhase.STOPPED) graph.notifications.result(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { graph.notifications.attention() }
            finally {
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }
    override fun onDestroy() {
        scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
