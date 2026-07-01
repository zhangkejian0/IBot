package com.xbot.android.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 流式 STT WebSocket 客户端（/api/stt/stream）。对应 Flutter stt_stream_client.dart。
 *
 * 协议：JSON 文本帧。
 * - 发送：start{sample_rate,language} → chunk{data:base64(pcm16)} → commit/end
 * - 接收：meta/ready/partial/final(含 voice)/stop_speaking/session_end/error/closed
 *
 * 空闲超时由服务端主动 session_end + 关 WS。
 * stop_speaking：服务端在聆听期判定用户要立即停止语音交互时推送（关键词初筛+LLM 确认）。
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
        /** 服务端判定用户要立即停止语音交互（聆听期 barge-in 退出）。 */
        data class StopSpeaking(val text: String, val reason: String?) : Event()
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

    /**
     * 连接并监听事件。**挂起直到握手完成（onOpen）**；连接失败抛异常（上层 fallback 批量）。
     *
     * 关键：必须等 WS 真正建连成功再进入聆听流程，否则会出现「双击后很快回 idle、
     * 听不到交互」——因为连接失败时 onFailure 立即触发 Error/Closed 结束会话。
     */
    suspend fun connect(onEvent: (Event) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        listener = onEvent
        val opened = AtomicBoolean(false)
        val req = Request.Builder().url(wsUrl).build()
        cont.invokeOnCancellation { runCatching { ws?.cancel() } }
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                if (cont.isActive) cont.resume(Unit)
            }
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
                    "stop_speaking" -> Event.StopSpeaking(obj.optString("text"), obj.optString("reason").takeIf { it.isNotEmpty() })
                    "error" -> Event.Error(obj.optString("message"))
                    else -> return
                }
                emit(ev)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS 失败: ${t.message}")
                // 握手前失败：让 connect 抛异常，上层回退批量；握手后失败：发事件结束会话。
                if (!opened.get() && cont.isActive) {
                    cont.resumeWithException(t)
                } else {
                    emit(Event.Error("WebSocket 错误"))
                    emit(Event.Closed)
                }
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
