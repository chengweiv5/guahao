package cn.guahao.hospital

import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** One instance per provider, shared by all credential versions. Called under transport gate. */
class RequestBudget(private val intervalMillis: Long = 1000) {
    private var nextAt = 0L
    suspend fun awaitTurn() {
        val wait = TimeUnit.NANOSECONDS.toMillis(nextAt - System.nanoTime())
        if (wait > 0) delay(wait)
        nextAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis)
    }
    fun defer(millis: Long) {
        nextAt = maxOf(nextAt, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis.coerceIn(0, 86_400_000)))
    }
}
