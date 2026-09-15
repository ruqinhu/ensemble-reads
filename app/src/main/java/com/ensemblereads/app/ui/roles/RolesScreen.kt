package com.ensemblereads.app.ui.roles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.RoleEntity
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.tts.RoleAllocator
import kotlinx.coroutines.launch

val VOICE_LABELS = mapOf(
    "zh-CN-YunyangNeural" to "云扬(男·沉稳)",
    "zh-CN-YunxiNeural" to "云希(男·青年)",
    "zh-CN-YunjianNeural" to "云健(男·阳刚)",
    "zh-CN-YunxiaNeural" to "云夏(男·少年)",
    "zh-CN-XiaoxiaoNeural" to "晓晓(女·温暖)",
    "zh-CN-XiaoyiNeural" to "晓伊(女·活泼)",
    "zh-CN-XiaoxuanNeural" to "晓萱(女·成熟)",
)

/**
 * 章节角色管理：聚合本段所有角色，未分配角色标 ⚠；点击指定音色，
 * 保存 Role 后通过 [onRolesChanged] 触发该章重合成。
 */
@Composable
fun RolesScreen(
    bookId: Long,
    segments: List<SegmentEntity>,
    container: AppContainer,
    onRolesChanged: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var roles by remember { mutableStateOf<List<RoleEntity>>(emptyList()) }
    LaunchedEffect(bookId) { roles = container.roleRepo.byBook(bookId) }
    var editing by remember { mutableStateOf<String?>(null) }

    val counts = segments.groupingBy { it.speaker }.eachCount()
        .entries.sortedByDescending { it.value }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("角色配置", style = MaterialTheme.typography.headlineLarge) }
        items(counts) { (name, count) ->
            val role = roles.firstOrNull { it.roleName == name }
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Text(
                        when {
                            name == RoleAllocator.NARRATOR -> role?.let { VOICE_LABELS[it.voice] ?: it.voice } ?: "旁白(自动)"
                            role != null -> VOICE_LABELS[role.voice] ?: role.voice
                            else -> "未分配 ⚠"
                        }
                    )
                },
                trailingContent = { Text("$count 次") },
                modifier = Modifier.clickable {
                    // 所有角色（含旁白）均可改音色；旁白未配置时保持自动
                    editing = name
                },
            )
        }
        item {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }

    editing?.let { name ->
        VoicePickerDialog(
            title = "为「$name」选择音色",
            fallbackVoices = VOICE_LABELS,
            onSelect = { voiceId ->
                scope.launch {
                    container.roleRepo.upsert(
                        RoleEntity(bookId = bookId, roleName = name, voice = voiceId))
                    roles = container.roleRepo.byBook(bookId) // 立即刷新列表
                    editing = null
                    onRolesChanged()
                }
            },
            onDismiss = { editing = null },
        )
    }
}
