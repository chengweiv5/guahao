package cn.guahao.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.guahao.core.RegistrationChannel
import cn.guahao.hospital.beijing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** No synthetic hospitals or successful-connection labels in the real platform picker. */
@Composable internal fun HospitalChannelPicker(
    channel: RegistrationChannel,
    selectedHospital: BeijingHospital?,
    load: suspend (RegistrationChannel, Int) -> BeijingHospitalPage,
    onChannel: (RegistrationChannel) -> Unit,
    onHospital: (BeijingHospital?) -> Unit
) {
    Text("挂号渠道", fontWeight = FontWeight.SemiBold)
    RegistrationChannel.entries.forEach { option ->
        OutlinedButton(onClick = { if (channel != option) { onHospital(null); onChannel(option) } },
            border = BorderStroke(if (channel == option) 2.dp else 1.dp,
                if (channel == option) Teal else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("${if (channel == option) "✓ " else ""}${option.title}")
        }
    }
    if (channel == RegistrationChannel.YOUAN_WECHAT) {
        Text("北京佑安医院", fontWeight = FontWeight.SemiBold)
        Text("下一步选择已连接的就诊人。", color = Muted)
    } else key(channel) {
        PlatformHospitalResults(channel, selectedHospital, load, onHospital)
    }
}

@Composable private fun PlatformHospitalResults(channel: RegistrationChannel, selected: BeijingHospital?,
    load: suspend (RegistrationChannel, Int) -> BeijingHospitalPage, onSelect: (BeijingHospital?) -> Unit) {
    val scope = rememberCoroutineScope()
    var hospitals by remember { mutableStateOf(emptyList<BeijingHospital>()) }
    var page by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun fetch(next: Int) {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                val result = load(channel, next)
                hospitals = (if (next == 1) result.hospitals else hospitals + result.hospitals).distinctBy { it.code }
                page = next; total = result.total
                if (next == 1 && selected != null && hospitals.none { it.code == selected.code }) onSelect(null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? BeijingQueryException)?.message ?: "医院目录暂不可用，请稍后重试" }
            finally { loading = false }
        }
    }
    Text("${channel.title}的连接和订单单独管理。", color = Muted)
    OutlinedButton(onClick = { fetch(1) }, enabled = !loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (loading) "正在查询医院…" else if (page > 0) "刷新医院目录" else "查询医院目录")
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (page > 0) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索已加载医院") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        val visible = hospitals.filter { it.name.contains(search.trim(), ignoreCase = true) }
        if (hospitals.isEmpty()) Text("平台本次未返回医院，请稍后刷新。", color = Muted)
        else if (visible.isEmpty()) Text("已加载的医院中没有匹配项，可以加载更多或调整搜索词。", color = Muted)
        visible.forEach { hospital ->
            OutlinedButton(onClick = { onSelect(hospital) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${if (selected?.code == hospital.code) "✓ " else ""}${hospital.name}", fontWeight = FontWeight.SemiBold)
                    hospital.level?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (hospitals.size < total) OutlinedButton(onClick = { fetch(page + 1) }, enabled = !loading,
            modifier = Modifier.fillMaxWidth()) { Text("加载更多医院 · 已加载 ${hospitals.size} 家") }
    }
    if (selected != null) Text("已选 ${selected.name}。此渠道尚未完成 App 连接，暂不能创建自动挂号任务。", color = Muted)
}
