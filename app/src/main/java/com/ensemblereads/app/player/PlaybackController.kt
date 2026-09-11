package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 播放控制器：把章节合成器与播放服务串起来，向 UI 暴露当前段索引。
 * 边合成边播放：每段就绪即喂给播放器，开书后很快出声，不必等整章合成完。
 */
class PlaybackController(
    private val synthesizer: ChapterSynthesizer,
) {
    val currentSeg: StateFlow<Int> = AudioPlaybackService.currentSegment
    private val service get() = AudioPlaybackService.instance

    /**
     * 从 [chapter] 的 [fromSeg] 段开始朗读。
     * ensureChapter 的 onReady 回调逐段触发：首段就绪即播放，后续段边合成边追加。
     */
    suspend fun startFrom(book: BookEntity, chapter: ChapterEntity, fromSeg: Int) {
        val s = service ?: return
        val files = mutableListOf<File>()
        val segIndexes = mutableListOf<Int>()
        var started = false
        val ready = synthesizer.ensureChapter(book, chapter, fromSeg) { seg ->
            val f = seg.audioPath?.let(::File)
            if (f != null) {
                files.add(f)
                segIndexes.add(seg.segIndex)
                if (!started) {
                    started = true
                    s.play(files.toList(), segIndexes.toList(), 0)
                } else {
                    s.appendMedia(listOf(f), listOf(seg.segIndex))
                }
            }
        }
        // 兜底：极端情况下回调未触发但 ensureChapter 返回了就绪段（如全部为已缓存且回调异常）
        if (!started && ready.isNotEmpty()) {
            val items = ready.sortedBy { it.segIndex }
            s.play(items.map { File(it.audioPath!!) }, items.map { it.segIndex }, 0)
        }
        // 预取后续章节（受缓存上限约束）
        synthesizer.prefetch(book, chapter.id, 10)
    }

    fun pause() = service?.pause()
    fun resume() = service?.resume()
    fun nextSeg() = service?.next()
    fun prevSeg() = service?.prev()
    fun setSpeed(s: Float) = service?.setSpeed(s)
    fun sleepTimer(minutes: Int) = service?.sleepTimer(minutes)
}
