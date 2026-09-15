package com.ensemblereads.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.data.repo.AppContainer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest

/**
 * 书内全文搜索（E14）：输入关键词，检索本书「章节正文」与「段落文本」，
 * 命中项点击跳转到对应章节的阅读器。放在 ModalBottomSheet 内使用。
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchSheet(
    container: AppContainer,
    bookId: Long,
    onOpenChapter: (Long) -> Unit,          // chapterId
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val queryFlow = remember { MutableStateFlow("") }
    var chapterHits by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var segHits by remember { mutableStateOf<List<SegmentEntity>>(emptyList()) }

    // 防抖搜索：停顿 400ms 后查询章节 + 段落
    LaunchedEffect(Unit) {
        queryFlow.debounce(400).collectLatest { q ->
            if (q.isBlank()) { chapterHits = emptyList(); segHits = emptyList(); return@collectLatest }
            chapterHits = container.chapterRepo.searchChapters(bookId, q)
                .map { it.id to it.title }
            segHits = container.segmentRepo.searchSegments(bookId, q)
        }
    }

    Column(Modifier.fillMaxHeight(0.8f).padding(16.dp)) {
        Text("书内搜索", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; queryFlow.value = it },
            label = { Text("搜索正文") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            if (chapterHits.isNotEmpty()) {
                item { Text("章节命中", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                items(chapterHits) { (chapterId, title) ->
                    Text(title, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onDismiss(); onOpenChapter(chapterId) }
                            .padding(vertical = 8.dp))
                }
            }
            if (segHits.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("段落命中", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(segHits) { seg ->
                    Column(Modifier.fillMaxWidth()
                        .clickable { onDismiss(); onOpenChapter(seg.chapterId) }
                        .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(seg.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("→ 章 ${seg.chapterId} · 段 ${seg.segIndex}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (query.isNotBlank() && chapterHits.isEmpty() && segHits.isEmpty()) {
                item {
                    Text("无结果", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}
