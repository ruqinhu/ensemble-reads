package com.ensemblereads.app.ui.bookshelf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ensemblereads.app.book.EpubParser
import com.ensemblereads.app.book.MdParser
import com.ensemblereads.app.book.PdfParser
import com.ensemblereads.app.book.TxtParser
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.repo.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** 通过 SAF uri 导入 TXT/EPUB，解析入库，返回新 Book 的 id。 */
suspend fun importBook(context: Context, uri: Uri, container: AppContainer): Long =
    withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "未命名"

        // 同一 uri 已导入过：去重返回既有书籍 id
        container.bookRepo.all().firstOrNull { it.filePath == uri.toString() }?.let {
            return@withContext it.id
        }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开文件")
        val lower = displayName.lowercase()

        // 按扩展名分派解析器：EPUB / PDF / Markdown / TXT
        val (title, chapters, format) = when {
            lower.endsWith(".epub") -> {
                val (t, chs) = EpubParser.read(bytes)
                Triple(t, chs, "EPUB")
            }
            lower.endsWith(".pdf") -> {
                val (t, chs) = PdfParser.read(context, bytes)
                Triple(t, chs, "PDF")
            }
            lower.endsWith(".md") || lower.endsWith(".markdown") -> {
                val enc = TxtParser.detectEncoding(bytes)
                val text = String(bytes, Charset.forName(enc))
                val (t, chs) = MdParser.parse(text)
                Triple(t, chs, "MD")
            }
            else -> {
                val enc = TxtParser.detectEncoding(bytes)
                val text = String(bytes, Charset.forName(enc)).removePrefix("﻿")
                Triple(displayName.removeSuffix(".txt"), TxtParser.split(text), "TXT")
            }
        }

        val book = BookEntity(
            title = title.ifEmpty { displayName },
            filePath = uri.toString(),
            format = format,
            chapterCount = chapters.size,
            createdAt = System.currentTimeMillis(),
        )
        val bookId = container.bookRepo.add(book)
        container.chapterRepo.addAll(chapters.mapIndexed { i, c ->
            ChapterEntity(bookId = bookId, index = i, title = c.title, content = c.content)
        })
        bookId
    }
