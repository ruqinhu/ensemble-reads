package com.ensemblereads.app.ui.bookshelf

import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.SegCountByChapter

/**
 * 整书朗读进度计算（纯函数，可单测）。
 * 口径：已完成段数 = 当前章之前各章段数之和 + 当前章 lastSegIndex+1；全书段数 = 已解析段数总和。
 * 百分比是「整书」进度而非章内比例（修复书架「已听 85%」误导）。
 */
object BookProgressCalculator {

    /** 返回 null 表示无进度（未读过 / 无解析段），书架不显示。 */
    fun calc(book: BookEntity, rows: List<SegCountByChapter>): BookProgress? {
        val lc = book.lastChapterId ?: return null
        val chapters = rows.filter { it.bookId == book.id }.sortedBy { it.chapterIndex }
        val lastIdx = chapters.indexOfFirst { it.chapterId == lc }
        if (lastIdx < 0) return null
        val total = chapters.sumOf { it.cnt }
        if (total <= 0) return null
        val doneBefore = chapters.take(lastIdx).sumOf { it.cnt }
        val done = doneBefore + (book.lastSegIndex + 1)
        val pct = (done * 100 / total).coerceIn(0, 100)
        return BookProgress(pct, chapters[lastIdx].chapterTitle)
    }
}
