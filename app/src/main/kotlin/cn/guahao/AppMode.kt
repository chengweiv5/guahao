package cn.guahao

import cn.guahao.core.BookingTask
import cn.guahao.core.PatientRef
import cn.guahao.core.TaskRecord

/** Build-time policy shared by the UI, execution and recovery entry points. */
class AppMode(val demoEnabled: Boolean = BuildConfig.DEMO_MODE_ENABLED) {
    fun allows(patient: PatientRef): Boolean = demoEnabled || patient.sessionId != "demo"
    fun allows(task: BookingTask): Boolean = demoEnabled || (!task.demo && allows(task.condition.patient))
    fun visible(records: List<TaskRecord>): List<TaskRecord> = records.filter { allows(it.task) }
    fun requireAllowed(task: BookingTask) { check(allows(task)) { "此版本不支持演示任务" } }
    fun requireAllowed(patient: PatientRef) { check(allows(patient)) { "此版本不支持演示数据" } }
}
