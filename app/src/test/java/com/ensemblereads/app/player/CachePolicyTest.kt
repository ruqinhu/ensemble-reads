package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    @Test fun evictsEarliestWhenOverLimit() {
        val chs = listOf(
            ChapterEntity(id = 1, bookId = 1, index = 0, title = "c1", content = "", cached = true),
            ChapterEntity(id = 2, bookId = 1, index = 1, title = "c2", content = "", cached = true),
            ChapterEntity(id = 3, bookId = 1, index = 2, title = "c3", content = "", cached = true),
        )
        // limit=2 → 淘汰 index 最小(最早)的一章
        val toEvict = CachePolicy.evict(limit = 2, chapters = chs, segmentCountByChapter = mapOf(1L to 5, 2L to 5, 3L to 5))
        assertEquals(listOf(1L), toEvict)
    }

    @Test fun noEvictWhenWithinLimit() {
        val chs = listOf(
            ChapterEntity(id = 1, bookId = 1, index = 0, title = "c1", content = "", cached = true),
            ChapterEntity(id = 2, bookId = 1, index = 1, title = "c2", content = "", cached = true),
        )
        assertTrue(CachePolicy.evict(limit = 100, chapters = chs, segmentCountByChapter = mapOf(1L to 5, 2L to 5)).isEmpty())
    }

    @Test fun ignoresUncachedChapters() {
        val chs = listOf(
            ChapterEntity(id = 1, bookId = 1, index = 0, title = "c1", content = "", cached = false),
            ChapterEntity(id = 2, bookId = 1, index = 1, title = "c2", content = "", cached = true),
        )
        assertTrue(CachePolicy.evict(limit = 1, chapters = chs, segmentCountByChapter = mapOf(2L to 3)).isEmpty())
    }
}
