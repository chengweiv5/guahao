package cn.guahao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.guahao.core.*

@Composable internal fun ActionFooter(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable internal fun EditorProgress(step: Int) {
    val labels = listOf("医院与渠道", "就诊条件", "执行设置", "确认启用")
    Text("$step / 4  ·  ${labels[step - 1]}", color = Teal, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.indices.forEach { index ->
            Spacer(Modifier.weight(1f).height(4.dp).background(
                if (index < step) Teal else Color(0xFFDAE5DE), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable internal fun TaskListCard(record: TaskRecord, onClick: () -> Unit) {
    val task = record.task
    Panel(onClick = onClick) {
        Text(task.hospitalName, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(task.binding?.campusName, task.binding?.providerName ?: if (task.demo) "本机演示" else "微信服务号").joinToString(" · "),
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Badge((if (task.demo) "演示 · " else "") + phaseLabel(record.phase),
            record.phase in setOf(TaskPhase.AWAITING_PAYMENT, TaskPhase.NEEDS_ATTENTION, TaskPhase.RECONCILING))
        Text("${task.condition.department.name} · ${task.condition.doctorName}",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${task.condition.visitDate} · ${periodLabel(task.condition.startMinute, task.condition.endMinute)}", color = Muted)
        when (record.phase) {
            TaskPhase.WAITING -> Text("${timestamp(task.releaseAt)} 开始查号", color = Teal)
            TaskPhase.AWAITING_PAYMENT -> Text(record.order?.invalidAt?.let { "请于 ${timestamp(it)} 前付款" }
                ?: "请到原渠道核对付款期限", color = MaterialTheme.colorScheme.error)
            TaskPhase.RECONCILING -> Text("正在核对原提交结果", color = Muted)
            TaskPhase.DRAFT -> Text("核对条件后启用", color = Muted)
            else -> if (record.note.isNotBlank()) Text(record.note, color = Muted, maxLines = 2)
        }
        Text("查看任务详情  →", color = Teal, style = MaterialTheme.typography.bodySmall)
    }
}
