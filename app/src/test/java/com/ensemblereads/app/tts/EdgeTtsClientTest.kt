package com.ensemblereads.app.tts

import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsClientTest {
    @Test fun buildsSsml() {
        val ssml = EdgeTtsClient.buildSsml("你好", "zh-CN-YunxiNeural", 0, 10)
        assertTrue(ssml.contains("<voice name='zh-CN-YunxiNeural'>"))
        assertTrue(ssml.contains("rate='+10%'"))
        assertTrue(ssml.contains("你好"))
    }

    @Test fun ssmlEscapesXml() {
        val ssml = EdgeTtsClient.buildSsml("a<b&c", "zh-CN-YunxiNeural", 0, 0)
        assertTrue(ssml.contains("a&lt;b&amp;c"))
    }

    @Test fun connectionIdIsUuid() {
        assertTrue(EdgeTtsClient.newConnectionId().matches(Regex("[0-9a-f-]{36}")))
    }

    @Test fun pitchIncludedWhenSet() {
        val ssml = EdgeTtsClient.buildSsml("x", "zh-CN-YunxiNeural", -5, 0)
        assertTrue(ssml.contains("pitch='-5%'"))
    }
}
