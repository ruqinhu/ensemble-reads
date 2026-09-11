package com.ensemblereads.app.tts

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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Edge TTS 客户端（WebSocket 协议自实现）。Android 无官方库，
 * 本实现复用 PoC 验证过的协议：连接 readaloud 端点，发 speech.config + SSML，
 * 收二进制 audio 写文件。
 */
class EdgeTtsClient {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    companion object {
        const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        /** 单段合成看门狗：超时即取消连接，防止 WS 悬挂阻塞整条合成管线。 */
        private const val SINGLE_TIMEOUT_MS = 30_000L

        fun newConnectionId(): String = UUID.randomUUID().toString()

        fun buildSsml(text: String, voice: String, pitch: Int, rate: Int): String {
            val p = if (pitch == 0) "" else " pitch='${signedPercent(pitch)}'"
            val r = if (rate == 0) "" else " rate='${signedPercent(rate)}'"
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'><prosody$r$p>${xmlEscape(text)}</prosody></voice></speak>"
        }

        private fun signedPercent(v: Int) = if (v >= 0) "+$v%" else "$v%"
        private fun xmlEscape(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /** 合成到 dest；失败重试 3 次（退避）。返回是否成功。超时看门狗防止悬挂。 */
    suspend fun synthesize(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            var ok = false
            repeat(3) { attempt ->
                if (ok) return@withContext true
                ok = try {
                    withTimeoutOrNull(SINGLE_TIMEOUT_MS) {
                        runOnce(text, voice, pitch, rate, dest)
                    } ?: false
                } catch (e: Exception) {
                    delay(1500L * (attempt + 1)) // 可取消退避
                    false
                }
            }
            ok
        }

    private suspend fun runOnce(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val connId = newConnectionId()
            val url = "$WSS?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connId"
            var out = dest.outputStream()
            var done = false
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val config = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    webSocket.send("X-Timestamp:${System.currentTimeMillis()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" + config)
                    webSocket.send(buildSsml(text, voice, pitch, rate))
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    out.write(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Path:audio.metadata 等文本帧忽略
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    finish(dest.length() > 0)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(false)
                }

                private fun finish(ok: Boolean) {
                    if (done) return
                    done = true
                    try { out.close() } catch (e: Exception) { /* 忽略 */ }
                    if (!cont.isCancelled) cont.resume(ok)
                }
            }
            val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            cont.invokeOnCancellation { ws.cancel() }
        }
}
