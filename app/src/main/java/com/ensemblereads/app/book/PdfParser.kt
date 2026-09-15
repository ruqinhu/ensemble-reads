package com.ensemblereads.app.book

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

/** PDF 文本层抽取：整本文本交给 [TxtParser.split] 切章。扫描版 PDF（无文本层）得到空文本。 */
object PdfParser {
    /** 解析 PDF 文本层。首次调用前会初始化 PDFBox 资源加载器（幂等）。 */
    fun read(context: Context, bytes: ByteArray): Pair<String, List<ParsedChapter>> {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val text = PDFTextStripper().getText(doc)?.trim() ?: ""
            val chapters = TxtParser.split(text)
            val title = chapters.firstOrNull()?.title?.takeIf { it.isNotBlank() && it != "前言" }
                ?: "PDF 文档"
            return title to chapters
        }
    }
}
