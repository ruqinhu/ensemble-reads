package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import java.io.File

/**
 * 多角色朗读引擎抽象接口。MVP 用 [DeepSeekTtsEngine]（直连）；将来可替换为
 * 情感 TTS（EmotiVoice / 云服务）实现，UI 与播放层不变。
 */
interface TtsEngine {
    /** 把章节纯文本解析为角色分段（DeepSeek）。失败抛异常。 */
    suspend fun parseSegments(chapterId: Long, text: String): List<Segment>

    /** 角色分配：自动特征匹配 + 用户 overrides 覆盖，返回 角色名 → Voice。 */
    suspend fun allocateVoices(
        segments: List<Segment>,
        overrides: Map<String, RoleEntity>,
    ): Map<String, Voice>

    /** 合成一段文本到 dest 文件。失败抛异常。 */
    suspend fun synthesize(text: String, voice: Voice, dest: File)
}
