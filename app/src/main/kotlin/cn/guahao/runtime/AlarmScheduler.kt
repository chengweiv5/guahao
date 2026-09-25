package cn.guahao.runtime

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import cn.guahao.core.*
import java.time.Instant

class AlarmScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun intent(task: BookingTask) = PendingIntent.getBroadcast(context, 0,
        Intent(context, TaskReceiver::class.java).setAction("cn.guahao.RUN")
            .setData(Uri.parse("guahao://task/${task.id}/${task.generation}"))
            .putExtra("taskId", task.id).putExtra("generation", task.generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(task: BookingTask, at: Instant = task.releaseAt) {
        check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) { "请允许精确定时" }
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at.toEpochMilli(), System.currentTimeMillis() + 1000), intent(task))
    }
    fun cancel(task: BookingTask) { alarms.cancel(intent(task)) }
}
