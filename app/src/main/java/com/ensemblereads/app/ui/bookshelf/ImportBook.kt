package com.ensemblereads.app.ui.bookshelf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ensemblereads.app.book.EpubParser
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

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开文件")
        val isEpub = displayName.endsWith(".epub", true)

        val (title, chapters) = if (isEpub) {
            val (t, chs) = EpubParser.read(bytes)
            t to chs
        } else {
            val enc = TxtParser.detectEncoding(bytes)
            val text = String(bytes, Charset.forName(enc)).removePrefix("﻿")
            displayName.removeSuffix(".txt") to TxtParser.split(text)
        }

        val book = BookEntity(
            title = title.ifEmpty { displayName },
            filePath = uri.toString(),
            format = if (isEpub) "EPUB" else "TXT",
            chapterCount = chapters.size,
            createdAt = System.currentTimeMillis(),
        )
        val bookId = container.bookRepo.add(book)
        container.chapterRepo.addAll(chapters.mapIndexed { i, c ->
            ChapterEntity(bookId = bookId, index = i, title = c.title, content = c.content)
        })
        bookId
    }
