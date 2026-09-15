package com.ensemblereads.app.ui.reader

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ensemblereads.app.data.db.AnnotationEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.player.AudioPlaybackService
import com.ensemblereads.app.player.PlaybackController
import com.ensemblereads.app.ui.theme.LocalReaderPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** 高亮可选颜色（AARRGGBB），长按段落 → 高亮时选择。 */
private val highlightColors = listOf("#FFE8D88A", "#FFA8E6CF", "#FFFFB3BA", "#FF9ECBFF", "#FFFFF3B0")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    chapterTitle: String,
    content: String,
    segments: List<SegmentEntity>,
    controller: PlaybackController?,
    chapterId: Long,
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenRoles: () -> Unit,
    onPlayFromSeg: ((Int) -> Unit)? = null,
    onSeekToMs: ((Long) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    // 阅读器内目录抽屉（D13）：传入全书章节列表后，顶部「目录」改为抽屉
    chapters: List<ChapterEntity>? = null,
    currentChapterId: Long = chapterId,
    onOpenChapter: ((ChapterEntity) -> Unit)? = null,
    // 阅读排版（D11）：字号/行距/字体，来自设置
    fontSizeSp: Float = 17f,
    lineHeightSp: Float = 28f,
    fontFamilyName: String = "default",
    // 阅读标注（E15）：当前章书签/高亮/笔记
    annotations: List<AnnotationEntity> = emptyList(),
    onAddAnnotation: ((SegmentEntity, String, String?, String?) -> Unit)? = null,
    onDeleteAnnotation: ((Long) -> Unit)? = null,
    // 书内搜索入口（E14）：非空时顶栏显示搜索图标
    onSearch: (() -> Unit)? = null,
) {
    var cur by remember { mutableIntStateOf(-1) }
    // 沉浸式阅读：点击正文空白切换顶部/底部栏显隐
    var showBars by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var annotating by remember { mutableStateOf<SegmentEntity?>(null) }
    var noteTarget by remember { mutableStateOf<SegmentEntity?>(null) }
    var noteText by remember { mutableStateOf("") }
    val annBySeg = annotations.associateBy { it.segIndex }
    val palette = LocalReaderPalette.current
    // 正文样式：字号/行距/字体由设置驱动，文字颜色用阅读器专属配色（与 App 主题解耦）
    val bodyFontFamily = when (fontFamilyName) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        fontFamily = bodyFontFamily,
        color = palette.text,
    )
    LaunchedEffect(Unit) {
        controller?.currentSeg?.collectLatest { cur = it }
    }
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            if (showBars) TopAppBar(
                title = { Text(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    // 有章节列表时用抽屉；否则退回独立目录页
                    TextButton(onClick = { if (chapters != null) showToc = true else onOpenChapters() }) { Text("目录") }
                    TextButton(onClick = onOpenRoles) { Text("角色") }
                    if (onSearch != null) {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (showBars) controller?.let {
                PlaybackBar(it, chapterId, onSeekToMs, onPrevChapter, onNextChapter)
            }
        },
    ) { pad ->
        // 点击正文空白 toggle 工具栏；段落文字点击（子项消费）仍触发定位播放
        Box(
            Modifier.fillMaxSize().padding(pad)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { showBars = !showBars },
        ) {
            val contentPadding = PaddingValues(16.dp)
            if (segments.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    itemsIndexed(segments) { i, seg ->
                        val highlighted = i == cur
                        val ann = annBySeg[seg.segIndex]
                        // 背景：当前播放段高亮 > 用户高亮色 > 透明
                        val bg = when {
                            highlighted -> palette.selection
                            ann?.type == AnnotationEntity.TYPE_HIGHLIGHT && ann.color != null ->
                                Color(android.graphics.Color.parseColor(ann.color))
                            else -> Color.Transparent
                        }
                        val suffix = when (ann?.type) {
                            AnnotationEntity.TYPE_BOOKMARK -> " 🔖"
                            AnnotationEntity.TYPE_NOTE -> " ✎"
                            else -> ""
                        }
                        Text(
                            seg.text + suffix,
                            style = bodyStyle,
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(bg, RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .combinedClickable(
                                    onClick = { onPlayFromSeg?.invoke(seg.segIndex) },
                                    onLongClick = { annotating = seg },
                                ),
                            fontWeight = if (highlighted) FontWeight.SemiBold else null,
                        )
                    }
                }
            } else {
                val lines = remember(content) { content.split('\n').filter { it.isNotBlank() } }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = bodyStyle,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // 阅读器内目录抽屉：快速切换章节，不必离开阅读页
    if (chapters != null && showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxHeight(0.7f)) {
                items(chapters) { ch ->
                    Text(
                        ch.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ch.id == currentChapterId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { showToc = false; onOpenChapter?.invoke(ch) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    // 长按段落 → 标注菜单：加/取消书签、高亮选色、写笔记
    annotating?.let { seg ->
        val existing = annBySeg[seg.segIndex]
        val hasBookmark = existing?.type == AnnotationEntity.TYPE_BOOKMARK
        ModalBottomSheet(onDismissRequest = { annotating = null }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(seg.text, style = bodyStyle.copy(fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    if (hasBookmark) onDeleteAnnotation?.invoke(existing!!.id)
                    else onAddAnnotation?.invoke(seg, AnnotationEntity.TYPE_BOOKMARK, null, null)
                    annotating = null
                }) { Text(if (hasBookmark) "取消书签 🔖" else "加书签 🔖") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("高亮：", style = MaterialTheme.typography.labelMedium)
                    highlightColors.forEach { c ->
                        Box(
                            Modifier.padding(4.dp).size(28.dp)
                                .background(Color(android.graphics.Color.parseColor(c)), RoundedCornerShape(6.dp))
                                .clickable {
                                    onAddAnnotation?.invoke(seg, AnnotationEntity.TYPE_HIGHLIGHT, null, c)
                                    annotating = null
                                },
                        )
                    }
                }
                TextButton(onClick = { noteTarget = seg; noteText = existing?.noteText ?: ""; annotating = null }) { Text("写笔记 ✎") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    // 笔记输入对话框
    noteTarget?.let { seg ->
        AlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("笔记") },
            text = { OutlinedTextField(value = noteText, onValueChange = { noteText = it }, label = { Text("笔记内容") }) },
            confirmButton = { TextButton(onClick = { onAddAnnotation?.invoke(seg, AnnotationEntity.TYPE_NOTE, noteText, null); noteTarget = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { noteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
fun PlaybackBar(
    controller: PlaybackController,
    chapterId: Long,
    onSeekToMs: ((Long) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    var speed by remember { mutableFloatStateOf(AudioPlaybackService.currentSpeed()) }
    var playing by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val curSeg by controller.currentSeg.collectAsState()
    var position by remember { mutableFloatStateOf(0f) }
    var total by remember { mutableFloatStateOf(0f) }
    var dragMs by remember { mutableStateOf<Float?>(null) }

    // 周期刷新进度：位置读服务（轻量），总时长含未就绪段估算；切章先重置避免残留旧章
    LaunchedEffect(chapterId) {
        position = 0f
        total = 0f
        dragMs = null
        while (true) {
            position = controller.chapterPositionMs().toFloat()
            total = runCatching { controller.chapterTotalMs(chapterId).toFloat() }.getOrDefault(total)
            delay(1000)
        }
    }

    Surface(shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 整章进度条：拖动时本地值，松开才提交 seek（参考 Readest TTSScrubber）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime((dragMs ?: position).toLong()), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = (dragMs ?: position).coerceIn(0f, total.coerceAtLeast(1f)),
                    onValueChange = { dragMs = it },
                    onValueChangeFinished = {
                        val t = dragMs ?: position
                        dragMs = null
                        onSeekToMs?.invoke(t.toLong())
                    },
                    valueRange = 0f..total.coerceAtLeast(1f),
                    enabled = total > 0,
                    modifier = Modifier.weight(1f),
                )
                Text("-" + formatTime(total.coerceAtLeast(position).toLong()), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左/右 = 上一章/下一章
                IconButton(onClick = { onPrevChapter?.invoke() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一章") }
                IconButton(onClick = {
                    if (curSeg < 0) {
                        Toast.makeText(context, "正在准备语音，请稍候", Toast.LENGTH_SHORT).show()
                    } else {
                        if (playing) controller.pause() else controller.resume()
                        playing = !playing
                    }
                }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { onNextChapter?.invoke() }) { Icon(Icons.Default.SkipNext, contentDescription = "下一章") }
                var sleepMinutes by remember { mutableIntStateOf(0) }
                // -1 表示「播完本章停止」；其余为倒计时分钟数
                val sleepOptions = intArrayOf(0, 15, 30, 60, -1)
                val remaining by AudioPlaybackService.remainingMs.collectAsState()
                val sleepLabel = when {
                    remaining > 0 -> "☾${((remaining + 59_999) / 60_000)}分"
                    sleepMinutes == -1 -> "☾本章"
                    else -> "☾"
                }
                TextButton(onClick = {
                    val next = sleepOptions[(sleepOptions.indexOf(sleepMinutes) + 1) % sleepOptions.size]
                    sleepMinutes = next
                    when (next) {
                        -1 -> controller.sleepAfterChapter()
                        0 -> controller.cancelSleepTimer()
                        else -> controller.sleepTimer(next)
                    }
                }) {
                    Text(sleepLabel, style = MaterialTheme.typography.labelMedium)
                }
                // 右端预留 76dp，避开右下角悬浮的「查看后台请求」FAB（否则会盖住倍速滑块右半边）
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 76.dp)) {
                    Text("${(speed * 10).toInt() / 10f}x", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = speed,
                        onValueChange = { speed = it; controller.setSpeed(it) },
                        valueRange = 0.5f..2f,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

/** mm:ss 格式化（进度条时间显示）。 */
private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
