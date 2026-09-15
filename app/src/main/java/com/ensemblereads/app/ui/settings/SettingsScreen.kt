package com.ensemblereads.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.player.ChapterSynthesizer
import com.ensemblereads.app.ui.theme.AppTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.ensemblereads.app.data.backup.BackupManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, synthesizer: ChapterSynthesizer?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var cacheLimit by remember { mutableStateOf("100") }
    var speed by remember { mutableFloatStateOf(1f) }
    var theme by remember { mutableStateOf(AppTheme.SYSTEM) }
    var fontSize by remember { mutableFloatStateOf(17f) }
    var lineHeight by remember { mutableFloatStateOf(28f) }
    var fontFamily by remember { mutableStateOf("default") }
    var totalListenedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        totalListenedMs = container.statsRepo.totalMs()
    }

    LaunchedEffect(Unit) {
        key = container.settings.get(SettingsManager.KEY_DEEPSEEK_KEY) ?: ""
        cacheLimit = container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: "100"
        (container.settings.get(SettingsManager.KEY_DEFAULT_SPEED) ?: "1.0").toFloatOrNull()?.let { speed = it }
        container.settings.get(SettingsManager.KEY_THEME)?.let { v ->
            AppTheme.entries.firstOrNull { it.name.lowercase() == v.lowercase() }?.let { theme = it }
        }
        (container.settings.get(SettingsManager.KEY_FONT_SIZE) ?: "17").toFloatOrNull()?.let { fontSize = it.coerceIn(16f, 24f) }
        (container.settings.get(SettingsManager.KEY_LINE_HEIGHT) ?: "28").toFloatOrNull()?.let { lineHeight = it.coerceIn(26f, 40f) }
        container.settings.get(SettingsManager.KEY_FONT_FAMILY)?.let { fontFamily = it }
    }

    Column(
        // 可滚动 + 键盘适配：输入 Key/缓存上限时内容可上滑，不被键盘遮挡/挤出
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)
        Text(
            "累计收听：${formatDuration(totalListenedMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cacheLimit, onValueChange = { cacheLimit = it },
            label = { Text("缓存上限(章)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("默认倍速")
            Spacer(Modifier.width(12.dp))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f)
            Spacer(Modifier.width(8.dp))
            Text("${(speed * 10).toInt() / 10f}x")
        }

        // 阅读主题（D12）
        var showThemeMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("阅读主题", modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { showThemeMenu = true }) { Text(themeLabel(theme)) }
                DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                    AppTheme.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(themeLabel(t)) }, onClick = { theme = t; showThemeMenu = false })
                    }
                }
            }
        }

        // 阅读排版（D11）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号")
            Spacer(Modifier.width(12.dp))
            Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 16f..24f)
            Spacer(Modifier.width(8.dp))
            Text("${fontSize.toInt()}sp")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距")
            Spacer(Modifier.width(12.dp))
            Slider(value = lineHeight, onValueChange = { lineHeight = it }, valueRange = 26f..40f)
            Spacer(Modifier.width(8.dp))
            Text("${lineHeight.toInt()}sp")
        }
        var showFontMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字体", modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { showFontMenu = true }) { Text(fontFamilyLabel(fontFamily)) }
                DropdownMenu(expanded = showFontMenu, onDismissRequest = { showFontMenu = false }) {
                    listOf("default" to "系统", "serif" to "衬线", "monospace" to "等宽").forEach { (k, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { fontFamily = k; showFontMenu = false })
                    }
                }
            }
        }

        Button(onClick = {
            scope.launch {
                container.settings.put(SettingsManager.KEY_DEEPSEEK_KEY, key.trim())
                container.settings.put(SettingsManager.KEY_CACHE_LIMIT, cacheLimit.trim())
                container.settings.put(SettingsManager.KEY_DEFAULT_SPEED, speed.toString())
                container.settings.put(SettingsManager.KEY_THEME, theme.name.lowercase())
                container.settings.put(SettingsManager.KEY_FONT_SIZE, fontSize.toString())
                container.settings.put(SettingsManager.KEY_LINE_HEIGHT, lineHeight.toString())
                container.settings.put(SettingsManager.KEY_FONT_FAMILY, fontFamily)
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存") }

        synthesizer?.let {
            TextButton(onClick = { scope.launch { it.clearAllCache() } }) { Text("清空缓存") }
        }

        // 备份导入/导出（H20）
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) scope.launch {
                val ok = BackupManager.export(context, container, uri)
                Toast.makeText(context, if (ok) "备份已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch {
                val ok = BackupManager.import(context, container, uri)
                Toast.makeText(context, if (ok) "备份已导入" else "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
        Row {
            TextButton(onClick = { exportLauncher.launch("ensemble_backup.json") }) { Text("导出备份") }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("导入备份") }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    return "${totalMin / 60} 小时 ${totalMin % 60} 分"
}

private fun themeLabel(t: AppTheme) = when (t) {
    AppTheme.SYSTEM -> "跟随系统"
    AppTheme.LIGHT -> "亮色"
    AppTheme.DARK -> "暗色"
    AppTheme.PARCHMENT -> "羊皮纸"
    AppTheme.FOREST -> "墨绿"
}

private fun fontFamilyLabel(key: String) = when (key) {
    "serif" -> "衬线"
    "monospace" -> "等宽"
    else -> "系统"
}
