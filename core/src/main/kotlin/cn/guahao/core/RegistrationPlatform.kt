package cn.guahao.core

/** A channel is a credential boundary even when two channels share an API host. */
enum class RegistrationChannel(val id: String, val title: String, val requestSource: String?) {
    YOUAN_WECHAT("psc-youan", "佑安微信服务号", null),
    BEIJING_114("beijing-114-wechat", "北京 114", "WE_CHAT"),
    JINGTONG("beijing-114-jingtong", "京通", "JT_WECHAT")
}

data class HospitalRoute(val hospitalId: String, val channel: RegistrationChannel, val campusId: String? = null) {
    init { require(hospitalId.isNotBlank()); require(campusId == null || campusId.isNotBlank()) }
    fun accepts(binding: ConnectionBinding) = hospitalId == binding.hospitalId && channel.id == binding.providerId &&
        campusId == binding.campusId
}

/** Observed official-page traffic is deliberately not a native-client capability. */
data class PlatformCapabilities(
    val nativeQueriesVerified: Boolean = false,
    val authenticationVerified: Boolean = false,
    val patientsVerified: Boolean = false,
    val submissionVerified: Boolean = false,
    val ordersVerified: Boolean = false
) {
    val canCreateAutomaticTask get() = nativeQueriesVerified && authenticationVerified && patientsVerified &&
        submissionVerified && ordersVerified
}
