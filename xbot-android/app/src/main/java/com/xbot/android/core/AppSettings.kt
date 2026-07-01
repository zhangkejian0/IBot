package com.xbot.android.core

import kotlinx.serialization.Serializable

/**
 * 应用设置（对应 Flutter AppSettings 的子集）。持久化到 settings.json。
 *
 * 分组：显示模式 / 识别功能 / 语音助手 / Pophie 服务 / 人物日志。
 *
 * 说明：robotId 首次生成后持久化（保持设备身份与会话记忆连续），
 * sessionId 由服务端回传后持久化以延续多轮会话记忆。
 */
@Serializable
data class AppSettings(
    // —— 显示模式 ——
    /** 调试模式：true 显示摄像头识别覆盖层，false 显示虚拟形象。 */
    val debugMode: Boolean = false,

    // —— 识别功能 ——
    val faceEnabled: Boolean = true,
    val handEnabled: Boolean = true,
    val objectEnabled: Boolean = true,
    val identityEnabled: Boolean = true,

    // —— 语音助手 ——
    val voiceEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val streamingSttEnabled: Boolean = true,
    val wakeWord: String = "你好小白",

    // —— Pophie 服务 ——
    val baseUrl: String = "http://223.109.143.135:8000",
    val voiceId: String = "",
    val robotId: String = "",
    val sessionId: String? = null,
)
