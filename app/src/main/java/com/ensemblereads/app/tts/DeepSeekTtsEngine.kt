package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import java.io.File

/** 直连实现：DeepSeek 解析 + RoleAllocator 分配 + Edge TTS 合成。 */
class DeepSeekTtsEngine(private val apiKey: String) : TtsEngine {
    private val deepseek = DeepSeekClient(apiKey)
    private val edge = EdgeTtsClient()

    override suspend fun parseSegments(chapterId: Long, text: String): List<Segment> =
        deepseek.parse(chapterId, text)

    override suspend fun allocateVoices(
        segments: List<Segment>,
        overrides: Map<String, RoleEntity>,
    ): Map<String, Voice> = RoleAllocator.allocate(segments, overrides)

    override suspend fun synthesize(text: String, voice: Voice, dest: File) {
        val ok = edge.synthesize(text, voice.id, voice.pitch, voice.rate, dest)
        if (!ok) throw RuntimeException("EdgeTTS synthesize failed")
    }
}
