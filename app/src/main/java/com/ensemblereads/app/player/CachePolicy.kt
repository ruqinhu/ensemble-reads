package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.ChapterEntity

/** 缓存策略：当已缓存章数超过上限时，按章节 index 由小到大（最早章节）淘汰。 */
object CachePolicy {
    fun evict(limit: Int, chapters: List<ChapterEntity>, segmentCountByChapter: Map<Long, Int>): List<Long> {
        val cached = chapters
            .filter { it.cached && (segmentCountByChapter[it.id] ?: 0) > 0 }
            .sortedBy { it.index }
        val over = cached.size - limit
        if (over <= 0) return emptyList()
        return cached.take(over).map { it.id }
    }
}
