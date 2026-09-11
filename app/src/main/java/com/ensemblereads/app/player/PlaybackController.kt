package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 播放控制器：把章节合成器与播放服务串起来，向 UI 暴露当前段索引。
 * 服务实例通过 [AudioPlaybackService.instance] 惰性获取（AppRoot 负责启动）。
 */
class PlaybackController(
    private val synthesizer: ChapterSynthesizer,
) {
    val currentSeg: StateFlow<Int> = AudioPlaybackService.currentSegment
    private val service get() = AudioPlaybackService.instance

    /**
     * 从 [chapter] 的 [fromSeg] 段开始朗读。
     * 流程：ensureChapter（解析+逐段合成，边听边缓存）→ 播放 → 预取后续 10 章。
     */
    suspend fun startFrom(book: BookEntity, chapter: ChapterEntity, fromSeg: Int) {
        val s = service ?: return
        val ready = synthesizer.ensureChapter(book, chapter, fromSeg)
        if (ready.isEmpty()) return
        val files = ready.sortedBy { it.segIndex }.map { File(it.audioPath!!) }
        val startIdx = ready.indexOfFirst { it.segIndex >= fromSeg }.coerceAtLeast(0)
        s.play(files, startIdx)
        synthesizer.prefetch(book, chapter.id, 10)
    }

    fun pause() = service?.pause()
    fun resume() = service?.resume()
    fun nextSeg() = service?.next()
    fun prevSeg() = service?.prev()
    fun setSpeed(s: Float) = service?.setSpeed(s)
    fun sleepTimer(minutes: Int) = service?.sleepTimer(minutes)
}
