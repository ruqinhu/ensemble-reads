package com.ensemblereads.app.book

import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.io.InputStream

/** EPUB → (书名, 纯文本章节列表)。用 epublib 读结构 + Jsoup 去 HTML 标签。 */
object EpubParser {
    fun read(stream: InputStream): Pair<String, List<ParsedChapter>> {
        val book = EpubReader().readEpub(stream)
        val title = book.title ?: "未命名书籍"
        val chapters = mutableListOf<ParsedChapter>()
        book.contents.spine.spineReferences.forEachIndexed { i, ref ->
            val text = try {
                ref.resource.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (e: Exception) { return@forEachIndexed }
            val doc = Jsoup.parse(text)
            val heading = doc.select("h1,h2,h3").firstOrNull()?.text() ?: "第${i + 1}节"
            val body = doc.body().text().trim()
            if (body.isNotBlank()) chapters.add(ParsedChapter(i, heading, body))
        }
        return title to chapters
    }
}
