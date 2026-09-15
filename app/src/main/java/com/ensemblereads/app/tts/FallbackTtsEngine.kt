package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import java.io.File

/**
 * 主引擎（DeepSeek 解析 + Edge TTS 合成）失败时降级到 [fallback]（系统 TTS）。
 * 「粘性」降级：主引擎任一环节失败后，本会话后续直接用 fallback，避免反复撞 Edge TTS 的慢超时。
 */
class FallbackTtsEngine(
    private val primary: TtsEngine,
    private val fallback: TtsEngine,
) : TtsEngine {
    @Volatile
    private var useFallback = false

    override suspend fun parseSegments(chapterId: Long, text: String): List<Segment> {
        if (useFallback) return fallback.parseSegments(chapterId, text)
        return try {
            primary.parseSegments(chapterId, text)
        } catch (e: Exception) {
            useFallback = true
            fallback.parseSegments(chapterId, text)
        }
    }

    override suspend fun allocateVoices(
        segments: List<Segment>,
        overrides: Map<String, RoleEntity>,
    ): Map<String, Voice> {
        if (useFallback) return fallback.allocateVoices(segments, overrides)
        return try {
            primary.allocateVoices(segments, overrides)
        } catch (e: Exception) {
            useFallback = true
            fallback.allocateVoices(segments, overrides)
        }
    }

    override suspend fun synthesize(text: String, voice: Voice, dest: File) {
        if (useFallback) { fallback.synthesize(text, voice, dest); return }
        try {
            primary.synthesize(text, voice, dest)
        } catch (e: Exception) {
            useFallback = true
            fallback.synthesize(text, voice, dest)
        }
    }
}
