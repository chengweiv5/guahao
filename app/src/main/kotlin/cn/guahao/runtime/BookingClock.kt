package cn.guahao.runtime

import android.os.SystemClock
import cn.guahao.core.BookingClock
import kotlinx.coroutines.delay
import java.time.Instant

/** Wall-clock forward jumps are honored; backward jumps cannot extend a running window. */
class AndroidBookingClock : BookingClock {
    private val wallAnchor = Instant.now()
    private val elapsedAnchor = SystemClock.elapsedRealtime()
    override fun now(): Instant = maxOf(Instant.now(), wallAnchor.plusMillis(SystemClock.elapsedRealtime() - elapsedAnchor))
    override suspend fun delayMillis(value: Long) = delay(value)
}
