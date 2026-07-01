package com.xbot.android.voice

/**
 * 音频门控（自适应能量 + 近场判据），用于抗噪：挡住低能量环境杂音与小音量旁人说话。
 *
 * 判据（每帧）：
 * - 归一化 RMS（rms(short)/32768）需同时高于「绝对下限 [absFloor]」与「动态底噪×[openMargin]」
 *   才判为近场语音；否则视为噪声。
 * - 仅在非语音帧用 EMA 缓慢更新底噪估计，自适应不同环境（避免语音把底噪抬高）。
 * - 语音帧刷新 hangover；hangover 期间（[hangoverFrames] 帧）即使低能量也放行，保护句尾自然
 *   静音（喂给下游识别器/后端以正常触发端点），且避免因门控提前切断整句。
 *
 * 线程模型：**非线程安全**，每个实例仅供单一线程（采集线程）顺序调用。字幕侧与上行侧各持
 * 一个独立实例，互不干扰。
 *
 * 复用者：
 * - [StreamingAsrService.feedPcm16]（端侧字幕，onClose 请求 reset 清残留文本）
 * - [VoiceAssistant] 流式上行 feedListener（云端 STT，onClose 目前无需动作）
 *
 * @param absFloor 归一化 RMS 绝对下限。低于此一律当噪声，挡住小音量旁人。0.02 ≈ short RMS 655。
 *   调大→更严格（更抗旁人，但要求说话更近/更大声）。
 * @param openMargin 相对 SNR 判据：帧能量需高于「底噪×此倍数」（≈15dB 余量）。嘈杂环境可上调。
 * @param hangoverFrames 语音结束后继续放行的帧数（每帧 100ms）。15(~1.5s) > 端点静音判据(1.2s)。
 * @param noiseFloorInit 底噪初值（归一化 RMS）。
 * @param noiseFloorAlpha 底噪 EMA 学习系数（仅非语音帧更新）。
 */
class AudioGate(
    private val absFloor: Float = 0.02f,
    private val openMargin: Float = 6.0f,
    private val hangoverFrames: Int = 15,
    private val noiseFloorInit: Float = 0.0025f,
    private val noiseFloorAlpha: Float = 0.02f,
) {
    private var noiseFloor = noiseFloorInit
    private var remainingHangover = 0
    /** 自上次关闭以来是否放行过（用于门控从开→关的那一刻触发一次 onClose）。 */
    private var wasOpen = false

    /**
     * 判断当前帧是否应放行（喂入识别器 / 上行后端）。
     *
     * @param samples 一帧 PCM16
     * @param onClose 门控从「开」跌落到「完全关闭」的那一刻回调一次（用于清理累积状态）；
     *   持续关闭期间不重复回调。
     * @return true=放行；false=门控关闭，丢弃该帧
     */
    fun accept(samples: ShortArray, onClose: (() -> Unit)? = null): Boolean {
        val rms = normalizedRms(samples)
        val threshold = maxOf(absFloor, noiseFloor * openMargin)
        val isSpeech = rms > threshold

        if (isSpeech) {
            remainingHangover = hangoverFrames
        } else {
            noiseFloor = noiseFloor * (1f - noiseFloorAlpha) + rms * noiseFloorAlpha
            if (remainingHangover > 0) remainingHangover--
        }

        val open = isSpeech || remainingHangover > 0
        if (open) {
            wasOpen = true
            return true
        }
        if (wasOpen) {
            wasOpen = false
            onClose?.invoke()
        }
        return false
    }

    /** 复位门控状态（新聆听窗口 / 跨会话时调用），避免残留 hangover/底噪。 */
    fun reset() {
        noiseFloor = noiseFloorInit
        remainingHangover = 0
        wasOpen = false
    }

    private fun normalizedRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
