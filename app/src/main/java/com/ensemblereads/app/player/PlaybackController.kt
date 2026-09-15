package com.ensemblereads.app.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.ReadingStatsEntity
import com.ensemblereads.app.data.repo.ReadingStatsRepo
import com.ensemblereads.app.debug.RequestLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * 播放控制器：把章节合成器与播放服务串起来，向 UI 暴露当前段索引。
 * 边合成边播放：每段就绪即喂给播放器，开书后很快出声，不必等整章合成完。
 */
class PlaybackController(
    context: Context,
    private val synthesizer: ChapterSynthesizer,
    private val statsRepo: ReadingStatsRepo,
) {
    private val appContext = context.applicationContext
    val currentSeg: StateFlow<Int> = AudioPlaybackService.currentSegment
    private val service get() = AudioPlaybackService.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var statsJob: Job? = null

    /** 运行序号：切书/切章时自增，使旧 run 的回调与进度收集全部失效（修复切书残留/拼接）。 */
    @Volatile
    private var runId = 0

    /** 最近一次 startFrom 的书/章（供断网恢复自动补合成等）。 */
    @Volatile
    var currentBook: BookEntity? = null
        private set
    @Volatile
    var currentChapter: ChapterEntity? = null
        private set

    /**
     * 从 [chapter] 的 [fromSeg] 段开始朗读。
     * ensureChapter 的 onReady 回调逐段触发：首段就绪即播放，后续段边合成边追加。
     * 同时监听段切换，节流地把阅读进度写入 Book 表。
     */
    suspend fun startFrom(book: BookEntity, chapter: ChapterEntity, fromSeg: Int) {
        val s = ensureService() ?: return
        // 应用设置里的默认倍速
        s.setSpeed(synthesizer.defaultSpeed())
        // 锁屏/媒体通知显示「书名 · 章节名」
        s.updateMediaMetadata(book.title, chapter.title)
        val myRun = ++runId
        currentBook = book
        currentChapter = chapter
        val files = mutableListOf<File>()
        val segIndexes = mutableListOf<Int>()
        val durations = mutableListOf<Long>()
        var started = false
        val ready = synthesizer.ensureChapter(book, chapter, fromSeg) { seg ->
            // 已被更新的 run 取代：丢弃旧段，避免追加进新章播放列表
            if (myRun != runId) return@ensureChapter
            val f = seg.audioPath?.let(::File)
            if (f != null) {
                files.add(f)
                segIndexes.add(seg.segIndex)
                durations.add(seg.durationMs)
                if (!started) {
                    started = true
                    s.play(files.toList(), segIndexes.toList(), durations.toList(), 0)
                } else {
                    s.appendMedia(listOf(f), listOf(seg.segIndex), listOf(seg.durationMs))
                }
            }
        }
        if (myRun != runId) return
        // 兜底：极端情况下回调未触发但 ensureChapter 返回了就绪段（如全部为已缓存且回调异常）
        if (!started && ready.isNotEmpty()) {
            val items = ready.sortedBy { it.segIndex }
            s.play(
                items.map { File(it.audioPath!!) },
                items.map { it.segIndex },
                items.map { it.durationMs },
                0,
            )
        }
        if (myRun != runId) return
        // 记录阅读进度：跟随 currentSeg，段切换时节流写库
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastSavedSeg = -1
            var lastSavedAt = 0L
            AudioPlaybackService.currentSegment.collect { seg ->
                val now = System.currentTimeMillis()
                if (seg >= 0 && seg != lastSavedSeg && now - lastSavedAt >= 1000) {
                    lastSavedSeg = seg
                    lastSavedAt = now
                    synthesizer.saveProgress(book.copy(lastChapterId = chapter.id, lastSegIndex = seg))
                }
            }
        }
        // 阅读统计（H21）：按真实播放时长累计（轮询 isPlaying，5s 写库一次）
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastPoll = System.currentTimeMillis()
            var accumulated = 0L
            while (true) {
                delay(5000)
                val now = System.currentTimeMillis()
                if (service?.isPlaying() == true) accumulated += (now - lastPoll)
                lastPoll = now
                if (accumulated >= 1000) {
                    val cur = statsRepo.byBook(book.id)
                    statsRepo.upsert(
                        ReadingStatsEntity(
                            bookId = book.id,
                            listenedMs = (cur?.listenedMs ?: 0L) + accumulated,
                            lastListenedAt = now,
                        ),
                    )
                    accumulated = 0
                }
            }
        }
        // 整章合成完成：通知服务可停止（播放列表若已播完则此时自停）
        s.notifySynthesisDone()
        // 预取后续章节（受缓存上限约束）
        synthesizer.prefetch(book, chapter.id, 10)
    }

    /** 停止当前播放并清空播放列表（切书/切章前调用，防止旧章残留）。 */
    fun stopCurrent() {
        runId++
        progressJob?.cancel()
        progressJob = null
        statsJob?.cancel()
        statsJob = null
        currentBook = null
        currentChapter = null
        service?.pause()
        service?.resetForChapter()
    }

    /** 从指定段开始朗读（阅读器点击段落文字触发）。 */
    fun playFromSeg(book: BookEntity, chapter: ChapterEntity, segIndex: Int) {
        scope.launch {
            try {
                startFrom(book, chapter, segIndex)
            } catch (e: Exception) {
                android.util.Log.w("PlaybackController", "playFromSeg($segIndex) 失败", e)
            }
        }
    }

    /** 当前章内播放位置(ms)，供进度条（轻量，直接读服务）。 */
    fun chapterPositionMs(): Long = service?.chapterPositionMs() ?: 0

    /** 整章总时长(ms，含未就绪段估算)，供进度条。 */
    suspend fun chapterTotalMs(chapterId: Long): Long = synthesizer.chapterTotalMs(chapterId)

    /**
     * 拖动进度条到章内 ms：目标段已就绪 → 播放列表内直接 seek；
     * 未就绪 → 从该段开始重新合成播放（复用 startFrom 的 fromSeg 语义）。
     */
    fun seekToChapterMs(book: BookEntity, chapter: ChapterEntity, totalMs: Long) {
        scope.launch {
            try {
                val s = ensureService() ?: return@launch
                val (segIndex, offsetMs) = synthesizer.segAtMs(chapter.id, totalMs)
                if (!s.seekToSeg(segIndex, offsetMs)) {
                    startFrom(book, chapter, segIndex)
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackController", "seekToChapterMs($totalMs) 失败", e)
            }
        }
    }

    /** 确保播放服务就绪：未运行（如播完自停后 instance 清空）则重新启动并等待，最多 5s。 */
    private suspend fun ensureService(): AudioPlaybackService? {
        AudioPlaybackService.instance?.let { return it }
        RequestLogger.log("播放", "播放服务未运行，正在重新启动...")
        try {
            val intent = Intent(appContext, AudioPlaybackService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            RequestLogger.log("播放", "启动播放服务失败: ${e.javaClass.simpleName}: ${e.message}", ok = false)
            return null
        }
        var waited = 0L
        while (AudioPlaybackService.instance == null && waited < 5000) {
            delay(100)
            waited += 100
        }
        return AudioPlaybackService.instance
    }

    fun pause() = service?.pause()
    fun resume() = service?.resume()
    fun nextSeg() = service?.next()
    fun prevSeg() = service?.prev()
    fun setSpeed(s: Float) = service?.setSpeed(s)
    fun sleepTimer(minutes: Int) = service?.sleepTimer(minutes)
    fun sleepAfterChapter() = service?.sleepAfterChapterMode()
    fun cancelSleepTimer() = service?.cancelSleepTimer()

    /** 网络恢复后补合成当前章的未就绪段（FAILED/PENDING），静默执行，不打断播放。 */
    fun retryCurrentChapter() {
        val b = currentBook ?: return
        val c = currentChapter ?: return
        scope.launch {
            try {
                synthesizer.ensureChapter(b, c)
            } catch (e: Exception) {
                android.util.Log.w("PlaybackController", "断网恢复补合成失败", e)
            }
        }
    }
}
