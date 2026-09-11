package com.ensemblereads.app.player

import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.ParseCacheEntity
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.tts.Segment
import com.ensemblereads.app.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        var segments = existing
        if (existing.isEmpty()) {
            val cached = container.db.parseCacheDao().byChapter(chapter.id)
            val parsed: List<Segment> = if (cached != null) {
                jsonToSegments(cached.segmentsJson)
            } else {
                engine.parseSegments(chapter.id, chapter.content)
            }
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
        for (seg in segments.filter { it.segIndex >= fromSeg }) {
            if (seg.status == SegmentEntity.STATUS_READY) {
                ready.add(seg); onReady?.invoke(seg); continue
            }
            val v = voices[seg.speaker] ?: continue
            val dest = segFile(book.id, chapter.id, seg.segIndex)
            chapterDir(book.id, chapter.id).mkdirs()
            try {
                container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_SYNTHESIZING)
                engine.synthesize(seg.text, v, dest)
                container.segmentRepo.markReady(seg.id, dest.absolutePath, System.currentTimeMillis())
                val updated = seg.copy(
                    status = SegmentEntity.STATUS_READY,
                    audioPath = dest.absolutePath,
                    cachedAt = System.currentTimeMillis(),
                )
                ready.add(updated)
                onReady?.invoke(updated)
            } catch (e: Exception) {
                container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_FAILED)
            }
        }
        container.chapterRepo.setCached(chapter.id, container.segmentRepo.readyByChapter(chapter.id).isNotEmpty())
        evictIfNeeded(book)
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

    /** 批量缓存指定章节。后台执行。 */
    fun batchCache(book: BookEntity, chapterIds: List<Long>) {
        scope.launch {
            val chapters = container.chapterRepo.chapters(book.id).filter { it.id in chapterIds }
            for (ch in chapters) {
                try { ensureChapter(book, ch) } catch (e: Exception) { /* 单章失败不影响后续 */ }
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

    /** 重置某章（删除音频、段状态回 PARSED），用于用户改角色后重新合成。 */
    suspend fun resetChapter(book: BookEntity, chapter: ChapterEntity) {
        chapterDir(book.id, chapter.id).deleteRecursively()
        container.segmentRepo.byChapter(chapter.id).forEach {
            container.segmentRepo.setStatus(it.id, SegmentEntity.STATUS_PARSED)
        }
        container.chapterRepo.setCached(chapter.id, false)
    }

    private suspend fun cacheLimit(): Int =
        (container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: SettingsManager.DEFAULT_CACHE_LIMIT)
            .toIntOrNull() ?: 100

    private suspend fun cachedCount(chapters: List<ChapterEntity>): Int =
        chapters.count { it.cached && container.segmentRepo.readyByChapter(it.id).isNotEmpty() }

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
}

/** 把持久化的分段转回 tts.Segment（保留特征）。 */
fun SegmentEntity.toTtsSegment() = Segment(speaker, text, gender, age, tone)
