package com.ensemblereads.app.ui.roles

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ensemblereads.app.debug.RequestLogger
import com.ensemblereads.app.tts.EdgeTtsClient
import com.ensemblereads.app.tts.VoiceInfo
import kotlinx.coroutines.launch
import java.io.File

/**
 * 音色选择对话框（F16）：联网拉取 Edge TTS 全量音色，支持搜索与试听；
 * 拉取失败回退到内置 VOICE_LABELS。点击某音色 → onSelect(voiceId)。
 */
@Composable
fun VoicePickerDialog(
    title: String,
    fallbackVoices: Map<String, String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember {
        mutableStateOf(
            fallbackVoices.entries.map { VoiceInfo(it.key, it.value, "", "zh-CN") },
        )
    }
    var query by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

    LaunchedEffect(Unit) {
        runCatching { EdgeTtsClient().listVoices() }
            .onSuccess { list -> if (list.isNotEmpty()) voices = list }
            .onFailure { RequestLogger.log("EdgeTTS", "音色列表拉取失败，用内置列表", ok = false) }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
            runCatching { File(context.cacheDir, "voice_preview.mp3").delete() }
        }
    }

    fun preview(voiceId: String) {
        if (previewing == voiceId) { runCatching { if (player.isPlaying) player.stop() }; previewing = null; return }
        previewing = voiceId
        scope.launch {
            try {
                val dest = File(context.cacheDir, "voice_preview.mp3")
                runCatching { dest.delete() }
                val ok = EdgeTtsClient().synthesize("这是一段音色试听。", voiceId, 0, 0, dest)
                if (!ok) { previewing = null; return@launch }
                runCatching { if (player.isPlaying) player.stop() }
                player.reset()
                player.setDataSource(dest.absolutePath)
                player.prepare()
                player.setOnCompletionListener { previewing = null }
                player.start()
            } catch (e: Exception) {
                RequestLogger.log("EdgeTTS", "试听失败: ${e.javaClass.simpleName}: ${e.message}", ok = false)
                previewing = null
            }
        }
    }

    val filtered = voices.filter {
        query.isBlank() || it.label.contains(query, true) || it.locale.contains(query, true) || it.id.contains(query, true)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxHeight(0.8f)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("搜索音色") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(filtered) { v ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onSelect(v.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${v.locale} · ${v.gender}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { preview(v.id) }) {
                                Text(if (previewing == v.id) "停止" else "试听")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
