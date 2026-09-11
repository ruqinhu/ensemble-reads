package com.ensemblereads.app.book

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 解析出的章节：index 从 0 开始，title 为章节标题，content 为纯文本。 */
data class ParsedChapter(val index: Int, val title: String, val content: String)

object TxtParser {
    /** 匹配行首“第N章/节/卷/回/话”标题。 */
    private val CHAPTER_RE = Regex("^第[0-9一二三四五六七八九十百千两]+[章节卷回话].*$", RegexOption.MULTILINE)

    /**
     * 编码探测：先按 BOM 特判；否则用严格 UTF-8 解码器（遇到非法字节序列即报错）判定，
     * 不再是"永不抛异常"的死分支。判定失败回退 GB18030。
     */
    fun detectEncoding(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        val utf8 = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) { false }
        return if (utf8) "UTF-8" else "GB18030"
    }

    fun split(text: String): List<ParsedChapter> {
        // 去 UTF-8 BOM，避免首个章节标题匹配失败
        val lines = text.removePrefix("﻿").lines()
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
