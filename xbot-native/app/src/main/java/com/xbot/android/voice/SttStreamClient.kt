package com.xbot.android.voice

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 流式 STT WebSocket 客户端（/api/stt/stream）。对应 Flutter stt_stream_client.dart。
 *
 * 协议：JSON 文本帧。
 * - 发送：start{sample_rate,language} → chunk{data:base64(pcm16)} → commit/end
 * - 接收：meta/ready/partial/final(含 voice)/session_end/error/closed
 *
 * 空闲超时由服务端主动 session_end + 关 WS。
 */
class SttStreamClient(
    private val wsUrl: String,
) {
    companion object { private const val TAG = "SttStreamClient" }

    /** STT 事件。 */
    sealed class Event {
        data class Meta(val silenceCommitMs: Int?, val conversationIdleSec: Int?) : Event()
        object Ready : Event()
        data class Partial(val text: String) : Event()
        data class Final(val text: String, val voice: JSONObject?) : Event()
        data class SessionEnd(val message: String?, val conversationIdleSec: Int?) : Event()
        data class Error(val message: String?) : Event()
        object Closed : Event()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 流式不超时
        .build()
    private var ws: WebSocket? = null
    private var listener: ((Event) -> Unit)? = null
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed

    /** 连接并监听事件。连接失败抛异常（上层 fallback 批量）。 */
    fun connect(onEvent: (Event) -> Unit) {
        listener = onEvent
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = try { JSONObject(text) } catch (_: Exception) { return }
                val ev = when (obj.optString("type")) {
                    "meta" -> Event.Meta(
                        obj.optInt("silence_commit_ms").takeIf { it > 0 },
                        obj.optInt("conversation_idle_sec").takeIf { it > 0 },
                    )
                    "ready" -> Event.Ready
                    "partial" -> Event.Partial(obj.optString("text"))
                    "final" -> Event.Final(obj.optString("text"), obj.optJSONObject("voice"))
                    "session_end" -> Event.SessionEnd(obj.optString("message"), obj.optInt("conversation_idle_sec").takeIf { it > 0 })
                    "error" -> Event.Error(obj.optString("message"))
                    else -> return
                }
                emit(ev)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS 失败: ${t.message}")
                emit(Event.Error("WebSocket 错误"))
                emit(Event.Closed)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emit(Event.Closed)
            }
        })
    }

    fun start(sampleRate: Int = 16000, language: String = "zh") {
        send(JSONObject().apply {
            put("type", "start"); put("sample_rate", sampleRate); put("language", language)
        })
    }

    fun sendChunk(pcm16: ByteArray) {
        if (closed || pcm16.isEmpty()) return
        send(JSONObject().apply {
            put("type", "chunk"); put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
        })
    }

    fun commit() = send(JSONObject().put("type", "commit"))
    fun sendEnd() = send(JSONObject().put("type", "end"))

    private fun send(obj: JSONObject) {
        if (closed) return
        try { ws?.send(obj.toString()) } catch (_: Exception) {}
    }

    private fun emit(ev: Event) { listener?.invoke(ev) }

    /** 关闭（幂等）。可选先发 end。 */
    fun close(sendEndFrame: Boolean = false) {
        if (closed) return
        if (sendEndFrame) sendEnd()
        closed = true
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
    }
}
