package com.ensemblereads.app.player

import com.ensemblereads.app.tts.Segment

/**
 * 长章节段合并（纯函数，可单测）。
 *
 * 背景：DeepSeek 按 1200 字符分块、逐句切段，46KB 长章会被切成 1000+ 段，
 * 导致合成 1000+ 个 mp3、章节时长估算严重失真、DB 段表膨胀。
 *
 * 规则：
 * 1) 相邻同 speaker 且合并后长度 ≤ [MAX_SEG_CHARS] 的短段合并为一段（特征取桶内首段）；
 * 2) 仍 > [MAX_SEGMENTS] 时第二轮：相邻同 speaker 无条件合并，直到 ≤ 上限；
 * 3) 仍超（>400 个独立说话轮次）则对超长段按 [MAX_TEXT_CAP] 截断兜底。
 * 单段本身超过上限的长独白保留原样，避免切碎语义。
 */
object SegmentMerger {
    const val MAX_SEG_CHARS = 180
    const val MAX_SEGMENTS = 400
    const val MAX_TEXT_CAP = 500

    fun merge(segments: List<Segment>): List<Segment> {
        if (segments.size < 2) return segments
        var merged = mergeAdjacent(segments, MAX_SEG_CHARS)
        if (merged.size > MAX_SEGMENTS) {
            merged = mergeAdjacentUnbounded(merged, MAX_SEGMENTS)
        }
        if (merged.size > MAX_SEGMENTS) {
            merged = merged.map {
                if (it.text.length > MAX_TEXT_CAP) it.copy(text = it.text.take(MAX_TEXT_CAP)) else it
            }
        }
        return merged
    }

    /** 相邻同 speaker、合并后 ≤ [maxChars] 的段合并；特征取桶内首段。 */
    internal fun mergeAdjacent(segments: List<Segment>, maxChars: Int): List<Segment> {
        val out = mutableListOf<Segment>()
        var cur: Segment? = null
        for (s in segments) {
            val c = cur
            if (c == null) { cur = s; continue }
            if (c.speaker == s.speaker && c.text.length + s.text.length <= maxChars) {
                cur = c.copy(text = c.text + s.text)
            } else {
                out.add(c)
                cur = s
            }
        }
        cur?.let { out.add(it) }
        return out
    }

    /** 相邻同 speaker 无条件合并（每轮把每个同 speaker 连续段吞并成一段），直到总段数 ≤ [maxSegments]。 */
    internal fun mergeAdjacentUnbounded(segments: List<Segment>, maxSegments: Int): List<Segment> {
        var merged = segments
        while (merged.size > maxSegments) {
            val next = mergeOnePass(merged)
            if (next.size == merged.size) break // 无可再合并
            merged = next
        }
        return merged
    }

    private fun mergeOnePass(segments: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < segments.size) {
            var j = i + 1
            var text = segments[i].text
            while (j < segments.size && segments[j].speaker == segments[i].speaker) {
                text += segments[j].text
                j++
            }
            out.add(segments[i].copy(text = text))
            i = j
        }
        return out
    }
}
