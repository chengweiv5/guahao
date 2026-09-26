package cn.guahao.hospital.beijing

import cn.guahao.core.*
import java.time.LocalDate

interface BeijingBookingSource {
    suspend fun orders(selection: BeijingPatientSelection, from: LocalDate, to: LocalDate): List<OrderSnapshot>
    suspend fun submit(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate, maySend: () -> Boolean): LockReply
    suspend fun receipt(selection: BeijingPatientSelection, task: BookingTask, candidate: Candidate): AsyncReply
}

interface BeijingPlatformSource : BeijingQueryTransport, BeijingAccountSource, BeijingBookingSource

/** Live read-only samples do not establish the submit/reconciliation contract. */
internal object JingtongCapabilities {
    const val automaticBookingVerified = false
    const val unavailableReason = "京通预约提交与同单核对仍待真实验证；可保存草稿，暂不能开启自动挂号"
}
