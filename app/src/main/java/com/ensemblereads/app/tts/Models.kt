package com.ensemblereads.app.tts

/** 一段被解析出的朗读单元。gender/age/tone 来自 DeepSeek。 */
data class Segment(
    val speaker: String,
    val text: String,
    val gender: String = "unknown",
    val age: String = "未知",
    val tone: String = "中性",
)

/** 音色参数：id 为 Edge TTS 音色 ID；pitch 单位 Hz（正负）；rate 单位 %（正负）。 */
data class Voice(val id: String, val pitch: Int = 0, val rate: Int = 0)

/** Edge TTS 音色信息（voices/list 接口返回，供音色选择器展示/搜索/试听）。 */
data class VoiceInfo(val id: String, val label: String, val gender: String, val locale: String)
