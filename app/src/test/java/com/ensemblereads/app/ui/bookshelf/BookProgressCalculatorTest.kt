package com.ensemblereads.app.ui.bookshelf

import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.SegCountByChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 整书进度计算：当前章前段数和 + 当前章段位置 ÷ 全书段数。 */
class BookProgressCalculatorTest {

    private val rows = listOf(
        SegCountByChapter(1, 10, 0, "第一章", 5),
        SegCountByChapter(1, 11, 1, "第二章", 4),
        SegCountByChapter(1, 12, 2, "第三章", 6),
    )

    @Test
    fun `未读无进度`() {
        assertNull(BookProgressCalculator.calc(BookEntity(id = 1, title = "t", filePath = "f", format = "TXT", lastChapterId = null), rows))
    }

    @Test
    fun `无解析段返回 null`() {
        assertNull(BookProgressCalculator.calc(
            BookEntity(id = 1, title = "t", filePath = "f", format = "TXT", lastChapterId = 10, lastSegIndex = 2),
            emptyList(),
        ))
    }

    @Test
    fun `首章段索引`() {
        // 第 1 章 lastSegIndex=0 → 完成 1 段 / 全书 15 段 ≈ 6%
        val p = BookProgressCalculator.calc(
            BookEntity(id = 1, title = "t", filePath = "f", format = "TXT", lastChapterId = 10, lastSegIndex = 0), rows)
        assertEquals(6, p!!.percent)
        assertEquals("第一章", p.chapterLabel)
    }

    @Test
    fun `第二章中间`() {
        // 第 1 章全听完(5) + 第 2 章 lastSegIndex=1(完成 2 段) = 7 / 15 ≈ 46%
        val p = BookProgressCalculator.calc(
            BookEntity(id = 1, title = "t", filePath = "f", format = "TXT", lastChapterId = 11, lastSegIndex = 1), rows)
        assertEquals(46, p!!.percent)
        assertEquals("第二章", p.chapterLabel)
    }

    @Test
    fun `末章听完 100`() {
        val p = BookProgressCalculator.calc(
            BookEntity(id = 1, title = "t", filePath = "f", format = "TXT", lastChapterId = 12, lastSegIndex = 5), rows)
        assertEquals(100, p!!.percent)
    }
}
