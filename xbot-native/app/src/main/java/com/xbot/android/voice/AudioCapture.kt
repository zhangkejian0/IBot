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
 * - 格式：16kHz / mono / PCM16bit，启用 AGC/AEC/NS
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
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    // 音量（除非 TTS 接管）。
                    if (!externalLevel) {
                        val newLevel = rmsLevel(chunk)
                        level = level * 0.6f + newLevel * 0.4f
                    }
                    // 广播。
                    val snapshot: List<(ShortArray) -> Unit>
                    synchronized(listenersLock) { snapshot = chunkListeners.toList() }
                    for (l in snapshot) l(chunk)
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
}
