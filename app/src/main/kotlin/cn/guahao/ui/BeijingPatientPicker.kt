package cn.guahao.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.guahao.core.HospitalRoute
import cn.guahao.hospital.beijing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit read and card selection; it cannot create or enable a booking task. */
@Composable internal fun BeijingPatientPicker(route: HospitalRoute, hospitalName: String,
    repository: BeijingConnectionRepository) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<BeijingAccountSnapshot?>(null) }
    var patient by remember { mutableStateOf<BeijingPatient?>(null) }
    var card by remember { mutableStateOf<BeijingPatientCard?>(null) }
    var saved by remember { mutableStateOf(repository.saved(route)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("就诊人和就诊卡", fontWeight = FontWeight.SemiBold)
    Text("在${route.channel.title}官方页面登录后，核验并选择本次使用的就诊人和卡。", color = Muted)
    OutlinedButton(onClick = {
        busy = true; account = null; patient = null; card = null; error = null
        scope.launch {
            try { account = repository.refresh(route.channel) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? BeijingQueryException)?.message ?: "连接核验未完成，请重试" }
            finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (busy) "正在核验…" else "核验登录与就诊人")
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    account?.let { current ->
        Text("登录已核验 · ${current.patients.size} 位就诊人", color = Teal)
        if (current.patients.isEmpty()) Text("此账户暂无已绑定就诊人，请先在官方页面管理就诊人。", color = Muted)
        current.patients.forEach { person ->
            OutlinedButton(onClick = { patient = person; card = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${if (patient === person) "✓ " else ""}${person.displayName}")
            }
        }
        patient?.let { person ->
            if (person.cards.isEmpty()) Text("此就诊人暂无可选卡，请在官方页面核对。", color = Muted)
            person.cards.forEach { option ->
                OutlinedButton(onClick = { card = option }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (card === option) "✓ " else ""}${option.label} · ${option.displayNumber}")
                }
            }
        }
        Primary("保存本次就诊人和卡", patient != null && card != null && !busy) {
            try { saved = repository.save(route, hospitalName, current, requireNotNull(patient), requireNotNull(card)); error = null }
            catch (_: Exception) { error = "连接或就诊人已变化，请重新核验后选择" }
        }
    }
    saved?.let { selection ->
        Text("已保存：${selection.patient.displayName} · ${selection.card.label} · ${selection.card.displayNumber}")
        Text("保存在本机加密存储中；后续使用前仍需重新核验。", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
