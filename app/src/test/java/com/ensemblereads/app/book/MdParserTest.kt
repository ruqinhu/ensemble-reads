package com.ensemblereads.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 轻量解析：标题切章、行内语法剥离、无标题兜底。 */
class MdParserTest {

    @Test
    fun `标题切章`() {
        val md = "# 第一章 初见\n\n内容一。\n\n## 第二章 追寻\n\n内容二。"
        val (title, chapters) = MdParser.parse(md)
        assertEquals("第一章 初见", title)
        assertEquals(2, chapters.size)
        assertEquals("第二章 追寻", chapters[1].title)
        assertTrue(chapters[0].content.contains("内容一"))
    }

    @Test
    fun `剥行内语法`() {
        val md = "# 标题\n\n这是 **加粗** 和 *斜体* 以及 `代码` 和 [链接](http://x.com)。"
        val (_, chapters) = MdParser.parse(md)
        val content = chapters[0].content
        assertTrue(!content.contains("**"))
        assertTrue(!content.contains("[链接](http"))
        assertTrue(content.contains("加粗") && content.contains("斜体") && content.contains("链接"))
    }

    @Test
    fun `无标题整篇一章`() {
        val md = "只有一段话。\n\n没有标题。"
        val (_, chapters) = MdParser.parse(md)
        assertEquals(1, chapters.size)
        assertEquals("前言", chapters[0].title)
    }

    @Test
    fun `空输入`() {
        val (_, chapters) = MdParser.parse("")
        assertTrue(chapters.isEmpty())
    }
}
