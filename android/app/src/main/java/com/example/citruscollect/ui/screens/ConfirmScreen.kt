package com.example.citruscollect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.citruscollect.ui.LocalAppContainer
import com.example.citruscollect.ui.theme.CitrusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

// 部位与视角是固定业务字典（契约枚举），只有品种/类别来自 bootstrap。
private val PARTS = listOf("leaf" to "叶", "fruit" to "果", "branch" to "枝", "insect" to "虫体", "other" to "其他")
private const val UNKNOWN_CLASS = "unknown"

/**
 * 一条记录只有一张图片，本页编辑的所有信息只属于这一张图片。
 * 旧版本的多图记录仍可打开与上传，但会明确提示它们共用一条信息。
 */
@Composable
fun ConfirmScreen(sampleId: String, onBack: () -> Unit, onSaved: () -> Unit, onRetake: (String?) -> Unit, onRecordDeleted: () -> Unit,
                  onOpenRecord: (String) -> Unit = {}) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val images by container.repository.imagesOf(sampleId).collectAsState(initial = emptyList())
    val sample by container.repository.sampleById(sampleId).collectAsState(initial = null)
    val image = images.firstOrNull()
    val legacyMultiPhoto = images.size > 1
    val editable = sample?.let { it.uploadId == null && it.uploadState in setOf("draft", "pending", "failed", "needs_photos") } == true
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recognizeId by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    var cultivars by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var classes by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var dictionaryAttempt by remember { mutableStateOf(0) }
    var dictionaryReady by remember { mutableStateOf(false) }
    var dictionaryError by remember { mutableStateOf<String?>(null) }

    var cultivar by rememberSaveable(sampleId) { mutableStateOf("") }
    var part by rememberSaveable(sampleId) { mutableStateOf("leaf") }
    var classId by rememberSaveable(sampleId) { mutableStateOf("") }
    var symptoms by rememberSaveable(sampleId) { mutableStateOf("") }
    var note by rememberSaveable(sampleId) { mutableStateOf("") }
    var formLoaded by rememberSaveable(sampleId) { mutableStateOf(false) }

    LaunchedEffect(sampleId, dictionaryAttempt) {
        dictionaryReady = false
        dictionaryError = null
        try {
            val stored = container.repository.sampleById(sampleId).first()
            if (!formLoaded) {
                cultivar = stored?.cultivarId.orEmpty()
                classId = stored?.classId.orEmpty()
                part = stored?.part ?: "leaf"
                symptoms = stored?.symptoms.orEmpty()
                note = stored?.note.orEmpty()
                formLoaded = true
            }
            val bootstrap = container.authRepository.bootstrap(forceRefresh = dictionaryAttempt > 0)
            cultivars = bootstrap.cultivars.filter { it.is_active }.map { it.cultivar_id to it.name }
            // 模型无法映射到启用字典时用“不确定”，仍允许人工选择；字典里已有同名项时不重复添加。
            val activeClasses = bootstrap.classes.filter { it.is_active }.map { it.class_id to it.name }
            val unknown = activeClasses.firstOrNull { it.first == UNKNOWN_CLASS }
                ?: activeClasses.firstOrNull { it.second == "不确定" }
                ?: (UNKNOWN_CLASS to "不确定")
            classes = listOf(unknown) + activeClasses.filter { it.first != unknown.first && it.second != "不确定" }
            if (cultivars.none { it.first == cultivar }) cultivar = cultivars.firstOrNull()?.first.orEmpty()
            if (classes.none { it.first == classId }) classId = unknown.first
            dictionaryReady = cultivars.isNotEmpty() && classes.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dictionaryError = "字典加载失败，请重试"
        }
    }

    Column(Modifier.fillMaxSize().background(CitrusColors.Screen)) {
      Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = onBack) { Text("返回") }
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (sample == null) "加载中…" else if (editable) "编辑图片" else "采集详情",
                    style = MaterialTheme.typography.titleMedium)
                Text("IMG-" + sampleId.takeLast(4).uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (image == null) {
            Text(if (sample == null) "记录加载中…" else "这张记录还没有图片，请先补拍")
            if (sample != null && editable) {
                OutlinedButton(enabled = !busy, onClick = { onRetake(null) }) { Text("返回补拍") }
            }
            return@Column
        }

        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Box {
                LocalPhoto(image, Modifier.fillMaxWidth().height(230.dp), fullSize = true)
                if (legacyMultiPhoto) {
                    Text("旧版多图 · 第 ${images.indexOf(image) + 1}/${images.size} 张",
                        Modifier.align(Alignment.BottomStart).padding(8.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            if (legacyMultiPhoto) "旧版本的多图记录：这些图片共用同一条信息。新拍的照片会各自单独成条。"
            else "下面的信息只属于这张图片，其他图片互不影响。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall
        )

        if (editable && formLoaded && sample?.uploadState == "draft") {
            Text("待确认：请核对品种、部位和类别，确认后保存。", color = CitrusColors.Green,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = editable && !busy, onClick = { recognizeId = image.imageId }) {
                Text(if (image.recognitionJson != null) "识别结果" else "识别此图")
            }
            OutlinedButton(enabled = editable && !busy, onClick = { onRetake(image.imageId) }) { Text("重拍") }
            OutlinedButton(enabled = editable && !busy, onClick = { confirmDiscard = true }) { Text("删除") }
        }

        sample?.let { record ->
            if (record.captureQualityWarning != null || record.latitude != null) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        record.captureQualityWarning?.let {
                            Text("拍摄质量提醒", style = MaterialTheme.typography.titleSmall)
                            Text(it)
                            Text("此提醒仅供采集参考，原图已保留。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (record.latitude != null && record.longitude != null) {
                            Text("拍摄定位：${record.latitude}, ${record.longitude}")
                            record.locationAccuracyM?.let { Text("定位精度约 ±${it.toInt()} 米", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (sample?.uploadState == "uploaded") {
            ReviewFeedback(sample?.reviewJson, busy) {
                busy = true; error = null
                scope.launch {
                    try { container.repository.refreshReview(sampleId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "核验结果刷新失败" }
                    finally { busy = false }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (dictionaryError != null) {
            Text(dictionaryError.orEmpty(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { dictionaryAttempt++ }) { Text("重试加载字典") }
        }
        if (dictionaryReady) TextButton(onClick = { dictionaryAttempt++ }) { Text("刷新字典") }

        Text("品种")
        ChoiceRow(cultivars, cultivar, enabled = editable && !busy) { cultivar = it }
        Text("部位")
        ChoiceRow(PARTS, part, enabled = editable && !busy) { part = it }
        Text("初步类别")
        ChoiceRow(classes, classId, enabled = editable && !busy) { classId = it }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(symptoms, { symptoms = it }, readOnly = !editable || busy, label = { Text("症状描述") },
            modifier = Modifier.fillMaxWidth(), enabled = editable && !busy)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(note, { note = it }, readOnly = !editable || busy, label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(), enabled = editable && !busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(Modifier.height(16.dp))
      }

      Surface(color = CitrusColors.White, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (editable) {
                OutlinedButton(
                    enabled = dictionaryReady && !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                container.repository.saveConfirm(sampleId, cultivar, part, classId, symptoms, note)
                                onSaved()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "保存失败，请重试"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存信息") }
                Button(
                    enabled = dictionaryReady && !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                container.repository.saveConfirm(sampleId, cultivar, part, classId, symptoms, note)
                                container.repository.enqueueUpload(sampleId)
                                onSaved()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "上传失败，可稍后在上传记录里重试"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (busy) "处理中…" else "保存并加入队列") }
            } else {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回列表") }
            }
        }
      }
    }

    images.firstOrNull { it.imageId == recognizeId }?.let { target ->
        SingleRecognitionDialog(target, images.size, readOnly = !editable, beforeApply = {
            container.repository.saveDraftMetadata(sampleId, cultivar, part, classId, symptoms, note)
        }, onDismiss = { recognizeId = null }, onAdopted = { adopted ->
            recognizeId = null
            if (adopted != sampleId) onOpenRecord(adopted) else scope.launch {
                val updated = container.repository.sampleById(sampleId).first()
                if (updated != null) { classId = updated.classId; part = updated.part; note = updated.note }
            }
        })
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { if (!busy) confirmDiscard = false },
        containerColor = com.example.citruscollect.ui.theme.CitrusColors.White,
        title = { Text("删除这张图片？") },
        text = { Text("这条记录只有这一张图片，删除后记录和本地原图都会移除，无法恢复。") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null; confirmDiscard = false
                scope.launch {
                    try {
                        container.repository.discardRecord(sampleId)
                        onRecordDeleted()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "删除失败，请重试"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("删除记录") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmDiscard = false }) { Text("取消") } }
    )
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}
