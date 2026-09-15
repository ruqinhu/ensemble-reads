package com.ensemblereads.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.debug.RequestLogger

/**
 * 全局悬浮按钮 + 后台请求日志面板：所有页面右下角可见。
 * 点击悬浮按钮展开日志面板（DeepSeek 解析 / Edge TTS 合成 / 章节合成等实时记录），
 * 可清除，可「返回」关闭面板回到原页面。
 */
@Composable
fun RequestLogFloatingPanel() {
    var open by remember { mutableStateOf(false) }
    val entries by RequestLogger.entries.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (open) {
            // 半透明遮罩：拦截点击，避免误触下层页面
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black.copy(alpha = 0.45f),
            ) {}
            Surface(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("后台请求日志", style = MaterialTheme.typography.titleMedium)
                        Row {
                            IconButton(onClick = { RequestLogger.clear() }) {
                                Icon(Icons.Default.Delete, contentDescription = "清除")
                            }
                            IconButton(onClick = { open = false }) {
                                Icon(Icons.Default.Close, contentDescription = "返回")
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    "暂无请求记录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                        items(entries) { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    RequestLogger.formatTime(e.time),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "[${e.tag}] ${e.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (e.ok) MaterialTheme.colorScheme.onSurface else Color(0xFFD32F2F),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            FloatingActionButton(
                onClick = { open = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Default.BugReport, contentDescription = "查看后台请求")
            }
        }
    }
}
