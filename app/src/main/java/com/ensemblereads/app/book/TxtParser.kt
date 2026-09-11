package com.ensemblereads.app.book

/** 解析出的章节：index 从 0 开始，title 为章节标题，content 为纯文本。 */
data class ParsedChapter(val index: Int, val title: String, val content: String)

object TxtParser {
    /** 匹配行首“第N章/节/卷/回/话”标题。 */
    private val CHAPTER_RE = Regex("^第[0-9一二三四五六七八九十百千两]+[章节卷回话].*$", RegexOption.MULTILINE)

    fun detectEncoding(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        val utf8 = try {
            @Suppress("UNUSED_VARIABLE") val s = String(bytes, Charsets.UTF_8)
            true
        } catch (e: Exception) { false }
        return if (utf8) "UTF-8" else "GB18030"
    }

    fun split(text: String): List<ParsedChapter> {
        val lines = text.lines()
        val chapters = mutableListOf<ParsedChapter>()
        var curTitle = "前言"
        val cur = StringBuilder()
        var index = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && CHAPTER_RE.matches(trimmed)) {
                if (cur.isNotBlank()) {
                    chapters.add(ParsedChapter(index++, curTitle, cur.toString().trim()))
                }
                curTitle = trimmed
                cur.setLength(0)
            } else {
                cur.appendLine(line)
            }
        }
        if (cur.isNotBlank()) chapters.add(ParsedChapter(index, curTitle, cur.toString().trim()))
        return chapters
    }
}
