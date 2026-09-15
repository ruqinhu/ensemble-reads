package com.ensemblereads.app.player

import com.ensemblereads.app.tts.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段合并：长章 1000+ 段压到合理粒度，相邻同 speaker 短段合并，超长单段保留。 */
class SegmentMergerTest {

    @Test
    fun `相邻同 speaker 短段合并`() {
        val segs = listOf(
            Segment("旁白", "夜色渐深。"),
            Segment("旁白", "他立在檐下。"),
            Segment("张羽", "你来了。"),
        )
        val merged = SegmentMerger.merge(segs)
        assertEquals(2, merged.size)
        assertEquals("夜色渐深。他立在檐下。", merged[0].text)
        assertEquals("张羽", merged[0].speaker)
    }

    @Test
    fun `不同 speaker 不合并`() {
        val segs = listOf(
            Segment("张羽", "你好"),
            Segment("黑衣人", "再见"),
        )
        val merged = SegmentMerger.merge(segs)
        assertEquals(2, merged.size)
    }

    @Test
    fun `合并后超过 MAX_SEG_CHARS 不再合并`() {
        val long1 = "长".repeat(100)
        val long2 = "长".repeat(100)
        val merged = SegmentMerger.merge(listOf(Segment("旁白", long1), Segment("旁白", long2)))
        assertEquals(2, merged.size)
    }

    @Test
    fun `合并后仍超上限则无条件合并到上限内`() {
        val segs = (0 until 500).map { Segment("旁白", "短") }
        val merged = SegmentMerger.merge(segs)
        assertTrue(merged.size <= SegmentMerger.MAX_SEGMENTS)
    }

    @Test
    fun `超长单段保留不切碎`() {
        val longText = "长".repeat(2000)
        val merged = SegmentMerger.merge(listOf(Segment("旁白", longText)))
        assertEquals(1, merged.size)
        assertEquals(longText.length, merged[0].text.length)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(SegmentMerger.merge(emptyList()).isEmpty())
    }
}
