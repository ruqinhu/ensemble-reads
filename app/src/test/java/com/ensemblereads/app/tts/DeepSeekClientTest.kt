package com.ensemblereads.app.tts

import org.junit.Assert.assertEquals
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
}
