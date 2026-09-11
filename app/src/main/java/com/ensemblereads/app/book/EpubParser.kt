package com.ensemblereads.app.book

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 EPUB 解析器：EPUB 本质是 ZIP 容器 + OPF 清单 + XHTML 正文。
 * 用 JDK 自带 zip + 项目内已有的 Jsoup 实现，不依赖任何第三方 EPUB 库。
 *
 * 内存友好：只解压 container.xml / OPF / spine 实际引用的 XHTML，
 * 图片、字体、内嵌 PDF 等非正文资源一律跳过，避免大书导入 OOM。
 */
object EpubParser {

    /** 从 EPUB zip 流中读取：(书名, 纯文本章节列表)。按 OPF spine 顺序提取。 */
    fun read(stream: InputStream): Pair<String, List<ParsedChapter>> = read(stream.readBytes())

    /** 字节版入口：避免流上二次整读（ImportBook 已持有字节时用这个）。 */
    fun read(bytes: ByteArray): Pair<String, List<ParsedChapter>> {
        // 1. container.xml → OPF 路径（Jsoup XML 解析，兼容属性顺序/换行）
        val containerBytes = extractEntries(bytes) { it.equals("META-INF/container.xml", ignoreCase = true) }
            .values.firstOrNull() ?: error("非标准 EPUB：缺少 META-INF/container.xml")
        val container = Jsoup.parse(containerBytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
        val opfPath = container.selectFirst("rootfile")?.attr("full-path")?.trim()?.removePrefix("/")
            ?: error("EPUB 中未找到 rootfile/full-path")

        // 2. OPF → 书名 / manifest(id→href) / spine 顺序
        val opfBytes = extractEntries(bytes) { it == opfPath }.values.firstOrNull()
            ?: error("缺少 OPF 清单: $opfPath")
        val opf = Jsoup.parse(opfBytes.toString(Charsets.UTF_8), "", Parser.xmlParser())

        val title = opf.selectFirst("metadata")?.children()
            ?.firstOrNull { it.tagName() == "dc:title" }?.text()
            ?: opf.selectFirst("title")?.text()
            ?: "未命名书籍"

        val manifest = mutableMapOf<String, String>() // id → href
        opf.select("manifest item").forEach { el ->
            val id = el.attr("id"); val href = el.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

        val wantedNames = opf.select("spine itemref")
            .mapNotNull { manifest[it.attr("idref")]?.let { href -> resolveEntryPath(opfPath, href) } }
            .distinct()

        // 3. 只解压 spine 引用的条目
        val contentEntries = extractEntries(bytes) { it in wantedNames }

        // 4. 逐章提取纯文本；单章畸形不中断整本
        val chapters = mutableListOf<ParsedChapter>()
        var chapterIndex = 0
        for (name in wantedNames) {
            val raw = contentEntries[name] ?: continue
            val doc = runCatching { Jsoup.parse(raw.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            val text = doc.body().text().trim()
            if (text.isBlank()) continue // 图片等无文本资源
            val heading = doc.select("h1,h2,h3").firstOrNull()?.text() ?: "第${chapterIndex + 1}节"
            chapters.add(ParsedChapter(chapterIndex++, heading, text))
        }
        return title to chapters
    }

    /** 遍历 zip，仅解压满足 filter 的条目（防止把图片/字体等大资源全解进内存）。 */
    private fun extractEntries(bytes: ByteArray, filter: (String) -> Boolean): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && filter(e.name)) out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    /** 把 OPF 中相对/绝对 href 解析为 zip 内条目路径：去 fragment、URL 解码、规范 ./ ../。 */
    private fun resolveEntryPath(opfPath: String, href: String): String {
        val base = opfPath.substringBeforeLast('/', "")
        val raw = href.substringBefore('#')
        val target = when {
            raw.startsWith("/") -> raw
            base.isEmpty() -> raw
            else -> "$base/$raw"
        }
        val stack = mutableListOf<String>()
        for (seg in percentDecode(target).removePrefix("/").split('/')) {
            when (seg) {
                "", "." -> { /* 忽略 */ }
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }

    /** 最小 URL 百分号解码：兼容多字节 UTF-8 序列，纯 JDK 实现以便 JVM 单测。 */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            out.write(c.code)
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
