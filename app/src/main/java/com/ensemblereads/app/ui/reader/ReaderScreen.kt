package com.ensemblereads.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.player.PlaybackController
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapterTitle: String,
    content: String,
    segments: List<SegmentEntity>,
    controller: PlaybackController?,
    onOpenRoles: () -> Unit,
) {
    var cur by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        controller?.currentSeg?.collectLatest { cur = it }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { TextButton(onClick = onOpenRoles) { Text("角色") } },
            )
        },
        // 无朗读引擎时不显示播放条
        bottomBar = { controller?.let { PlaybackBar(it) } },
    ) { pad ->
        val contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        if (segments.isNotEmpty()) {
            // 有角色分段：按段渲染并高亮当前朗读句
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = contentPadding,
            ) {
                itemsIndexed(segments) { i, seg ->
                    val highlighted = i == cur
                    Text(
                        seg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        fontWeight = if (highlighted) FontWeight.SemiBold else null,
                    )
                }
            }
        } else {
            // 尚无解析段（未配引擎 / 合成未完成）：直接展示原文
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = contentPadding,
            ) {
                items(content.split('\n').filter { it.isNotBlank() }) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackBar(controller: PlaybackController) {
    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.prevSeg() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一句") }
            IconButton(onClick = {
                if (playing) controller.pause() else controller.resume()
                playing = !playing
            }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            IconButton(onClick = { controller.nextSeg() }) { Icon(Icons.Default.SkipNext, contentDescription = "下一句") }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
