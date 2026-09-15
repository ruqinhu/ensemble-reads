package com.ensemblereads.app.tts

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeepSeekClientTest {
    @Test fun parsesFencedJson() {
        val raw = "```json\n[{\"speaker\":\"旁白\",\"text\":\"他走了\",\"gender\":\"unknown\",\"age\":\"未知\",\"tone\":\"中性\"}]\n```"
        val segs = DeepSeekParser.parseResponse(raw)
        assertEquals(1, segs.size)
        assertEquals("旁白", segs[0].speaker)
    }

    @Test fun normalizesBadFields() {
        val raw = "[{\"speaker\":\"张羽\",\"text\":\"x\",\"gender\":\"male\",\"age\":\"Adult\",\"tone\":\"angry\"}]"
        val segs = DeepSeekParser.parseResponse(raw)
        assertEquals("未知", segs[0].age)
        assertEquals("中性", segs[0].tone)
    }

    @Test fun invalidReturnsEmpty() {
        assertEquals(0, DeepSeekParser.parseResponse("出错了").size)
    }

    @Test fun keepsOriginalSpeakers() {
        val raw = "[{\"speaker\":\"母亲\",\"text\":\"到了吗\",\"gender\":\"female\",\"age\":\"中年\",\"tone\":\"温柔\"}]"
        assertEquals("母亲", DeepSeekParser.parseResponse(raw)[0].speaker)
    }

    @Test fun handlesSurroundingText() {
        val raw = "好的：\n[{\"speaker\":\"妈妈\",\"text\":\"到了吗\",\"gender\":\"female\",\"age\":\"中年\",\"tone\":\"温柔\"}]\n完毕"
        assertEquals("妈妈", DeepSeekParser.parseResponse(raw)[0].speaker)
    }

    @Test fun retriesThenSucceeds() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"type":"text","text":"[{\"speaker\":\"旁白\",\"text\":\"ok\",\"gender\":\"unknown\",\"age\":\"未知\",\"tone\":\"中性\"}]"}]}""",
            ),
        )
        server.start()
        try {
            val client = DeepSeekClient("k", server.url("/").toString())
            val segs = client.parse(1, "text")
            assertEquals(1, segs.size)
            assertEquals("旁白", segs[0].speaker)
            assertEquals(2, server.requestCount) // 500 后重试成功
        } finally {
            server.shutdown()
        }
    }

    @Test fun emptyResultThrows() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"出错了"}]}"""))
        server.start()
        try {
            val client = DeepSeekClient("k", server.url("/").toString())
            try {
                client.parse(1, "text")
                fail("应当抛 RuntimeException")
            } catch (e: RuntimeException) {
                // 预期：空解析结果视为失败
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun buildChunkUserContentAppendsContinuation() {
        val base = buildChunkUserContent("正文", null)
        assertTrue(base.contains("正文"))
        assertTrue(!base.contains("前文提示"))
        val withPrev = buildChunkUserContent("正文", "张羽")
        assertTrue(withPrev.contains("正文"))
        assertTrue(withPrev.contains("张羽"))
        assertTrue(withPrev.contains("前文提示"))
    }

    @Test fun buildChunkUserContentIgnoresBlankSpeaker() {
        val s = buildChunkUserContent("正文", "  ")
        assertTrue(!s.contains("前文提示"))
    }
}
