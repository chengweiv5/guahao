package cn.guahao.payment

import android.content.Context
import android.content.Intent

object OfficialPaymentHandoff {
    const val instructions = "微信 → 北京佑安医院服务号 → 挂号结果查询 → 找到本单 → 医保缴费（或按已选择的普通支付方式缴费）。完成后返回这里刷新。"
    fun openWeChat(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm") ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
