package com.xbot.android.voice

import java.util.UUID

/**
 * Pophie 后端配置。对应 Flutter pophie_config.dart。
 *
 * - baseUrl：后端地址（可在设置页修改），默认 http://223.109.143.135:8000
 * - robotId：本设备唯一标识，首次生成 robot-<uuid> 并持久化
 * - sessionId：会话标识，首轮后由服务端回传并保存，重启复用以延续会话记忆
 * - voiceId：TTS 音色 ID，空则用服务端默认
 */
data class PophieConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val robotId: String = newRobotId(),
    var sessionId: String? = null,
    val voiceId: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://223.109.143.135:8000"
        fun newRobotId(): String = "robot-${UUID.randomUUID()}"
    }

    /** 把 HTTP(S) 基地址转为流式 STT 的 WebSocket URL。 */
    fun sttWsUrl(): String {
        var b = baseUrl.trimEnd('/')
        b = when {
            b.startsWith("https://") -> "wss://${b.substringAfter("https://")}"
            b.startsWith("http://") -> "ws://${b.substringAfter("http://")}"
            else -> "ws://$b"
        }
        return "$b/api/stt/stream"
    }
}
