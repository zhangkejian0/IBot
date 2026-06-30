package com.xbot.android.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pophie 后端客户端（STT+LLM+TTS）。对应 Flutter pophie_client.dart。
 *
 * 端点：
 * - POST /api/chat          批量 STT+LLM+TTS（skip_tts=true 时只回文本）
 * - POST /api/chat/stream   流式对话 NDJSON：{type:speak,text} → {type:done,response}
 * - POST /api/tts/stream    流式 TTS NDJSON：meta → chunk(base64 pcm) → done
 * - GET  /api/proactive_messages   主动消息（每 5s 轮询）
 * - PUT/GET/DELETE /api/robots/{robot_id}/owner   主人档案
 *
 * 音频：16kHz mono PCM16 WAV（44 字节头）base64。TTS 返回 PCM16 mono @22050。
 */
class PophieClient(
    var config: PophieConfig,
) {
    companion object {
        private const val TAG = "PophieClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val NDJSON = "application/x-ndjson".toMediaType()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    // ============ /api/chat（批量）============

    data class ChatResult(
        val text: String,
        val facialExpression: String?,
        val robotState: String?,
        val sttText: String?,
        val sessionId: String?,
        val isSilent: Boolean = text.isBlank(),
    )

    /**
     * 批量对话：发送 WAV(base64) + 感知上下文，skip_tts=true（流式 TTS 单独走）。
     * @param wavBytes 16kHz mono WAV；为 null 则纯文本输入
     * @param text 纯文本输入（voice-only 时为 ""）
     */
    suspend fun chat(
        wavBytes: ByteArray? = null,
        text: String = "",
        perception: JSONObject? = null,
        userId: String? = null,
    ): ChatResult = withContext(Dispatchers.IO) {
        val input = JSONObject()
        if (text.isNotEmpty()) input.put("text", text)
        if (wavBytes != null) {
            val audio = JSONObject()
            audio.put("format", "wav")
            audio.put("encoding", "base64")
            audio.put("sample_rate", 16000)
            audio.put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP))
            input.put("audio", audio)
        }
        if (perception != null) input.put("perception", perception)
        if (config.voiceId.isNotEmpty()) input.put("voice_id", config.voiceId)
        input.put("skip_tts", true)

        val body = JSONObject()
        body.put("robot_id", config.robotId)
        config.sessionId?.let { body.put("session_id", it) }
        userId?.let { body.put("user_id", it) }
        body.put("input", input)

        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/chat")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val resp = await(client.newCall(req))
        val root = JSONObject(resp.body!!.string())
        val output = root.optJSONObject("output")
        val sid = root.optString("session_id")
        if (sid.isNotEmpty()) config.sessionId = sid
        ChatResult(
            text = output?.optString("text") ?: "",
            facialExpression = output?.optString("facial_expression"),
            robotState = output?.optString("robot_state"),
            sttText = root.optJSONObject("stt")?.optString("text"),
            sessionId = sid.takeIf { it.isNotEmpty() },
        )
    }

    // ============ /api/chat/stream（NDJSON）============

    /**
     * 流式对话：逐句 onSpeak（TTS 每段），最终 onDone 返回完整结果。
     */
    suspend fun chatStream(
        text: String,
        perception: JSONObject? = null,
        userId: String? = null,
        onSpeak: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val input = JSONObject()
        input.put("text", text)
        if (perception != null) input.put("perception", perception)
        if (config.voiceId.isNotEmpty()) input.put("voice_id", config.voiceId)
        input.put("skip_tts", true)
        val body = JSONObject()
        body.put("robot_id", config.robotId)
        config.sessionId?.let { body.put("session_id", it) }
        userId?.let { body.put("user_id", it) }
        body.put("input", input)

        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/chat/stream")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/x-ndjson")
            .build()
        val resp = await(client.newCall(req))
        val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
        var result: ChatResult = ChatResult(text = "", null, null, null, null)
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                try {
                    val obj = JSONObject(line)
                    when (obj.optString("type")) {
                        "speak" -> onSpeak(obj.optString("text"))
                        "done" -> {
                            val output = obj.optJSONObject("response")?.optJSONObject("output")
                            val sid = obj.optJSONObject("response")?.optString("session_id")
                            if (!sid.isNullOrEmpty()) config.sessionId = sid
                            result = ChatResult(
                                text = output?.optString("text") ?: "",
                                facialExpression = output?.optString("facial_expression"),
                                robotState = output?.optString("robot_state"),
                                sttText = obj.optJSONObject("response")?.optJSONObject("stt")?.optString("text"),
                                sessionId = sid,
                            )
                        }
                        "error" -> throw IOException(obj.optString("message"))
                    }
                } catch (_: Exception) {
                    // 跳过坏行。
                }
            }
            line = reader.readLine()
        }
        result
    }

    // ============ /api/tts/stream（NDJSON）============

    /**
     * 流式 TTS：meta(sampleRate) → chunk(base64 pcm16) → done。
     * @param onMeta 返回采样率（默认 22050）
     * @param onChunk 返回解码后的 PCM16 字节
     * @param onDone 返回首包延迟 ms
     */
    suspend fun ttsStream(
        text: String,
        voice: JSONObject? = null,
        onMeta: (Int) -> Unit,
        onChunk: (ByteArray) -> Unit,
        onDone: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
        body.put("text", text)
        if (voice != null) body.put("voice", voice)
        if (config.voiceId.isNotEmpty()) body.put("voice_id", config.voiceId)
        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/tts/stream")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/x-ndjson")
            .build()
        val call = client.newCall(req)
        val resp = await(call)
        val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
        var line = reader.readLine()
        while (line != null) {
            if (line.isNotBlank()) {
                try {
                    val obj = JSONObject(line)
                    when (obj.optString("type")) {
                        "meta" -> onMeta(obj.optInt("sample_rate", 22050))
                        "chunk" -> {
                            val data = obj.optString("data")
                            if (data.isNotEmpty()) onChunk(Base64.decode(data, Base64.NO_WRAP))
                        }
                        "done" -> onDone(obj.optInt("first_packet_ms"))
                        "error" -> throw IOException(obj.optString("message"))
                    }
                } catch (e: IOException) {
                    throw e
                } catch (_: Exception) {
                    // 跳过坏行。
                }
            }
            line = reader.readLine()
        }
    }

    /** 取消所有进行中的请求（barge-in 用）。 */
    fun cancelAll() {
        client.dispatcher.cancelAll()
    }

    /** 健康检查（设置页「测试连接」）：能拿到任意 HTTP 响应即视为后端可达。 */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/api/schema")
                .get()
                .build()
            await(client.newCall(req)).use { it.code in 200..499 }
        } catch (_: Exception) {
            false
        }
    }

    // ============ /api/proactive_messages ============

    data class ProactiveMessage(val id: Long, val content: String, val trigger: String)

    suspend fun fetchProactiveMessages(sinceId: Long): List<ProactiveMessage> = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl.trimEnd('/')}/api/proactive_messages" +
            "?robot_id=${config.robotId}&since_id=$sinceId&limit=10" +
            (config.sessionId?.let { "&session_id=$it" } ?: "")
        val req = Request.Builder().url(url).get().build()
        val resp = await(client.newCall(req))
        val root = JSONObject(resp.body!!.string())
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<ProactiveMessage>(items.length())
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val meta = it.optJSONObject("metadata")
            val trigger = meta?.optString("trigger") ?: "reminder"
            out.add(ProactiveMessage(it.optLong("id"), it.optString("content"), trigger))
        }
        out
    }

    // ============ /api/robots/{id}/owner ============

    suspend fun registerOwner(ownerJson: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/robots/${config.robotId}/owner")
            .put(ownerJson.toRequestBody(JSON))
            .build()
        try { await(client.newCall(req)).code in 200..299 } catch (_: Exception) { false }
    }

    // ============ helpers ============

    /** OkHttp Call → Response（挂起，自动 close body 由调用方在读完流后处理）。 */
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                Log.e(TAG, "请求失败: ${e.message}")
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(c: Call, r: Response) {
                if (cont.isActive) cont.resume(r)
            }
        })
    }

    /** PCM16 → WAV（44 字节头 + PCM）。 */
    fun pcm16ToWav(pcm16: ShortArray): ByteArray {
        val byteCount = pcm16.size * 2
        val out = java.io.ByteArrayOutputStream(44 + byteCount)
        val little = java.nio.ByteOrder.LITTLE_ENDIAN
        val bb = java.nio.ByteBuffer.allocate(44).order(little)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + byteCount); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1) // PCM
        bb.putShort(1); bb.putInt(16000); bb.putInt(16000 * 2) // byteRate
        bb.putShort(2); bb.putShort(16) // bits
        bb.put("data".toByteArray()); bb.putInt(byteCount)
        out.write(bb.array())
        val data = java.nio.ByteBuffer.allocate(byteCount).order(little)
        for (s in pcm16) data.putShort(s)
        out.write(data.array())
        return out.toByteArray()
    }

    /** PCM16 short[] → byte[]（小端）。 */
    fun pcm16ToBytes(samples: ShortArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }
}
