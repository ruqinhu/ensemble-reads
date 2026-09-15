package com.ensemblereads.app.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局后台请求日志：记录 DeepSeek 解析、Edge TTS 合成、章节合成等请求的状态，
 * 供全局悬浮面板实时查看（排障/观察后台在请求什么）。
 */
object RequestLogger {
    data class Entry(
        val time: Long,
        val tag: String,
        val message: String,
        val ok: Boolean = true,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** 追加一条日志（新记录在前，最多保留 200 条）。 */
    fun log(tag: String, message: String, ok: Boolean = true) {
        _entries.update { (listOf(Entry(System.currentTimeMillis(), tag, message, ok)) + it).take(200) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** HH:mm:ss 时间格式化，供 UI 显示。 */
    fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}
