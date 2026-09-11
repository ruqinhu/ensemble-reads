package com.ensemblereads.app.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            files.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun standardEpub(): ByteArray = zip(
        "META-INF/container.xml" to """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试书</dc:title></metadata>
              <manifest>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="images/pic.jpg" media-type="image/jpeg"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>""",
        "OEBPS/Text/ch1.xhtml" to "<html><body><h1>第一章</h1><p>你好，世界。</p><p>第二段。</p></body></html>",
        "OEBPS/images/pic.jpg" to "FAKEJPGDATA", // 不在 spine，不应被解压
    )

    @Test fun parsesStandardEpub() {
        val (title, chapters) = EpubParser.read(standardEpub())
        assertEquals("测试书", title)
        assertEquals(1, chapters.size)
        assertEquals("第一章", chapters[0].title)
        assertTrue(chapters[0].content.contains("你好，世界"))
        assertTrue(chapters[0].content.contains("第二段"))
    }

    @Test fun decodesRelativeAndEncodedHref() {
        // href 带 ../ 相对路径与 %20 空格编码；多章时按 spine 顺序
        val bytes = zip(
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<package xmlns="http://www.idpf.org/2007/opf">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
                <manifest>
                  <item id="good" href="../Text/My%20Book/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="bad" href="bad.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="good"/><itemref idref="bad"/></spine></package>""",
            "Text/My Book/ch1.xhtml" to "<html><body><h1>好章节</h1><p>正常内容。</p></body></html>",
            "bad.xhtml" to "<html><body><h1>坏章节</h1><p>也能解析。</p></body></html>",
        )
        val (_, chapters) = EpubParser.read(bytes)
        assertEquals(2, chapters.size)
        assertEquals("好章节", chapters[0].title)
        assertEquals("坏章节", chapters[1].title)
    }

    @Test fun missingContainerThrows() {
        assertThrows(IllegalStateException::class.java) {
            EpubParser.read(zip("foo.txt" to "x"))
        }
    }

    @Test fun skipsTextlessResource() {
        // spine 只引用一张图片 → 0 章，不崩溃
        val bytes = zip(
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<package xmlns="http://www.idpf.org/2007/opf">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
                <manifest><item id="img" href="pic.jpg" media-type="image/jpeg"/></manifest>
                <spine><itemref idref="img"/></spine></package>""",
            "pic.jpg" to "FAKE",
        )
        val (_, chapters) = EpubParser.read(bytes)
        assertEquals(0, chapters.size)
    }
}
