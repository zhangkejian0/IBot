package com.xbot.android.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采流服务。对应 Flutter audio_capture_service.dart。
 *
 * - 格式：16kHz / mono / PCM16bit，AudioSource=VOICE_RECOGNITION（**不**开系统 AGC/AEC/NS，
 *   保留原始未处理信号供识别器；回声靠半双工避免，见 VoiceAssistant）
 * - 端侧降噪：注入 [denoiser]（GTCRN）后，广播的 chunk 与 [level] 均为降噪后值；
 *   未注入或未就绪时直通原始 PCM，功能不受影响
 * - 暴露 [chunkListeners]：每个 PCM16 chunk 广播给消费者（唤醒词 + STT）
 * - 暴露 [level]：归一化音量 0..1（驱动虚拟形象嘴部张合），RMS/6000 + EMA 0.6/0.4
 *
 * [externalLevel]：TTS 播放时置 true，停止写 level（由 TTS 驱动嘴部）。
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
) {
    companion object {
        private const val TAG = "AudioCapture"
        private const val RMS_DENOM = 6000f
    }

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 归一化音量 0..1（线程安全读取，由 capture 线程写）。 */
    @Volatile
    var level: Float = 0f
        private set

    /** TTS 是否接管 level（true 时停止写 level）。 */
    @Volatile
    var externalLevel: Boolean = false

    /**
     * 端侧降噪器（GTCRN）。注入后，[start] 广播的 chunk 与 [level] 均为降噪后值。
     * 未注入或未就绪时直通原始 PCM。可运行时热切换（采集线程读取 @Volatile 引用）。
     */
    @Volatile
    var denoiser: SpeechDenoiser? = null

    private val chunkListeners = mutableListOf<(ShortArray) -> Unit>()
    private val listenersLock = Any()

    /** 是否有麦克风权限。 */
    fun hasPermission(pkg: android.content.Context): Boolean =
        ContextCompat.checkSelfPermission(pkg, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun addChunkListener(listener: (ShortArray) -> Unit) {
        synchronized(listenersLock) { chunkListeners.add(listener) }
    }

    fun removeChunkListener(listener: (ShortArray) -> Unit) {
        synchronized(listenersLock) { chunkListeners.remove(listener) }
    }

    /** 开始采流。 */
    @SuppressLint("MissingPermission")
    fun start(context: android.content.Context) {
        if (running.get()) return
        if (!hasPermission(context)) {
            Log.w(TAG, "无麦克风权限，采流不启动")
            return
        }
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf * 2, 3200) // 至少 100ms@16k
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 含 AGC/AEC/NS 提示
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            r.release()
            return
        }
        record = r
        r.startRecording()
        running.set(true)
        captureThread = Thread({
            val buf = ShortArray(1600) // 100ms@16k
            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    val raw = if (n == buf.size) buf else buf.copyOf(n)
                    // 端侧降噪（GTCRN）：未注入/未就绪时 denoise() 直通，无额外开销。
                    val den = denoiser
                    val cleaned = if (den != null) den.denoise(raw) else raw
                    // 音量用降噪后值（除非 TTS 接管）。
                    if (!externalLevel) {
                        val newLevel = rmsLevel(cleaned)
                        level = level * 0.6f + newLevel * 0.4f
                    }
                    // 广播降噪后 chunk（唤醒词 / STT 均受益）。
                    val snapshot: List<(ShortArray) -> Unit>
                    synchronized(listenersLock) { snapshot = chunkListeners.toList() }
                    for (l in snapshot) l(cleaned)
                }
            }
        }, "xbot-audio-capture").apply { isDaemon = true; start() }
    }

    /** 停止采流。 */
    fun stop() {
        running.set(false)
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        captureThread = null
        level = 0f
    }

    /** 计算 RMS 音量，归一化到 0..1。 */
    private fun rmsLevel(samples: ShortArray): Float {
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        return (rms / RMS_DENOM).toFloat().coerceIn(0f, 1f)
    }

    // ============ VAD 端点检测（批量模式用）============

    /** VAD 采样结果。 */
    data class VadResult(val pcm16: ShortArray, val silenceMs: Long?)

    /**
     * 阻塞采集一段用户语音（带端点检测），供批量对话模式用。
     * 对应 Flutter audio_capture_service.captureUtterance。
     *
     * - onset 阈值 0.15，需连续 3 帧（~300ms）确认说话开始
     * - sustain 阈值 0.08，低于此算静音
     * - 说话开始后静音 500ms 即认为结束
     * - 最长 12s，onset 超时 10s
     *
     * **必须在后台线程调用**（阻塞直到返回）。
     */
    fun captureUtterance(
        context: android.content.Context,
        maxDurationMs: Long = 12000L,
        silenceTimeoutMs: Long = 500L,
        onsetTimeoutMs: Long = 10000L,
        speechThreshold: Float = 0.15f,
        silenceThreshold: Float = 0.08f,
        speechOnsetFrames: Int = 3,
    ): VadResult? {
        if (!hasPermission(context)) return null
        // 启动采流（如果未启动）。
        val wasRunning = running.get()
        if (!wasRunning) start(context)
        val buf = ArrayList<Short>(16000) // 预分配 ~1s
        val frameSize = 1600 // 100ms@16k
        val readBuf = ShortArray(frameSize)
        val record = this.record ?: return null
        val startTime = System.currentTimeMillis()
        var speechStarted = false
        var onsetCount = 0
        var lastSpeechTime = 0L
        val preRoll = ArrayList<Short>() // 说话开始前的预滚缓冲

        try {
            while (running.get()) {
                val now = System.currentTimeMillis()
                // onset 超时。
                if (!speechStarted && now - startTime > onsetTimeoutMs) return null
                // 最长限制。
                if (now - startTime > maxDurationMs) break

                val n = record.read(readBuf, 0, frameSize)
                if (n <= 0) continue
                // 端侧降噪：与 [start] 广播路径一致，未注入/未就绪时直通。
                val den = denoiser
                val frame: ShortArray = if (den != null) {
                    val raw = if (n == frameSize) readBuf else readBuf.copyOf(n)
                    den.denoise(raw)
                } else {
                    if (n == frameSize) readBuf else readBuf.copyOf(n)
                }
                val level = rmsLevel(frame)

                // 同步 level 字段（与 start() 的采集线程同款 EMA 平滑），让阻塞采集路径
                // 也能被 UI 轮询拿到实时音量（向导声纹录入的分贝曲线背景依赖此值）。
                if (!externalLevel) {
                    this.level = this.level * 0.6f + level * 0.4f
                }

                if (!speechStarted) {
                    if (level >= speechThreshold) {
                        onsetCount++
                        if (onsetCount >= speechOnsetFrames) {
                            speechStarted = true
                            lastSpeechTime = now
                            // 合入预滚缓冲（保留 onset 前的声音）。
                            buf.addAll(preRoll)
                            buf.addAll(frame.take(n))
                        } else {
                            preRoll.addAll(frame.take(n))
                            if (preRoll.size > frameSize * speechOnsetFrames) {
                                preRoll.subList(0, preRoll.size - frameSize * speechOnsetFrames).clear()
                            }
                        }
                    } else {
                        onsetCount = 0
                        preRoll.clear()
                    }
                } else {
                    buf.addAll(frame.take(n))
                    if (level >= silenceThreshold) {
                        lastSpeechTime = now
                    } else if (now - lastSpeechTime > silenceTimeoutMs) {
                        // 静音超时 → 结束。
                        val pcm = ShortArray(buf.size) { buf[it] }
                        return VadResult(pcm, now - lastSpeechTime)
                    }
                }
            }
        } finally {
            if (!wasRunning) stop()
        }
        if (!speechStarted || buf.isEmpty()) return null
        val pcm = ShortArray(buf.size) { buf[it] }
        return VadResult(pcm, null)
    }
}
