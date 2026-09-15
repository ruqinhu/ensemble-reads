package com.ensemblereads.app.book

/** 极轻量 Markdown 解析：按 `#` 标题切章、剥离行内语法、首标题作书名候选。不加第三方依赖。 */
object MdParser {
    private val TITLE_RE = Regex("^#{1,6}\\s+(.*)$")

    /** 返回 (书名候选, 章节列表)。无标题时整篇为一章「前言」。 */
    fun parse(text: String): Pair<String, List<ParsedChapter>> {
        val lines = text.lines()
        val chapters = mutableListOf<ParsedChapter>()
        var curTitle = "前言"
        val cur = StringBuilder()
        var index = 0
        for (line in lines) {
            val m = TITLE_RE.find(line.trim())
            if (m != null) {
                if (cur.isNotBlank()) chapters.add(ParsedChapter(index++, curTitle, cur.toString().trim()))
                curTitle = stripLine(m.groupValues[1])
                cur.setLength(0)
            } else {
                val cleaned = stripLine(line)
                if (cleaned.isNotBlank()) cur.appendLine(cleaned)
            }
        }
        if (cur.isNotBlank()) chapters.add(ParsedChapter(index, curTitle, cur.toString().trim()))
        val bookTitle = chapters.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: "Markdown 文档"
        return bookTitle to chapters
    }

    /** 剥离 Markdown 语法：块级引用/列表前缀、行内粗体/斜体/代码/链接/删除线。 */
    fun stripLine(line: String): String {
        var s = line.trim()
        s = s.replace(Regex("^>+\\s?"), "")                 // 引用
        s = s.replace(Regex("^[-*+]\\s+"), "")               // 无序列表
        s = s.replace(Regex("^\\d+\\.\\s+"), "")             // 有序列表
        s = s.replace(Regex("^```"), "")                     // 代码块围栏
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // [text](url) → text
        s = s.replace(Regex("`([^`]*)`"), "$1")              // `code` → code
        s = s.replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")    // **bold** → bold
        s = s.replace(Regex("__([^_]*)__"), "$1")            // __bold__ → bold
        s = s.replace(Regex("\\*([^*]*)\\*"), "$1")          // *em* → em
        s = s.replace(Regex("~~([^~]*)~~"), "$1")            // ~~del~~ → del
        return s.trim()
    }
}
