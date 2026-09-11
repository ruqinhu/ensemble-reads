package com.ensemblereads.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.player.ChapterSynthesizer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, synthesizer: ChapterSynthesizer?) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var cacheLimit by remember { mutableStateOf("100") }
    var speed by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        key = container.settings.get(SettingsManager.KEY_DEEPSEEK_KEY) ?: ""
        cacheLimit = container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: "100"
        (container.settings.get(SettingsManager.KEY_DEFAULT_SPEED) ?: "1.0").toFloatOrNull()?.let { speed = it }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)

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
        Button(onClick = {
            scope.launch {
                container.settings.put(SettingsManager.KEY_DEEPSEEK_KEY, key)
                container.settings.put(SettingsManager.KEY_CACHE_LIMIT, cacheLimit)
                container.settings.put(SettingsManager.KEY_DEFAULT_SPEED, speed.toString())
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存") }

        synthesizer?.let {
            TextButton(onClick = { scope.launch { it.clearAllCache() } }) { Text("清空缓存") }
        }
    }
}
