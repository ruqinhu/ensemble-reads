package com.ensemblereads.app.player

import android.media.MediaMetadataRetriever
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.debug.RequestLogger
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.ParseCacheEntity
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.tts.Segment
import com.ensemblereads.app.tts.TtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 章节合成器：解析→分配→逐段合成（边听边缓存），支持预取、批量缓存与超限淘汰。 */
class ChapterSynthesizer(
    private val container: AppContainer,
    private val engine: TtsEngine,
    private val audioRoot: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每章一把锁：防止同一章被并发合成（reader 自动播放 + 角色重合成 + 预取）产生重复段或写坏音频。 */
    private val chapterLocks = ConcurrentHashMap<Long, Mutex>()

    private suspend fun <T> withChapterLock(chapterId: Long, block: suspend () -> T): T =
        chapterLocks.computeIfAbsent(chapterId) { Mutex() }.withLock { block() }

    private fun chapterDir(bookId: Long, chapterId: Long) = File(audioRoot, "$bookId/$chapterId")
    private fun segFile(bookId: Long, chapterId: Long, n: Int) = File(chapterDir(bookId, chapterId), "seg_$n.mp3")

    /** 播放器回调单独 try：异常只记日志，不把已合成段误标 FAILED，也不中断后续合成。 */
    private fun safeOnReady(onReady: ((SegmentEntity) -> Unit)?, seg: SegmentEntity) {
        if (onReady == null) return
        try {
            onReady(seg)
        } catch (e: Exception) {
            android.util.Log.w("ChapterSynthesizer", "onReady 播放器调用失败（段 ${seg.segIndex}）", e)
        }
    }

    /**
     * 保证章内从 fromSeg 起的段就绪：先解析（或读 ParseCache），再逐段合成。
     * 每段就绪通过 [onReady] 回调即时通知（供边合成边播放）；已缓存段也会立即回调。
     * 返回就绪段列表（按 segIndex 排序）。整章在章节锁内串行执行。
     */
    suspend fun ensureChapter(
        book: BookEntity,
        chapter: ChapterEntity,
        fromSeg: Int = 0,
        onReady: ((SegmentEntity) -> Unit)? = null,
    ): List<SegmentEntity> = withChapterLock(chapter.id) {
        val existing = container.segmentRepo.byChapter(chapter.id)
        RequestLogger.log("合成", "章节 ${chapter.id} 开始（已有段 ${existing.size}，文本${chapter.content.length}字符）")
        var segments = existing
        if (existing.isEmpty()) {
            // 只认当前版本缓存：旧版（version=1，未段合并）自动失效重解析
            val cached = container.db.parseCacheDao().byChapterVersioned(chapter.id, ParseCacheEntity.CURRENT_VERSION)
            val raw: List<Segment> = if (cached != null) {
                jsonToSegments(cached.segmentsJson)
            } else {
                try {
                    engine.parseSegments(chapter.id, chapter.content)
                } catch (e: Exception) {
                    // 解析失败降级：不整章报错，改为按句切成旁白段，保证仍可朗读（无角色区分）
                    android.util.Log.w("EnsembleReads", "DeepSeek 解析失败降级为旁白段: ${e.javaClass.simpleName}: ${e.message}")
                    fallbackSegments(chapter.content)
                }
            }
            // 长章节段合并：把 1000+ 短段压到 ≤400 段，降低合成量、校正时长估算
            val parsed = SegmentMerger.merge(raw)
            segments = parsed.mapIndexed { i, s ->
                SegmentEntity(
                    chapterId = chapter.id, segIndex = i, speaker = s.speaker, text = s.text,
                    gender = s.gender, age = s.age, tone = s.tone, status = SegmentEntity.STATUS_PARSED,
                )
            }
            container.segmentRepo.addAll(segments)
            container.db.parseCacheDao().upsert(ParseCacheEntity(chapter.id, segmentsToJson(parsed)))
        }
        val roles = container.roleRepo.byBook(book.id).associateBy { it.roleName }
        val voices = engine.allocateVoices(segments.map { it.toTtsSegment() }, roles)
        val ready = mutableListOf<SegmentEntity>()

        // 起始段越界处理：段合并(A2)会改变 segIndex 数量，Book.lastSegIndex 可能是合并前的旧值。
        // 越界时从头播（0），而不是钳到末段（否则只播一段就自停）；负数取 0。
        val safeFrom = if (segments.isEmpty() || fromSeg > segments.last().segIndex) 0 else fromSeg.coerceAtLeast(0)

        // 并行合成（Semaphore 限 PARALLEL_SYNTHESIS 路，对应 Edge TTS 服务端并发上限）：
        // 所有待合成段并发跑，但派发用 CompletableDeferred 按 segIndex 顺序门控——
        // 前面的段未完成就等，完成即回调，保证边合成边播放的顺序与现状一致。
        val semaphore = Semaphore(PARALLEL_SYNTHESIS)
        coroutineScope {
            val deferred = Array(segments.size) { CompletableDeferred<SegmentEntity?>() }
            segments.forEachIndexed { i, seg ->
                if (seg.segIndex < safeFrom) { deferred[i].complete(null); return@forEachIndexed }
                if (seg.status == SegmentEntity.STATUS_READY) { deferred[i].complete(seg); return@forEachIndexed }
                val v = voices[seg.speaker] ?: run { deferred[i].complete(null); return@forEachIndexed }
                val dest = segFile(book.id, chapter.id, seg.segIndex)
                chapterDir(book.id, chapter.id).mkdirs()
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        var updated: SegmentEntity? = null
                        try {
                            container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_SYNTHESIZING)
                            engine.synthesize(seg.text, v, dest)
                            val dur = readDurationMs(dest)
                            container.segmentRepo.markReady(seg.id, dest.absolutePath, System.currentTimeMillis(), dur)
                            updated = seg.copy(
                                status = SegmentEntity.STATUS_READY,
                                audioPath = dest.absolutePath,
                                cachedAt = System.currentTimeMillis(),
                                durationMs = dur,
                            )
                        } catch (e: Exception) {
                            container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_FAILED)
                        }
                        deferred[i].complete(updated)
                    }
                }
            }
            // 顺序派发：按 segIndex 逐个 await；失败段为 null 直接跳过
            for (i in segments.indices) {
                val r = deferred[i].await()
                if (r != null) {
                    ready.add(r)
                    safeOnReady(onReady, r) // 播放器异常移出合成 try/catch：不误标失败、不打断后续合成
                }
            }
        }
        container.chapterRepo.setCached(chapter.id, container.segmentRepo.readyByChapter(chapter.id).isNotEmpty())
        evictIfNeeded(book)
        RequestLogger.log("合成", "章节 ${chapter.id} 完成（就绪 ${ready.size} 段）")
        ready
    }

    /** 预取 [startChapterId] 之后的 count 章（受缓存上限约束）。后台执行。 */
    fun prefetch(book: BookEntity, startChapterId: Long, count: Int) {
        scope.launch {
            val chapters = container.chapterRepo.chapters(book.id)
            val start = chapters.indexOfFirst { it.id == startChapterId }
            if (start < 0) return@launch
            val limit = cacheLimit()
            val budget = (limit.coerceAtLeast(0) - cachedCount(chapters)).coerceAtLeast(0)
            val targets = chapters.drop(start + 1).take(minOf(count, budget))
            for (ch in targets) {
                try { ensureChapter(book, ch) } catch (e: Exception) { /* 预取失败静默，等待重试 */ }
            }
        }
    }

    /** 超限淘汰：删除最早缓存的章节文件并重置状态。 */
    suspend fun evictIfNeeded(book: BookEntity) {
        val chapters = container.chapterRepo.chapters(book.id)
        val counts = chapters.associate { it.id to container.segmentRepo.readyByChapter(it.id).size }
        val toEvict = CachePolicy.evict(cacheLimit(), chapters, counts)
        for (cid in toEvict) {
            chapterDir(book.id, cid).deleteRecursively()
            container.segmentRepo.byChapter(cid).forEach {
                container.segmentRepo.setStatus(it.id, SegmentEntity.STATUS_PENDING)
            }
            container.chapterRepo.setCached(cid, false)
        }
    }

    /** 清空全部音频缓存并重置状态（设置页"清空缓存"）。 */
    suspend fun clearAllCache() {
        audioRoot.deleteRecursively()
        container.bookRepo.all().forEach { book ->
            container.segmentRepo.deleteByBook(book.id)
            container.chapterRepo.chapters(book.id).forEach { container.chapterRepo.setCached(it.id, false) }
        }
    }

    /** 记录阅读进度到 Book 表（书架"继续阅读"）。 */
    suspend fun saveProgress(book: BookEntity) {
        container.bookRepo.update(book)
    }

    /** 设置里配置的默认倍速（0.5~2x），未配置或非法时为 1x。 */
    suspend fun defaultSpeed(): Float =
        container.settings.get(SettingsManager.KEY_DEFAULT_SPEED)?.toFloatOrNull()
            ?.takeIf { it in 0.5f..2f } ?: 1f

    /** 重置某章（删除音频、段状态回 PARSED），用于用户改角色后重新合成。与 ensureChapter 同锁。 */
    suspend fun resetChapter(book: BookEntity, chapter: ChapterEntity) = withChapterLock(chapter.id) {
        chapterDir(book.id, chapter.id).deleteRecursively()
        container.segmentRepo.byChapter(chapter.id).forEach {
            container.segmentRepo.setStatus(it.id, SegmentEntity.STATUS_PARSED)
        }
        container.chapterRepo.setCached(chapter.id, false)
    }

    /**
     * 强制重解析某章（删除段记录 + 解析缓存 + 音频目录，再按新逻辑重新解析合成）。
     * 用于段合并/角色识别算法升级后对既有书一次性重解析。调用方应先暂停播放。
     */
    suspend fun reparseChapter(book: BookEntity, chapter: ChapterEntity) {
        withChapterLock(chapter.id) {
            container.segmentRepo.deleteByChapter(chapter.id)
            container.db.parseCacheDao().deleteByChapter(chapter.id)
            chapterDir(book.id, chapter.id).deleteRecursively()
            container.chapterRepo.setCached(chapter.id, false)
        }
        ensureChapter(book, chapter, 0, null)
    }

    private suspend fun cacheLimit(): Int =
        (container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: SettingsManager.DEFAULT_CACHE_LIMIT)
            .toIntOrNull() ?: 100

    private suspend fun cachedCount(chapters: List<ChapterEntity>): Int =
        chapters.count { it.cached && container.segmentRepo.readyByChapter(it.id).isNotEmpty() }

    /** 解析失败降级：把原文按换行/句末标点切成旁白段，保证整章仍可朗读。 */
    private fun fallbackSegments(content: String): List<Segment> {
        val parts = content.split(Regex("\\n+|(?<=[。！？！？])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(Segment("旁白", content))
        return parts.map { Segment("旁白", it) }
    }

    private fun jsonToSegments(json: String): List<Segment> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Segment(o.optString("speaker"), o.optString("text"),
                o.optString("gender"), o.optString("age"), o.optString("tone"))
        }
    }.getOrDefault(emptyList())

    private fun segmentsToJson(segs: List<Segment>): String {
        val arr = JSONArray()
        segs.forEach {
            arr.put(JSONObject()
                .put("speaker", it.speaker).put("text", it.text)
                .put("gender", it.gender).put("age", it.age).put("tone", it.tone))
        }
        return arr.toString()
    }

    /** 读 mp3 实际时长(ms)；失败返回 0（不阻塞合成成功）。 */
    private fun readDurationMs(file: File): Long = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { r.release() }
        }
    } catch (e: Exception) {
        0L
    }

    /**
     * 章内各段时长(ms)，与 byChapter 顺序对齐：就绪段用实测值，
     * 未就绪段按本书就绪段的平均字速估算（无实测样本时退化为兜底字速）。
     * 供整章进度条的总时长与「章内时间 → 段」定位。
     */
    suspend fun segmentDurationsMs(chapterId: Long): List<Long> {
        val segs = container.segmentRepo.byChapter(chapterId)
        if (segs.isEmpty()) return emptyList()
        val ready = segs.filter { it.status == SegmentEntity.STATUS_READY && it.durationMs > 0 }
        val msPerChar = if (ready.isNotEmpty()) {
            ready.sumOf { it.durationMs }.toDouble() / ready.sumOf { it.text.length }.coerceAtLeast(1)
        } else DEFAULT_MS_PER_CHAR
        return segs.map { if (it.durationMs > 0) it.durationMs else (it.text.length * msPerChar).toLong() }
    }

    /** 整章总时长(ms)，含未就绪段估算。 */
    suspend fun chapterTotalMs(chapterId: Long): Long = segmentDurationsMs(chapterId).sum()

    /** 章内时间(ms) → (segIndex, 段内偏移ms)，供进度条跨段 seek。 */
    suspend fun segAtMs(chapterId: Long, totalMs: Long): Pair<Int, Long> {
        val segs = container.segmentRepo.byChapter(chapterId)
        val durs = segmentDurationsMs(chapterId)
        if (segs.isEmpty()) return 0 to 0L
        var acc = 0L
        for (i in durs.indices) {
            if (totalMs < acc + durs[i]) return segs[i].segIndex to (totalMs - acc).coerceAtLeast(0)
            acc += durs[i]
        }
        return segs.last().segIndex to durs.last()
    }
}

/** 章节内并发合成上限（Edge TTS 服务端限制，参考 VeloVoice 的 4 线程并发）。 */
private const val PARALLEL_SYNTHESIS = 4

/** 未就绪段时长兜底估算字速：无实测参考时按约 4.5 字/秒（220ms/字）。 */
private const val DEFAULT_MS_PER_CHAR = 220.0

/** 把持久化的分段转回 tts.Segment（保留特征）。 */
fun SegmentEntity.toTtsSegment() = Segment(speaker, text, gender, age, tone)
