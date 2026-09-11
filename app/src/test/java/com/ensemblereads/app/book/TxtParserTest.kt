package com.ensemblereads.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TxtParserTest {
    @Test fun detectsGbkAndUtf8() {
        val gbk = "第1章 测试\n正文".toByteArray(Charset.forName("GB18030"))
        assertTrue(TxtParser.detectEncoding(gbk).contains("GB", ignoreCase = true))
        val utf8 = "第1章 测试\n正文".toByteArray(Charsets.UTF_8)
        assertTrue(TxtParser.detectEncoding(utf8).contains("UTF-8", ignoreCase = true))
    }

    @Test fun splitsChapters() {
        val text = "第1章 面试\n内容A\n第2章 开学\n内容B"
        val chs = TxtParser.split(text)
        assertEquals(2, chs.size)
        assertEquals("第1章 面试", chs[0].title)
        assertTrue(chs[0].content.contains("内容A"))
        assertEquals("第2章 开学", chs[1].title)
    }

    @Test fun emptyReturnsEmpty() {
        assertEquals(0, TxtParser.split("").size)
    }

    @Test fun keepsPrologueWhenNoHeader() {
        val chs = TxtParser.split("这是一段没有章节标题的纯文本")
        assertEquals(1, chs.size)
        assertEquals("前言", chs[0].title)
    }
}
