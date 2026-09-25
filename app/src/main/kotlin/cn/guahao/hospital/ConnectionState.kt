package cn.guahao.hospital

import cn.guahao.core.psc.PscSession
import cn.guahao.core.ConnectionBinding
import kotlinx.serialization.Serializable

@Serializable enum class ConnectionStatus { SAVED, VERIFIED, CHECK_FAILED, BOOKING_UNAVAILABLE, RECONNECT_REQUIRED }

@Serializable data class ConnectionHealth(
    val status: ConnectionStatus = ConnectionStatus.SAVED,
    val checkedAt: String? = null,
    val verifiedAt: String? = null
)

data class HospitalConnection(val session: PscSession? = null, val health: ConnectionHealth = ConnectionHealth(),
    val checking: Boolean = false, val binding: ConnectionBinding? = null) {
    val needsReconnect get() = session != null && health.status == ConnectionStatus.RECONNECT_REQUIRED
    val title get() = when {
        session == null -> "尚未连接"
        checking -> "正在校验连接"
        else -> when (health.status) {
            ConnectionStatus.SAVED -> "会话已保存，待校验"
            ConnectionStatus.VERIFIED -> "最近校验可用"
            ConnectionStatus.CHECK_FAILED -> "暂未确认连接状态"
            ConnectionStatus.BOOKING_UNAVAILABLE -> "挂号权限待核对"
            ConnectionStatus.RECONNECT_REQUIRED -> "需重新连接"
        }
    }
    val description get() = when {
        session == null -> "首次导入后加密保存，有效期间无需重复粘贴。"
        checking -> "正在向医院核验挂号权限，请稍候。"
        else -> when (health.status) {
            ConnectionStatus.SAVED -> "已保留上次会话，校验后才能确认当前是否可用。"
            ConnectionStatus.VERIFIED -> "启用任务和提交前仍会校验；医院可能随时使会话失效。"
            ConnectionStatus.CHECK_FAILED -> "网络或医院响应暂不可确认，会话已保留，请重试校验。"
            ConnectionStatus.BOOKING_UNAVAILABLE -> "医院未通过身份或挂号权限校验。请在微信核对，必要时重新导入；这不一定是登录过期。"
            ConnectionStatus.RECONNECT_REQUIRED -> "医院要求重新登录。请在微信选择就诊人，复制新链接重新导入。"
        }
    }
}
