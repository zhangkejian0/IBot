package com.xbot.android.voice

/**
 * 语音助手状态机阶段。对应 Flutter voice_state.dart。
 *
 * 正常一轮对话：idle → waking → listening → thinking → speaking → idle。
 * 任一阶段出错或被中断都会回到 idle。
 */
enum class VoiceState {
    /** 未启用 / 空闲（若开启唤醒则持续监听唤醒词）。 */
    IDLE,
    /** 刚检测到唤醒词，正切换到聆听态（短暂过渡）。 */
    WAKING,
    /** 聆听用户说话：麦克风采集中，同步送云端 ASR。 */
    LISTENING,
    /** 思考中：等待 LLM 回复。 */
    THINKING,
    /** 播报中：TTS 合成并播放回复。 */
    SPEAKING;

    /** 是否处于活跃对话中（非 idle）。 */
    val isActive: Boolean get() = this != IDLE

    /** 映射到虚拟形象前端 FaceState 字符串。 */
    val faceState: String
        get() = when (this) {
            IDLE -> "idle"
            WAKING, LISTENING -> "listening"
            THINKING -> "thinking"
            SPEAKING -> "happy"
        }
}
