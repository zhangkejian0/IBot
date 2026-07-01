package com.xbot.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque

/**
 * 流式 TTS 播放器：用 AudioTrack 喂 PCM16 chunk，首个 chunk 到达即开口。
 * 对应 Flutter streaming_tts_player.dart（flutter_pcm_sound 的原生等价）。
 *
 * - PCM16 / mono / streaming mode，采样率由 meta 给（默认 22050）
 * - 首 chunk 即播放（首音延迟 ≈ LLM done + DashScope 首包）
 * - 喂 chunk 时同步写 [onLevel]（嘴部同步，RMS/6000）
 * - 等队列耗尽 + AudioTrack 自然播完（drain）再 release，避免截断
 *
 * @param onLevel 实时音量 0..1（驱动虚拟形象嘴部）；TTS 期间接管 AudioCapture.level
 */
class StreamingTtsPlayer(
    private val onLevel: (Float) -> Unit,
) {
    companion object {
        private const val TAG = "StreamingTtsPlayer"
        private const val RMS_DENOM = 6000f
    }

    private var track: AudioTrack? = null
    private var sampleRate = 22050
    private val queue = LinkedBlockingDeque<ShortArray>()
    @Volatile private var feedingDone = false
    @Volatile private var active = false
    private var feedThread: Thread? = null
    @Volatile var totalSamplesFed = 0
        private set
    @Volatile var drained = false
        private set

    /** 开始：初始化 AudioTrack（采样率由 meta 决定，首次 feedChunk 前调 start）。 */
    fun start(sr: Int = 22050) {
        if (active) return
        sampleRate = sr
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 5) // 至少 ~200ms 缓冲
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track!!.play()
        active = true
        feedingDone = false
        drained = false
        totalSamplesFed = 0
        feedThread = Thread({ runFeedLoop() }, "xbot-tts-feed").apply { isDaemon = true; start() }
    }

    /** 喂一个 PCM16 chunk（little-endian bytes → short[]）。立即写入队列触发播放。 */
    fun feedChunk(pcm16: ByteArray) {
        if (!active) return
        val samples = bytesToShorts(pcm16)
        queue.add(samples)
    }

    /** 标记喂入完成（所有 chunk 已喂完）；等播放耗尽后触发 drain。 */
    fun markFeedingDone() {
        feedingDone = true
    }

    private fun runFeedLoop() {
        try {
            while (active) {
                val chunk = queue.poll() ?: run {
                    if (feedingDone) break
                    Thread.sleep(5); continue
                }
                totalSamplesFed += chunk.size
                // 嘴部同步：写音量。
                onLevel(rmsLevel(chunk))
                track?.write(chunk, 0, chunk.size)
            }
            // 等 AudioTrack 把硬件缓冲播完（drain）。
            track?.stop()
            track?.flush()
            drained = true
            onLevel(0f)
        } catch (e: Exception) {
            Log.e(TAG, "feed 异常: ${e.message}")
        }
    }

    /** 释放：停止播放 + 清队列 + 释放 AudioTrack。 */
    fun release() {
        active = false
        queue.clear()
        try { feedThread?.join(300) } catch (_: Exception) {}
        feedThread = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        onLevel(0f)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    private fun rmsLevel(samples: ShortArray): Float {
        var sumSq = 0.0
        for (s in samples) { val v = s.toDouble(); sumSq += v * v }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        return (rms / RMS_DENOM).toFloat().coerceIn(0f, 1f)
    }
}
