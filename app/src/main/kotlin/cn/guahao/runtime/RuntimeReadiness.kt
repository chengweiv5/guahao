package cn.guahao.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import cn.guahao.core.RuntimeReadiness

fun readiness(context: Context, sessionValid: Boolean): RuntimeReadiness {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val alarms = context.getSystemService(AlarmManager::class.java)
    val batteryAcknowledged = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("batteryAcknowledged", false)
    return RuntimeReadiness(sessionValid,
        notifications.areNotificationsEnabled() && listOf("execution","results").all {
            notifications.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
        } && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
        Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
        batteryAcknowledged,
        context.getSystemService(UserManager::class.java).isUserUnlocked)
}
fun batteryExempt(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
