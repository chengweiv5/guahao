package cn.guahao.notifications

import android.app.*
import android.content.*
import android.os.Build
import cn.guahao.MainActivity
import cn.guahao.R
import cn.guahao.core.*
import cn.guahao.runtime.TaskReceiver
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BookingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("execution", "挂号任务运行", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("results", "挂号结果与付款提醒", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun open(id: String?) = PendingIntent.getActivity(context, id?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).putExtra("taskId", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun builder(channel: String, id: String?, title: String, text: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_booking).setContentTitle(title).setContentText(text).setContentIntent(open(id))
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_booking)
            .setContentTitle(title).setContentText("解锁后查看详情").setContentIntent(open(id)).build())
    fun running(record: TaskRecord?): Notification {
        val id = record?.task?.id
        val stop = PendingIntent.getBroadcast(context, 1,
            Intent(context, TaskReceiver::class.java).setAction("cn.guahao.STOP").putExtra("taskId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder("execution", id, if (record?.task?.demo == true) "演示挂号任务运行中" else "挂号任务运行中",
            record?.note ?: "正在准备任务").setOngoing(true).addAction(Notification.Action.Builder(null, "停止任务", stop).build()).build()
    }
    fun updateRunning(record: TaskRecord) { manager.notify(1, running(record)) }
    fun result(record: TaskRecord) {
        val deadline = record.order?.invalidAt?.atZone(ZoneId.of("Asia/Shanghai"))?.format(DateTimeFormatter.ofPattern("HH:mm"))
        val title = when(record.phase) {
            TaskPhase.BOOKED -> "挂号已完成"
            TaskPhase.AWAITING_PAYMENT -> if (deadline != null) "锁号成功，请在 $deadline 前付款" else "锁号成功，请核对付款期限"
            TaskPhase.EXPIRED -> "挂号任务已结束"
            else -> "挂号任务需要处理"
        }
        manager.notify(record.task.id.hashCode(), builder("results", record.task.id,
            (if (record.task.demo) "演示 · " else "") + title, "点击查看任务状态和官方处理入口").setAutoCancel(true).build())
    }
    fun attention() { manager.notify(2, builder("results", null, "挂号任务需要检查", "请打开 App 检查会话、定时与后台运行权限").setAutoCancel(true).build()) }
}
