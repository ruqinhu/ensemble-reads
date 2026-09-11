package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity

/**
 * 通用角色配音分配（移植 PoC）。任何小说的角色都按 gender×age 定基础音色；
 * 旁白音色由叙事语气（旁白段 tone 众数）自动选择；用户 overrides 优先。
 */
object RoleAllocator {
    const val NARRATOR = "旁白"
    private const val DEFAULT_NARRATOR_VOICE = "zh-CN-YunyangNeural"
    private const val FALLBACK_VOICE = "zh-CN-YunxiaNeural"

    private val NARRATOR_TONE_VOICE = mapOf(
        "沉稳" to "zh-CN-YunyangNeural", "威严" to "zh-CN-YunyangNeural", "憨厚" to "zh-CN-YunyangNeural",
        "平淡" to "zh-CN-YunyangNeural", "中性" to "zh-CN-YunyangNeural",
        "温柔" to "zh-CN-XiaoxiaoNeural", "活泼" to "zh-CN-XiaoyiNeural", "俏皮" to "zh-CN-XiaoyiNeural",
        "冷酷" to "zh-CN-XiaoxuanNeural", "凶狠" to "zh-CN-YunjianNeural", "焦急" to "zh-CN-YunxiNeural",
    )
    private val MALE_BY_AGE = mapOf(
        "少年" to "zh-CN-YunxiaNeural", "青年" to "zh-CN-YunxiNeural",
        "中年" to "zh-CN-YunjianNeural", "老年" to "zh-CN-YunjianNeural",
    )
    private val FEMALE_BY_AGE = mapOf(
        "少年" to "zh-CN-XiaoyiNeural", "青年" to "zh-CN-XiaoxiaoNeural",
        "中年" to "zh-CN-XiaoxuanNeural", "老年" to "zh-CN-XiaoxuanNeural",
    )
    private val MALE_VOICES = listOf("zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-YunyangNeural", "zh-CN-YunxiaNeural")
    private val FEMALE_VOICES = listOf("zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural", "zh-CN-XiaoxuanNeural")
    private val ALL_VOICES = MALE_VOICES + FEMALE_VOICES

    private val TONE = mapOf(
        "沉稳" to (0 to 0), "中性" to (0 to 0), "温柔" to (-5 to -3), "活泼" to (8 to 3),
        "冷酷" to (-10 to -8), "威严" to (-15 to -8), "凶狠" to (5 to -5), "憨厚" to (-5 to 2),
        "俏皮" to (10 to 5), "焦急" to (15 to 8),
    )

    /** 段落语气 → (rate%, pitchHz)。 */
    fun toneParams(tone: String): Pair<Int, Int> = TONE[tone] ?: (0 to 0)

    private fun featureOf(segments: List<Segment>, speaker: String): Triple<String, String, String> {
        val s = segments.firstOrNull { it.speaker == speaker } ?: return Triple("unknown", "未知", "中性")
        return Triple(s.gender, s.age, s.tone)
    }

    private fun pickNarratorVoice(segments: List<Segment>): String {
        val tones = segments.filter { it.speaker == NARRATOR }.map { it.tone }
        if (tones.isEmpty()) return DEFAULT_NARRATOR_VOICE
        val top = tones.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "中性"
        return NARRATOR_TONE_VOICE[top] ?: DEFAULT_NARRATOR_VOICE
    }

    fun allocate(segments: List<Segment>, overrides: Map<String, RoleEntity>): Map<String, Voice> {
        val counts = segments.groupingBy { it.speaker }.eachCount()
        val map = mutableMapOf<String, Voice>()
        val used = mutableSetOf<String>()

        // 旁白
        val narr = pickNarratorVoice(segments)
        map[NARRATOR] = Voice(narr, 0, 0)
        used.add(narr)

        // 用户覆盖优先
        overrides.forEach { (name, r) ->
            map[name] = Voice(r.voice, r.pitch, r.rate)
            used.add(r.voice)
        }

        // 其余角色按频次降序分配，撞色降级
        val sorted = counts.entries
            .filter { it.key != NARRATOR && it.key !in overrides }
            .sortedByDescending { it.value }
            .map { it.key }
        for (sp in sorted) {
            val (g, a, _) = featureOf(segments, sp)
            val pool = when (g) {
                "male" -> listOfNotNull(MALE_BY_AGE[a]).let { it + MALE_VOICES.filter { v -> v != MALE_BY_AGE[a] } }
                "female" -> listOfNotNull(FEMALE_BY_AGE[a]).let { it + FEMALE_VOICES.filter { v -> v != FEMALE_BY_AGE[a] } }
                else -> ALL_VOICES.filter { it !in used }
            }
            var voice = pool.firstOrNull { it !in used } ?: FALLBACK_VOICE
            if (voice in used) voice = ALL_VOICES.firstOrNull { it !in used } ?: FALLBACK_VOICE
            map[sp] = Voice(voice, 0, 0)
            used.add(voice)
        }
        return map
    }
}
