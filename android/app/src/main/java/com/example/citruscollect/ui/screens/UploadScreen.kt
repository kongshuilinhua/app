package com.example.citruscollect.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.citruscollect.util.CollectionReadiness
import com.example.citruscollect.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadScreen(onBack: () -> Unit, onOpenDraft: (String) -> Unit,
                 onOpenDetails: (String) -> Unit, onRetakePhoto: (String, String) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val pending by container.repository.observePending().collectAsState(initial = emptyList())
    val uploaded by container.repository.observeUploaded().collectAsState(initial = emptyList())
    var showUploaded by rememberSaveable { mutableStateOf(false) }
    var withdrawId by remember { mutableStateOf<String?>(null) }
    var withdrawing by remember { mutableStateOf(false) }
    var uploadingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    val busy = uploadingId != null || photoBusy || withdrawing

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text("上传记录", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !showUploaded, enabled = !busy, onClick = { showUploaded = false }, label = { Text("未上传（${pending.size}）") })
            FilterChip(selected = showUploaded, enabled = !busy, onClick = { showUploaded = true }, label = { Text("已上传（${uploaded.size}）") })
        }
        Spacer(Modifier.height(8.dp))
        val records = if (showUploaded) uploaded else pending
        if (records.isEmpty()) Text(if (showUploaded) "暂无已上传记录" else "暂无未上传记录", Modifier.padding(16.dp))
        if (message != null) {
            Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(records, key = { it.sampleId }) { s ->
                val images by container.repository.imagesOf(s.sampleId).collectAsState(initial = emptyList())
                val editable = s.uploadId == null && s.uploadState in setOf("draft", "pending", "failed", "needs_photos")
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        val readiness = CollectionReadiness.status(s.uploadState, s.cultivarId, s.part, s.classId)
                        val status = readiness.label
                        Text("记录 ${s.sampleId.takeLast(8)} · $status", Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleSmall)
                        Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(s.createdAt)),
                            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        readiness.detail?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                        s.uploadError?.let {
                            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        PhotoGallery(images, editable, busy,
                            onDelete = { imageId, deleteRecord ->
                                photoBusy = true
                                scope.launch {
                                    try { container.repository.deleteImage(imageId, deleteRecord) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { message = e.message ?: "删除失败" }
                                    finally { photoBusy = false }
                                }
                            }, onRetake = { onRetakePhoto(s.sampleId, it) }, onRecognitionAdopted = onOpenDetails)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { onOpenDetails(s.sampleId) }, modifier = Modifier.weight(1f)) {
                                Text(if (editable) "编辑记录" else "查看详情")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = {
                                    if (s.uploadState == "uploaded") {
                                        withdrawId = s.sampleId
                                        return@Button
                                    }
                                    scope.launch {
                                        if (s.uploadState == "draft" || (images.isEmpty() && editable)) {
                                            onOpenDraft(s.sampleId)
                                            return@launch
                                        }
                                        uploadingId = s.sampleId
                                        message = null
                                        try {
                                            container.repository.enqueueUpload(s.sampleId)
                                            message = "已加入后台上传队列，离开页面也会继续"
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            message = e.message ?: "上传失败"
                                        } finally {
                                            uploadingId = null
                                        }
                                    }
                                }
                            ) {
                                Text(if (s.uploadState == "uploaded") "撤回" else if (uploadingId == s.sampleId) "上传中…" else if (images.isEmpty() && editable) "去拍照"
                                    else if (s.uploadState == "draft") { if (readiness.incomplete) "完善信息" else "确认信息" } else "上传")
                            }
                        }
                    }
                }
            }
        }
    }
    if (withdrawId != null) AlertDialog(
        onDismissRequest = { if (!withdrawing) withdrawId = null },
        title = { Text("撤回这条上传记录？") },
        text = { Text("撤回后回到未上传，可以修改后重新提交。该记录将退出审核和后续新导出；已创建的历史导出保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val id = withdrawId!!
            withdrawing = true
            scope.launch {
                try {
                    container.repository.withdraw(id)
                    showUploaded = false
                    message = "已撤回，可修改后重新上传"
                    withdrawId = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "撤回失败，请重试"; withdrawId = null }
                finally { withdrawing = false }
            }
        }) { Text("确认撤回") } },
        dismissButton = { TextButton(enabled = !withdrawing, onClick = { withdrawId = null }) { Text("取消") } }
    )
}
