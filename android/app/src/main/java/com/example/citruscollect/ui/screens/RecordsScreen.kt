package com.example.citruscollect.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.citruscollect.data.entity.BatchEntity
import com.example.citruscollect.data.entity.ImageEntity
import com.example.citruscollect.data.entity.SampleEntity
import com.example.citruscollect.ui.LocalAppContainer
import com.example.citruscollect.ui.theme.CitrusColors
import com.example.citruscollect.util.CollectionReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PART_NAMES = listOf("leaf" to "叶", "fruit" to "果", "branch" to "枝", "insect" to "虫体", "other" to "其他")
private const val UNKNOWN_CLASS = "unknown"
private val VIEW_NAMES = mapOf("close_up" to "近景", "context" to "稍远图", "other" to "其他")

private fun dayKey(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
private fun monthDay(day: String): String = day.takeLast(5).replace("-", "月") + "日"
private fun partName(part: String): String = PART_NAMES.firstOrNull { it.first == part }?.second ?: "部位待确认"
private fun imgNo(sampleId: String): String = "IMG-" + sampleId.takeLast(4).uppercase(Locale.ROOT)

/**
 * 图片记录：一张图片一条记录，逐张独立编辑、识别、上传、撤回。
 * 批次同步到服务端，图片仍各自独立成条。
 */
@Composable
fun RecordsScreen(
    onOpenRecord: (String) -> Unit,
    onCapture: () -> Unit,
    onOpenBatches: () -> Unit,
    onAccount: () -> Unit
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()

    val pending by repository.observePending().collectAsState(initial = emptyList())
    val legacyCount by repository.observeLegacyCount().collectAsState(initial = 0)
    val uploaded by repository.observeUploaded().collectAsState(initial = emptyList())
    val all = remember(pending, uploaded) {
        (pending + uploaded).distinctBy { it.sampleId }.sortedByDescending { it.createdAt }
    }
    val batches by repository.observeBatches().collectAsState(initial = emptyList())
    val batch = batches.firstOrNull { it.batchId == container.batchId }
    val inBatch = remember(all, container.batchId) { all.filter { it.batchId == container.batchId } }
    val pendingList = inBatch.filter { it.uploadState != "uploaded" }
    val uploadedList = inBatch.filter { it.uploadState == "uploaded" }

    var tab by rememberSaveable { mutableStateOf("pending") }
    val list = if (tab == "pending") pendingList else uploadedList

    var resolvedPhotos by remember { mutableStateOf(emptySet<String>()) }
    var photos by remember { mutableStateOf<Map<String, ImageEntity>>(emptyMap()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(inBatch.map { it.sampleId }, tick) {
        photos = inBatch.mapNotNull { sample ->
            repository.imagesOf(sample.sampleId).first().firstOrNull()?.let { sample.sampleId to it }
        }.toMap()
        resolvedPhotos = inBatch.map { it.sampleId }.toSet()
    }

    var cultivarNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var classNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(tick) {
        runCatching { container.authRepository.bootstrap() }.onSuccess { bootstrap ->
            cultivarNames = bootstrap.cultivars.filter { it.is_active }.associate { it.cultivar_id to it.name }
            classNames = bootstrap.classes.filter { it.is_active }.associate { it.class_id to it.name }
        }
    }

    var message by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var withdrawId by remember { mutableStateOf<String?>(null) }
    var recognizeId by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showBulk by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(CitrusColors.Screen)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
            modifier = Modifier.fillMaxSize().padding(bottom = if (selecting) 88.dp else 0.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 7.dp, end = 0.dp, top = 14.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("图片记录", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CitrusColors.Ink)
                            Text("一张图片，一条独立记录", fontSize = 11.sp, color = CitrusColors.Muted,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                        RoundTag("本地记录")
                        TextButton(onClick = onAccount) { Text("我的", fontSize = 12.sp, color = CitrusColors.Muted) }
                    }
                    BatchStrip(batch = batch, count = inBatch.size, onClick = onOpenBatches)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Segmented(
                            options = listOf(
                                "pending" to "未上传 ${pendingList.size}",
                                "uploaded" to "已上传 ${uploadedList.size}"
                            ),
                            selected = tab,
                            onSelect = { tab = it; selected = emptySet(); selecting = false }
                        )
                        Spacer(Modifier.weight(1f))
                        if (tab == "pending" && list.isNotEmpty()) {
                            Text(
                                if (selecting) "完成选择" else "批量选择",
                                fontSize = 12.sp,
                                color = CitrusColors.Green,
                                modifier = Modifier
                                    .clickable {
                                        selecting = !selecting
                                        if (!selecting) selected = emptySet()
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                    if (legacyCount > 0) Text("旧版无主记录 $legacyCount 条已隔离保留，不自动归属当前账号", fontSize = 11.sp, color = CitrusColors.Muted)
                    if (tab == "pending") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("队列 ${pendingList.count { it.uploadState in setOf("queued", "uploading", "retrying") }} · 失败 ${pendingList.count { it.uploadState == "failed" }}", fontSize = 11.sp, color = CitrusColors.Muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    var queued = 0
                                    var failed = 0
                                    pendingList.filter { it.uploadState in setOf("pending", "failed", "retrying") }.forEach {
                                        try { repository.enqueueUpload(it.sampleId); queued++ }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { failed++ }
                                    }
                                    message = "已入队 $queued 条" + if (failed > 0) "，$failed 条需检查信息" else "，联网后后台上传"
                                }
                            }) { Text("本批次全部上传", fontSize = 11.sp) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
                        Text("${list.size} 张图片 · 独立管理", fontSize = 10.sp, color = CitrusColors.Faint)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (tab == "pending") "点击图片编辑信息" else "点击查看核验结果",
                            fontSize = 10.sp, color = CitrusColors.Faint
                        )
                    }
                }
            }

            items(list, key = { it.sampleId }) { sample ->
                val image = photos[sample.sampleId]
                RecordCard(
                    sample = sample,
                    image = image,
                    photoResolved = sample.sampleId in resolvedPhotos,
                    cultivar = cultivarNames[sample.cultivarId] ?: when {
                        sample.cultivarId == "unknown" -> "不确定"
                        sample.cultivarId.isBlank() -> "品种未填写"
                        else -> "品种已选择"
                    },
                    className = classNames[sample.classId]
                        ?: when { sample.classId == UNKNOWN_CLASS -> "不确定"; sample.classId.isBlank() -> "类别未填写"; else -> "类别已选择" },
                    viewName = VIEW_NAMES[image?.viewType] ?: "近景",
                    selecting = selecting,
                    selected = sample.sampleId in selected,
                    busy = busyId == sample.sampleId,
                    onOpen = { onOpenRecord(sample.sampleId) },
                    onSelect = {
                        selected = if (sample.sampleId in selected) selected - sample.sampleId
                        else selected + sample.sampleId
                    },
                    onRecognize = { recognizeId = sample.sampleId },
                    onUpload = {
                        busyId = sample.sampleId
                        scope.launch {
                            try {
                                repository.enqueueUpload(sample.sampleId)
                                message = "已加入后台队列 ${imgNo(sample.sampleId)}"
                                tick++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = e.message ?: "上传失败"
                            } finally {
                                busyId = null
                            }
                        }
                    },
                    onWithdraw = { withdrawId = sample.sampleId }
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                if (list.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (tab == "pending") "本批次没有未上传图片" else "本批次还没有上传图片",
                            fontSize = 14.sp, color = CitrusColors.Muted
                        )
                        Text(
                            if (tab == "pending") "去采集，或切换到其他批次" else "上传后，每张图片会保留在这里",
                            fontSize = 11.sp, color = CitrusColors.Faint,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (legacyCount > 0) Text("旧版无主记录 $legacyCount 条已隔离保留，不自动归属当前账号", fontSize = 11.sp, color = CitrusColors.Muted)
                    if (tab == "pending") {
                            TextButton(onClick = onCapture) { Text("去采集", color = CitrusColors.Green) }
                        }
                    }
                } else {
                    Text(
                        "— 本批次共 ${inBatch.size} 张图片 —",
                        fontSize = 10.sp, color = CitrusColors.Faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
                    )
                }
            }
        }

        if (selecting && selected.isNotEmpty()) {
            Surface(
                color = CitrusColors.White,
                shape = RoundedCornerShape(13.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 张", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = emptySet() }) { Text("取消", fontSize = 11.sp) }
                    TextButton(onClick = {
                        val ids = selected.toList()
                        scope.launch {
                            var queued = 0
                            var failed = 0
                            ids.forEach { id ->
                                try { repository.enqueueUpload(id); queued++ }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { failed++ }
                            }
                            message = "已入队 $queued 条，$failed 条未就绪"
                            selecting = false; selected = emptySet()
                        }
                    }) { Text("上传", fontSize = 11.sp) }
                    TextButton(onClick = { showBulk = true }) { Text("批量填写", fontSize = 11.sp, color = CitrusColors.Green) }
                }
            }
        }

        message?.let {
            Surface(
                color = CitrusColors.Green,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
            ) {
                Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            LaunchedEffect(it) {
                delay(2600)
                message = null
            }
        }
    }

    photos[recognizeId]?.let { image ->
        SingleRecognitionDialog(
            image = image,
            photoCount = 1,
            beforeApply = {},
            onDismiss = { recognizeId = null },
            onAdopted = { recognizeId = null; tick++ }
        )
    }

    if (withdrawId != null) AlertDialog(
        onDismissRequest = { withdrawId = null },
        containerColor = CitrusColors.White,
        title = { Text("撤回这条上传记录？") },
        text = { Text("撤回后回到未上传，可以修改后重新提交。这张图片会退出审核和后续新导出。") },
        confirmButton = {
            TextButton(onClick = {
                val id = withdrawId!!
                withdrawId = null
                scope.launch {
                    try {
                        repository.withdraw(id)
                        message = "已撤回，可修改后重新上传"
                        tick++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "撤回失败，请重试"
                    }
                }
            }) { Text("确认撤回") }
        },
        dismissButton = { TextButton(onClick = { withdrawId = null }) { Text("取消") } }
    )

    if (showBulk) BulkEditDialog(
        count = selected.size,
        cultivars = cultivarNames,
        classes = classNames,
        onDismiss = { showBulk = false },
        onApply = { cultivar, part, classId ->
            val targets = selected.toList()
            showBulk = false
            scope.launch {
                var failed = 0
                targets.forEach { id ->
                    val sample = all.firstOrNull { it.sampleId == id } ?: return@forEach
                    try {
                        repository.saveDraftMetadata(
                            id,
                            cultivar ?: sample.cultivarId,
                            part ?: sample.part,
                            classId ?: sample.classId,
                            sample.symptoms,
                            sample.note
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                }
                message = if (failed == 0) "已更新 ${targets.size} 张图片" else "${targets.size - failed} 张已更新，$failed 张失败"
                selected = emptySet()
                selecting = false
                tick++
            }
        }
    )
}

@Composable
private fun RoundTag(text: String) {
    Surface(color = Color.Transparent, border = BorderStroke(1.dp, Color(0xFFD9E2D5)), shape = CircleShape) {
        Text(text, fontSize = 10.sp, color = Color(0xFF75836D),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun BatchStrip(batch: BatchEntity?, count: Int, onClick: () -> Unit) {
    Surface(
        color = CitrusColors.GreenSoft,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(21.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFDCE7D6)))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    batch?.name ?: "历史记录",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink
                )
                Text(
                    if (batch == null) "还没有批次，去“采集批次”新建"
                    else "${monthDay(dayKey(batch.createdAt))} · ${batch.place.ifBlank { "未填写地点" }} · $count 张图片",
                    fontSize = 10.sp, color = Color(0xFF7C8875), modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                "切换 ›", fontSize = 11.sp, color = CitrusColors.Green,
                modifier = Modifier.clickable(onClick = onClick).padding(6.dp)
            )
        }
    }
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Surface(color = Color(0xFFEEF0E9), shape = RoundedCornerShape(9.dp)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { (key, label) ->
                val active = key == selected
                Surface(
                    color = if (active) CitrusColors.White else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                    shadowElevation = if (active) 2.dp else 0.dp,
                    modifier = Modifier.clickable { onSelect(key) }
                ) {
                    Text(
                        label, fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) CitrusColors.Ink else Color(0xFF8B9483),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(
    sample: SampleEntity,
    image: ImageEntity?,
    photoResolved: Boolean,
    cultivar: String,
    className: String,
    viewName: String,
    selecting: Boolean,
    selected: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onRecognize: () -> Unit,
    onUpload: () -> Unit,
    onWithdraw: () -> Unit
) {
    val uploaded = sample.uploadState == "uploaded"
    val readiness = CollectionReadiness.status(sample.uploadState, sample.cultivarId, sample.part, sample.classId,
        if (photoResolved) image != null else null)
    Surface(
        color = CitrusColors.White,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) CitrusColors.Green else Color(0xFFE1E7DC)
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 6.dp)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(137.dp)) {
                if (image != null) {
                    LocalPhoto(image, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFFDCE5CC)))
                }
                StatusBadge(sample.uploadState, readiness)
                if (selecting && !uploaded) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) CitrusColors.Green else Color(0xE6FFFFFF))
                            .clickable(onClick = onSelect),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Text("✓", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 8.dp)) {
                Text(imgNo(sample.sampleId), fontSize = 9.sp, color = CitrusColors.Faint)
                Text(
                    "${partName(sample.part)} · $className",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    "${cultivar.ifBlank { "品种待填写" }} · $viewName",
                    fontSize = 10.sp, color = Color(0xFF949C89), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                readiness.detail?.let { Text(it, fontSize = 10.sp, color = if (readiness.incomplete) CitrusColors.Orange else CitrusColors.Muted, maxLines = 2, modifier = Modifier.padding(top = 5.dp)) }
                sample.uploadError?.let { Text(it, fontSize = 10.sp, color = Color(0xFFB05F4B), maxLines = 3) }
                if (uploaded) sample.reviewJson?.let {
                    Text(reviewSummary(it), fontSize = 10.sp, color = CitrusColors.Green, maxLines = 2)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Color(0xFFF0F1EB))
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardAction(if (uploaded) "查看" else "编辑", CitrusColors.Green, onClick = {
                        if (selecting && !uploaded) onSelect() else onOpen()
                    })
                    Spacer(Modifier.weight(1f))
                    CardAction("识别", CitrusColors.Green, onRecognize)
                    Spacer(Modifier.weight(1f))
                    if (uploaded) {
                        CardAction("撤回", Color(0xFF98A08F), onWithdraw)
                    } else {
                        CardAction(if (sample.uploadState in setOf("queued", "uploading", "retrying")) "已入队" else if (busy) "入队中" else if (sample.uploadState == "failed") "重试 ↑" else if (readiness.needsConfirmation) { if (readiness.incomplete) "去完善" else "去确认" } else "上传 ↑", CitrusColors.Green, if (readiness.needsConfirmation) onOpen else onUpload, enabled = !busy && sample.uploadState !in setOf("queued", "uploading", "retrying"))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text, fontSize = 11.sp, color = if (enabled) color else Color(0xFFB9C0B0),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp)
    )
}

@Composable
private fun StatusBadge(state: String, readiness: CollectionReadiness.Status) {
    val label = readiness.label
    val background: Color
    val foreground: Color
    when {
        readiness.incomplete -> { background = Color(0xEEFFF4E7); foreground = Color(0xFFAD6530) }
        state == "uploaded" -> { background = Color(0xEEE8F3E9); foreground = Color(0xFF376748) }
        state == "draft" -> { background = Color(0xEEEDF2E9); foreground = Color(0xFF4F6B52) }
        state in setOf("queued", "retrying") -> { background = Color(0xEEEDF2E9); foreground = Color(0xFF4F6B52) }
        state == "uploading" -> { background = Color(0xEEEDF2E9); foreground = Color(0xFF4F6B52) }
        state == "needs_photos" -> { background = Color(0xEEFFF0EB); foreground = Color(0xFFB05F4B) }
        state == "failed" -> { background = Color(0xEEFFF0EB); foreground = Color(0xFFB05F4B) }
        else -> { background = Color(0xEEFFF4E7); foreground = Color(0xFFAD6530) }
    }
    Surface(color = background, shape = RoundedCornerShape(5.dp), modifier = Modifier.padding(8.dp)) {
        Text(label, fontSize = 9.sp, color = foreground, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun BulkEditDialog(
    count: Int,
    cultivars: Map<String, String>,
    classes: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (String?, String?, String?) -> Unit
) {
    var cultivar by remember { mutableStateOf<String?>(null) }
    var part by remember { mutableStateOf<String?>(null) }
    var classId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CitrusColors.White,
        title = { Text("统一填写 $count 张图片") },
        text = {
            Column {
                Text(
                    "只更新所选图片中填写的字段，留空的字段和未选图片不变。",
                    fontSize = 12.sp, color = CitrusColors.Muted, modifier = Modifier.padding(bottom = 12.dp)
                )
                FieldChips(
                    "品种",
                    listOf<Pair<String?, String>>(null to "不修改") + cultivars.map { it.key to it.value },
                    cultivar
                ) { cultivar = it }
                FieldChips(
                    "部位",
                    listOf<Pair<String?, String>>(null to "不修改") + PART_NAMES.map { it.first to it.second },
                    part
                ) { part = it }
                FieldChips(
                    "初步类别",
                    listOf<Pair<String?, String>>(null to "不修改") + classes.map { it.key to it.value },
                    classId
                ) { classId = it }
                Text(
                    "对象关联、症状和备注仍逐张填写。",
                    fontSize = 11.sp, color = CitrusColors.Faint, modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(cultivar, part, classId) }) { Text("应用到所选图片") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FieldChips(
    label: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF65755B), modifier = Modifier.padding(bottom = 6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            options.forEach { (value, text) ->
                val active = value == selected
                Surface(
                    color = if (active) CitrusColors.Green else CitrusColors.White,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, if (active) CitrusColors.Green else Color(0xFFDCE5D4)),
                    modifier = Modifier.clickable { onSelect(value) }
                ) {
                    Text(
                        text, fontSize = 11.sp, color = if (active) Color.White else CitrusColors.Ink,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
