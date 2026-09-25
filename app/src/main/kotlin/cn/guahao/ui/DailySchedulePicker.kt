package cn.guahao.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.guahao.core.*
import kotlinx.coroutines.*
import java.time.Instant

data class DoctorSelection(val query: ScheduleQuery, val doctor: DoctorRef, val observation: ScheduleObservation)

internal class SchedulePickerState {
    var result by mutableStateOf<DepartmentSchedule?>(null)
    var checkedAt by mutableStateOf<Instant?>(null)
    var preselect by mutableStateOf(false)
    var search by mutableStateOf("")
}

@Composable internal fun ScheduleSummary(observation: ScheduleObservation?) {
    if (observation == null) {
        Text("尚未查询目标日排班", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("最近查询：${observation.availability.label}", fontWeight = FontWeight.SemiBold)
        Text(if (observation.doctorConfirmed) "目标日医生排班已确认" else "目标日排班待确认",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("查询于 ${observation.checkedAt.atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · 执行时重新核实",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** The caller keys this subtree by the complete query; disposing it cancels old requests. */
@Composable internal fun DailySchedulePicker(query: ScheduleQuery?, selection: DoctorSelection?,
    load: suspend (ScheduleQuery) -> DepartmentSchedule, state: SchedulePickerState = remember(query) { SchedulePickerState() },
    onSelect: (DoctorSelection?) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by state::result
    var checkedAt by state::checkedAt
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preselect by state::preselect
    var search by state::search
    OutlinedButton(onClick = {
        val q = query ?: return@OutlinedButton
        onSelect(null); result = null; error = null; preselect = false; search = ""; loading = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { load(q) }
                if (loaded.department != q.department) throw HospitalException("医院返回科室不一致，请重新查询")
                result = loaded; checkedAt = Instant.now()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? HospitalException)?.safeMessage ?: "排班查询失败，请重试或重新连接医院" }
            finally { loading = false }
        }
    }, enabled = query != null && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (loading) "正在查询当日排班…" else if (result != null) "重新查询当日排班" else "查询当日排班")
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val schedule = result
    if (query == null) Text("先选择就诊人、科室、日期和是否专程来京就医。")
    else if (schedule == null && !loading && error == null) Text("查询后选择医生；更换条件需重新查询。")
    if (query != null && schedule != null) {
        val day = schedule.day(query.visitDate)
        Surface(color = if (day.status == DateAvailability.NOT_RELEASED) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${query.visitDate} · ${query.department.name}", style = MaterialTheme.typography.bodySmall)
                Text(day.status.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(when (day.status) {
                    DateAvailability.NOT_RELEASED -> "医院尚未开放这一天的号源。可先指定医生，设置放号时间。"
                    DateAvailability.NO_STOCK -> "医院显示这一天当前无可挂号源。可更换日期，或在任务执行期间等待号源。"
                    DateAvailability.UNKNOWN -> "医院暂未返回可确认的当天状态，请重试或在服务号核对。"
                    DateAvailability.AVAILABLE -> "医院显示当日有号，以具体医生和时段为准。"
                })
            }
        }
        val published = day.doctors.isNotEmpty()
        if (!published && !preselect) {
            Text("尚未取得当天医生排班，可以从医院已确认的本科室名单中预选。")
            OutlinedButton(onClick = { preselect = true }, modifier = Modifier.fillMaxWidth()) { Text("预选目标医生") }
        }
        if (published || preselect) {
            val doctors = if (published) day.doctors else schedule.doctors
            Text(if (published) "当日排班 · 选择医生" else "预选目标医生 · 本科室名单", fontWeight = FontWeight.SemiBold)
            if (!published) Text("来源：医院已返回的本科室医生。目标日出诊安排仍待确认。", style = MaterialTheme.typography.bodySmall)
            if (doctors.size > 6) OutlinedTextField(search, { search = it }, label = { Text("搜索医生") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (doctors.isEmpty()) Text("暂无法预选医生，请重新查询或在服务号核对。")
            val visible = doctors.filter { it.name.contains(search) }
            if (doctors.isNotEmpty() && visible.isEmpty()) Text("没有匹配的医生，请调整搜索词。")
            visible.forEach { doctor ->
                val selected = selection?.doctor == doctor
                OutlinedButton(onClick = { onSelect(DoctorSelection(query, doctor, day.observation(doctor, checkedAt!!))) },
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${doctor.name} · ${if (selected) "已选择 ✓" else "选择 ○"}", fontWeight = FontWeight.SemiBold)
                        doctor.title?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (!published) Text("目标日排班待确认")
                        else {
                            val slots = day.candidates.filter { it.doctorCode == doctor.code && it.doctorName == doctor.name }
                            if (slots.isEmpty()) Text("已返回医生排班，具体时段待公布")
                            slots.groupBy { it.half to it.feeFen }.forEach { (key, values) ->
                                val half = when(key.first) { "1" -> "上午"; "2" -> "下午"; "3" -> "晚间"; else -> "全天" }
                                Text("$half · ¥${java.math.BigDecimal.valueOf(key.second, 2).toPlainString()}")
                                Text(values.sortedBy { it.startMinute }.map { it.hour }.distinct().joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (day.status == DateAvailability.AVAILABLE) Text(if (slots.any { it.remaining > 0 && !it.standby }) "该医生当前有号" else "该医生当前无可挂号源")
                        }
                    }
                }
            }
            if (!published) Text("只尝试指定日期和医生，未匹配到号源时等待至任务截止，不自动更换医生。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
