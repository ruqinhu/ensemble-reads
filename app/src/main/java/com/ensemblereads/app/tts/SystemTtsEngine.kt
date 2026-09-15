package com.ensemblereads.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ensemblereads.app.data.db.RoleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统 TTS 引擎（离线兜底）：用 Android [TextToSpeech.synthesizeToFile] 合成。
 * 与 DeepSeek/EdgeTTS 断网时降级使用，保证离线也能朗读（无角色区分）。
 */
class SystemTtsEngine(private val context: Context) : TtsEngine {
    @Volatile
    private var tts: TextToSpeech? = null

    private suspend fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        return suspendCancellableCoroutine { cont ->
            // lateinit 先声明再赋值：onInit 回调在构造返回后（异步）触发，此时 created 已初始化
            lateinit var created: TextToSpeech
            created = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    created.language = Locale.CHINA
                    created.setSpeechRate(1f)
                    created.setPitch(1f)
                    tts = created
                    if (cont.isActive) cont.resume(created)
                } else if (cont.isActive) {
                    cont.resumeWithException(RuntimeException("系统 TTS 引擎不可用"))
                }
            }
            cont.invokeOnCancellation { runCatching { created.shutdown() } }
        }
    }

    override suspend fun parseSegments(chapterId: Long, text: String): List<Segment> = fallbackSegments(text)

    override suspend fun allocateVoices(
        segments: List<Segment>,
        overrides: Map<String, RoleEntity>,
    ): Map<String, Voice> = mapOf(RoleAllocator.NARRATOR to Voice("system"))

    override suspend fun synthesize(text: String, voice: Voice, dest: File) {
        val t = ensureTts()
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val id = "er_${System.currentTimeMillis()}"
                t.synthesizeToFile(text, Bundle.EMPTY, dest, id)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resumeWithException(RuntimeException("系统TTS合成失败"))
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(RuntimeException("系统TTS合成失败 code=$errorCode"))
                    }
                })
            }
        }
    }

    /** 解析失败降级：按句切成旁白段，保证整章仍可朗读。 */
    private fun fallbackSegments(content: String): List<Segment> {
        val parts = content.split(Regex("\\n+|(?<=[。！？！？])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(Segment(RoleAllocator.NARRATOR, content))
        return parts.map { Segment(RoleAllocator.NARRATOR, it) }
    }
}
