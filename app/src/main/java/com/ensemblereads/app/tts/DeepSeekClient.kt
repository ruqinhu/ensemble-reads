package com.ensemblereads.app.tts

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 容错解析 DeepSeek 响应文本为 [Segment] 列表。纯函数，便于单测。 */
object DeepSeekParser {
    private val TONES = listOf("沉稳", "温柔", "活泼", "冷酷", "威严", "凶狠", "憨厚", "俏皮", "焦急")
    private val AGES = listOf("少年", "青年", "中年", "老年")

    fun parseResponse(raw: String): List<Segment> {
        var text = raw.trim()
        val fence = Regex("```(?:json)?\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (fence != null) text = fence.groupValues[1].trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) text = text.substring(start, end + 1)
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val body = o.optString("text", "").trim()
                if (body.isEmpty()) return@mapNotNull null
                val gender = o.optString("gender", "").lowercase()
                val age = o.optString("age", "")
                val tone = o.optString("tone", "")
                Segment(
                    speaker = o.optString("speaker", "").trim().ifEmpty { "旁白" },
                    text = body,
                    gender = if (gender == "male" || gender == "female") gender else "unknown",
                    age = if (age in AGES) age else "未知",
                    tone = if (tone in TONES) tone else "中性",
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}

/** DeepSeek（Anthropic 兼容协议）客户端。端点默认为火山方舟 coding 端点。 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://ark.cn-beijing.volces.com/api/coding",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = buildString {
        append("你是一个中文小说「多角色朗读」的角色解析器。\n")
        append("任务：把给定的小说文本切分成连续片段，为每个片段标注说话者角色。\n")
        append("规则：\n")
        append("1. 对话（引号内内容）标注为说话者角色；对话前有说话者前缀据此判断，无前缀结合上下文推断，无法确定归为“旁白”。\n")
        append("2. 叙述、心理、环境描写等非对话内容统一标注为“旁白”。\n")
        append("3. 保留原文顺序，逐句切分，不遗漏、不改写、不合并不同角色的相邻对话。\n")
        append("4. 标注 gender(male/female/unknown)、age(少年/青年/中年/老年/未知)、tone(沉稳/温柔/活泼/冷酷/威严/凶狠/憨厚/俏皮/焦急/中性)；旁白段标注整本书叙事语气。\n")
        append("只输出一个 JSON 数组，不要输出任何其他文字。元素格式：\n")
        append("[{\"speaker\":\"角色名\",\"text\":\"原文片段\",\"gender\":\"...\",\"age\":\"...\",\"tone\":\"...\"}]")
    }

    /** 解析一段章节文本。失败重试 1 次（指数退避），仍失败抛异常；空结果视为失败。 */
    suspend fun parse(chapterId: Long, text: String): List<Segment> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                val body = JSONObject().apply {
                    put("model", "deepseek-v4-flash")
                    put("system", systemPrompt)
                    put("max_tokens", 16000)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "请解析下面的小说文本：\n\n$text")
                    }))
                }.toString()
                val req = Request.Builder()
                    .url("$baseUrl/v1/messages")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val json = JSONObject(resp.body?.string() ?: "")
                    val content = json.getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val b = content.optJSONObject(i)
                        if (b?.optString("type") == "text") sb.append(b.optString("text"))
                    }
                    val segs = DeepSeekParser.parseResponse(sb.toString())
                    // 空结果（如 max_tokens 截断导致 JSON 不完整）视为失败，避免静默无声
                    if (segs.isEmpty() && text.isNotBlank()) throw RuntimeException("DeepSeek 解析结果为空")
                    return@withContext segs
                }
            } catch (e: CancellationException) {
                throw e // 协程取消不应被吞掉再重试
            } catch (e: Exception) {
                last = e
                delay(500L * (attempt + 1)) // 指数退避
            }
        }
        throw last ?: RuntimeException("DeepSeek parse failed")
    }
}
