package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoleAllocatorTest {
    private fun seg(sp: String, g: String = "unknown", a: String = "未知", t: String = "中性") =
        Segment(sp, "x", g, a, t)

    @Test fun narratorDefaultsToYunyang() {
        val m = RoleAllocator.allocate(listOf(seg("旁白")), emptyMap())
        assertEquals("zh-CN-YunyangNeural", m.getValue("旁白").id)
    }

    @Test fun narratorFollowsTone() {
        val m = RoleAllocator.allocate(
            listOf(seg("旁白", t = "温柔"), seg("旁白", t = "温柔")), emptyMap())
        assertEquals("zh-CN-XiaoxiaoNeural", m.getValue("旁白").id)
    }

    @Test fun motherMatchesMatureFemale() {
        val m = RoleAllocator.allocate(
            listOf(seg("旁白"), seg("母亲", "female", "中年", "温柔")), emptyMap())
        assertEquals("zh-CN-XiaoxuanNeural", m.getValue("母亲").id)
    }

    @Test fun userOverrideWins() {
        val m = RoleAllocator.allocate(
            listOf(seg("张羽", "male", "青年")),
            mapOf("张羽" to RoleEntity(roleName = "张羽", bookId = 1, voice = "zh-CN-YunxiNeural", pitch = 10, rate = 20)))
        assertEquals("zh-CN-YunxiNeural", m.getValue("张羽").id)
        assertEquals(10, m.getValue("张羽").pitch)
        assertEquals(20, m.getValue("张羽").rate)
    }

    @Test fun conflictDemotes() {
        val segs = listOf(seg("旁白"), seg("A", "male", "中年"), seg("B", "male", "中年"))
        val m = RoleAllocator.allocate(segs, emptyMap())
        assertEquals("zh-CN-YunjianNeural", m.getValue("A").id)
        assertNotEquals("zh-CN-YunjianNeural", m.getValue("B").id)
    }

    @Test fun toneParams() {
        assertEquals(15 to 8, RoleAllocator.toneParams("焦急"))
        assertEquals(0 to 0, RoleAllocator.toneParams("中性"))
        assertEquals(0 to 0, RoleAllocator.toneParams("不存在的"))
    }
}
