package com.ensemblereads.app.tts

import com.ensemblereads.app.debug.RequestLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Edge TTS 客户端（WebSocket 协议自实现），协议对齐 edge-tts 7.2.8。
 * 关键点（此前全部缺失导致合成 100% 失败）：
 *  1. 连接 URL 必须带 Sec-MS-GEC 签名 + Sec-MS-GEC-Version，否则服务端 403 拒绝；
 *  2. SSML 必须带 X-RequestId/Content-Type:application/ssml+xml/X-Timestamp/Path:ssml 头；
 *  3. 音频二进制帧前 2 字节是 header 长度，需剥离 header 后才是 MP3 数据；
 *  4. 连接头带 Edge UA + Origin + Cookie(muid)，避免风控。
 */
class EdgeTtsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        /** Sec-MS-GEC-Version 跟随 Chromium 版本（与 edge-tts constants 一致）。 */
        private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
        /** Windows 文件时间 epoch（1601-01-01）与 Unix epoch（1970-01-01）的秒差。 */
        private const val WIN_EPOCH_SECONDS = 11_644_473_600L
        /** 单段合成看门狗：超时即取消连接，防止 WS 悬挂阻塞整条合成管线。 */
        private const val SINGLE_TIMEOUT_MS = 30_000L

        /** edge-tts 同款 ConnectionId：32 位小写 hex（无横线）。 */
        fun newConnectionId(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * 生成 Sec-MS-GEC 签名：当前 UTC 秒换算成 Windows 文件时间，向下取整到 5 分钟窗口，
         * 拼接 TRUSTED_TOKEN 后做 SHA-256，返回大写 hex。算法与 edge-tts drm.py 完全一致。
         */
        fun generateSecMsGec(): String {
            var ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH_SECONDS
            ticks -= ticks % 300L   // 对齐 5 分钟（300 秒）窗口
            ticks *= 10_000_000L    // 秒 → 100ns 间隔（Windows 文件时间格式）
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$ticks$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

        /** SSML：与 edge-tts mkssml 同构，pitch 单位 Hz、rate 单位 %，恒输出三属性。 */
        fun buildSsml(text: String, voice: String, pitch: Int, rate: Int): String {
            val p = if (pitch >= 0) "+$pitch" else "$pitch"
            val r = if (rate >= 0) "+$rate%" else "$rate%"
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'><prosody pitch='${p}Hz' rate='$r' volume='+0%'>" +
                "${xmlEscape(text)}</prosody></voice></speak>"
        }

        private fun xmlEscape(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        /** edge-tts date_to_string 同款：JS 风格 UTC 时间字符串。 */
        private val DATE_FMT = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private fun dateToString(): String = DATE_FMT.format(Date())
    }

    /** 拉取 Edge TTS 全量音色列表（GET voices/list，请求头对齐 edge-tts SpeakerManager）。 */
    suspend fun listVoices(): List<VoiceInfo> = withContext(Dispatchers.IO) {
        val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_TOKEN&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val request = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
            )
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("voices/list HTTP ${resp.code}")
            parseVoiceList(resp.body?.string() ?: "")
        }
    }

    /** 解析 voices/list 返回的 JSON → [VoiceInfo] 列表。纯函数，便于单测。 */
    fun parseVoiceList(json: String): List<VoiceInfo> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val shortName = o.optString("ShortName")
            if (shortName.isBlank()) return@mapNotNull null
            VoiceInfo(
                id = shortName,
                label = o.optString("FriendlyName", shortName),
                gender = o.optString("Gender", ""),
                locale = o.optString("Locale", ""),
            )
        }
    }.getOrDefault(emptyList())

    /** 合成到 dest；失败重试 3 次（退避）。返回是否成功。超时看门狗防止悬挂。 */
    suspend fun synthesize(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            RequestLogger.log("EdgeTTS", "合成 ${dest.name} voice=$voice 文本${text.length}字符")
            var ok = false
            repeat(3) { attempt ->
                if (ok) return@withContext true
                ok = try {
                    withTimeoutOrNull(SINGLE_TIMEOUT_MS) {
                        runOnce(text, voice, pitch, rate, dest)
                    } ?: false
                } catch (e: Exception) {
                    RequestLogger.log("EdgeTTS", "${dest.name} 合成异常: ${e.javaClass.simpleName}: ${e.message}", ok = false)
                    delay(1500L * (attempt + 1)) // 可取消退避
                    false
                }
            }
            if (ok) RequestLogger.log("EdgeTTS", "${dest.name} 合成成功") else RequestLogger.log("EdgeTTS", "${dest.name} 合成失败", ok = false)
            ok
        }

    private suspend fun runOnce(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val connId = newConnectionId()
            // URL 查询参数对齐 edge-tts：TrustedClientToken + ConnectionId + Sec-MS-GEC(+Version)
            val url = "$WSS?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connId" +
                "&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
            var out = dest.outputStream()
            var done = false
            var audioOk = false
            val muid = newConnectionId().uppercase()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // speech.config：sentenceBoundary 开（供 metadata），不关心 wordBoundary
                    val config = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    webSocket.send(
                        "X-Timestamp:${dateToString()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" + config
                    )
                    // SSML 必须带 Path:ssml 头（X-Timestamp 末尾 Z 是 Edge 已知要求，照抄 edge-tts）
                    webSocket.send(
                        "X-RequestId:${newConnectionId()}\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${dateToString()}Z\r\n" +
                            "Path:ssml\r\n\r\n" +
                            buildSsml(text, voice, pitch, rate)
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    // 二进制帧：前 2 字节（大端）为 header 长度，剥离 header 后才是音频数据
                    val buf = bytes.toByteArray()
                    if (buf.size < 2) return
                    val headerLength = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (buf.size < headerLength + 2) return
                    val audio = buf.copyOfRange(headerLength + 2, buf.size)
                    if (audio.isNotEmpty()) {
                        out.write(audio)
                        audioOk = true
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 收到 Path:turn.end 表示本段合成正常结束
                    if (text.contains("Path:turn.end")) finish(audioOk)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // 兜底：服务端直接关闭连接（未发 turn.end）时按是否收到音频判定
                    finish(audioOk)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 诊断：握手失败(非101，如403)时 response 带 HTTP 响应；网络失败时 t 为 IOException
                    val respInfo = response?.let { "HTTP ${it.code} ${it.message}" } ?: "无HTTP响应"
                    android.util.Log.w("EdgeTTS", "${dest.name} WSS失败: ${t.javaClass.name}: ${t.message} [$respInfo]", t)
                    RequestLogger.log("EdgeTTS", "${dest.name} 连接失败: ${t.javaClass.simpleName}: ${t.message} [$respInfo]", ok = false)
                    finish(false)
                }

                private fun finish(ok: Boolean) {
                    if (done) return
                    done = true
                    try { out.close() } catch (e: Exception) { /* 忽略 */ }
                    if (!cont.isCancelled) cont.resume(ok)
                }
            }

            // 连接头对齐 edge-tts WSS_HEADERS：Edge UA + Origin + Cookie(muid)
            val request = Request.Builder().url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
                )
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=$muid")
                .build()
            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }
}
