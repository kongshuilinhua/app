package com.example.citruscollect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.citruscollect.data.diagnosis.result
import com.example.citruscollect.ui.LocalAppContainer
import com.example.citruscollect.ui.theme.CitrusColors

@Composable
fun DiagnosisHistoryScreen(onOpen: (String) -> Unit, onIdentify: () -> Unit) {
    val container = LocalAppContainer.current
    val rows by container.diagnosisRepository.observeAll().collectAsState(initial = emptyList())
    var followOnly by remember { mutableStateOf(false) }
    val visible = if (followOnly) rows.filter { it.followUpDate != null } else rows
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            ScreenTitle("我的记录", "识别报告与后续观察。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !followOnly, onClick = { followOnly = false }, label = { Text("识别记录 ${rows.size}") })
                FilterChip(selected = followOnly, onClick = { followOnly = true }, label = { Text("后续观察 ${rows.count { it.followUpDate != null }}") })
            }
        }
        if (visible.isEmpty()) item {
            AppSection {
                Text(if (followOnly) "还没有观察安排" else "还没有识别记录", style = MaterialTheme.typography.titleMedium)
                Text("拍照识别后，结果和失败记录都会保留在本机。", color = CitrusColors.Muted)
                Button(onClick = onIdentify) { Text("去拍照识别") }
            }
        }
        items(visible, key = { it.id }) { row ->
            Surface(onClick = { onOpen(row.id) }, shape = MaterialTheme.shapes.large, color = CitrusColors.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.result()?.advice?.title ?: when (row.status) { "analyzing" -> "正在识别…"; "draft" -> "照片已保存，待识别"; else -> "识别未完成，可重试" }, style = MaterialTheme.typography.titleMedium)
                    Text(displayDate(row.createdAt), style = MaterialTheme.typography.bodySmall, color = CitrusColors.Muted)
                    row.error?.let { Text(it, color = CitrusColors.Red, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    row.followUpDate?.let { Text("观察日期：$it · ${if (row.observation.isBlank()) "待记录" else "已记录"}", color = CitrusColors.Green) }
                    Text("查看记录 →", color = CitrusColors.Green, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item { Text("记录按账号和服务器分别保存。清除应用数据会移除本地记录。", style = MaterialTheme.typography.bodySmall, color = CitrusColors.Muted) }
    }
}
