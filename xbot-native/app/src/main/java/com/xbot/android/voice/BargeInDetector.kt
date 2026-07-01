package com.xbot.android.voice

/**
 * Barge-in 检测器：TTS 播放期间检测用户说话打断。对应 Flutter barge_in_detector.dart。
 *
 * 策略（防 TTS 回声误触发）：
 * 1. 高阈值 [speechThreshold]=0.18（TTS 回声 RMS 归一化通常 <0.12，近距离人声 >0.3）
 * 2. 连续 [consecutiveFrames]=3 帧（~300ms）持续高能量才触发
 * 3. [cooldownMs]=500：TTS 开始后 500ms 内不检测（避免播放起始瞬态）
 *
 * 默认半双工模式下不会触发（TTS 时已停麦）；仅当 bargeInEnabled 时由 VoiceAssistant 接入。
 */
class BargeInDetector(
    private val speechThreshold: Float = 0.18f,
    private val consecutiveFrames: Int = 3,
    private val cooldownMs: Long = 500L,
    private val rmsDenom: Float = 6000f,
) {
    @Volatile private var started = false
    private var firstChunkAt = 0L
    private var consecutive = 0

    /** 开始检测。 */
    fun start() {
        started = true
        firstChunkAt = 0L
        consecutive = 0
    }

    /** 停止检测。 */
    fun stop() {
        started = false
        consecutive = 0
    }

    /**
     * 喂入一段 PCM16 chunk，返回是否检测到用户打断。
     * 在 AudioCapture 的 chunk 回调中调用。
     */
    fun feed(samples: ShortArray, now: Long): Boolean {
        if (!started) return false
        if (firstChunkAt == 0L) {
            firstChunkAt = now
            return false
        }
        // 冷却期内不检测。
        if (now - firstChunkAt < cooldownMs) return false
        val level = rmsLevel(samples)
        if (level >= speechThreshold) {
            consecutive++
            if (consecutive >= consecutiveFrames) {
                started = false
                return true
            }
        } else {
            consecutive = 0
        }
        return false
    }

    private fun rmsLevel(samples: ShortArray): Float {
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        return (rms / rmsDenom).toFloat().coerceIn(0f, 1f)
    }
}
