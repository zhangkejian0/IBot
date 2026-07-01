package com.xbot.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧语音降噪服务（sherpa-onnx GTCRN 流式降噪）。
 *
 * 在 [AudioCapture] 采集线程广播 chunk 前做一道神经网络降噪，去除稳态背景噪声
 * （空调/风扇/环境嘈杂），让唤醒词 KWS / 流式 STT / 音量 dB 都拿到更干净的人声。
 *
 * - 模型：GTCRN（48.2K 参数，33 MMACs/s），16kHz mono，[assets]/[modelDir]/gtcrn_simple.onnx
 * - 用 [OnlineSpeechDenoiser]（流式接口）：每次 [denoise] 喂入一段 PCM16，内部按 frameShift
 *   （160 采样/10ms）分帧处理，返回等长降噪后 PCM16
 * - 线程安全：[denoise] 由采集线程单线程调用（[AudioCapture] 的 xbot-audio-capture 线程）
 *
 * 降级策略：模型缺失/加载失败 → [isReady]=false，[denoise] 原样返回输入（直通），不阻断功能。
 *
 * @param modelDir assets 中的模型目录（如 "voice/gtcrn_simple"），其下应有 gtcrn_simple.onnx
 */
class SpeechDenoiser(
    private val context: Context,
    private val modelDir: String = "voice/gtcrn_simple",
) {
    companion object {
        private const val TAG = "SpeechDenoiser"
        private const val MODEL_FILE = "gtcrn_simple.onnx"
        private const val SAMPLE_RATE = 16000
    }

    private var denoiser: OnlineSpeechDenoiser? = null
    private val ready = AtomicBoolean(false)

    /** 降噪器是否就绪（模型加载成功）。false 时 [denoise] 直通。 */
    val isReady: Boolean get() = ready.get()

    /** 初始化：加载 GTCRN 模型。失败置 ready=false（直通降级），不抛异常。 */
    fun initialize() {
        try {
            val gtcrnCfg = OfflineSpeechDenoiserGtcrnModelConfig().apply {
                model = "$modelDir/$MODEL_FILE"
            }
            val modelCfg = OfflineSpeechDenoiserModelConfig().apply {
                gtcrn = gtcrnCfg
                numThreads = 1
                debug = false
                provider = "cpu"
            }
            val cfg = OnlineSpeechDenoiserConfig().apply { this.model = modelCfg }
            // OnlineSpeechDenoiser(AssetManager, config)：模型从 assets 加载。
            // native 层通过 AssetManager 读取 noCompress 的 .onnx，model 路径相对 assets 根。
            denoiser = OnlineSpeechDenoiser(context.assets, cfg).also {
                // 模型期望采样率应与采集一致（16kHz），否则降级避免错频。
                if (it.sampleRate != SAMPLE_RATE) {
                    Log.e(TAG, "模型采样率=${it.sampleRate} ≠ $SAMPLE_RATE，降级直通")
                    try { it.release() } catch (_: Exception) {}
                    denoiser = null
                    return
                }
            }
            ready.set(true)
            Log.i(TAG, "GTCRN 降噪已加载（frameShift=${denoiser?.frameShiftInSamples} samples）")
        } catch (e: Exception) {
            // 模型缺失或 native 加载失败 → 直通降级，不影响语音主流程。
            Log.e(TAG, "GTCRN 降噪初始化失败，降级直通: ${e.message}")
            denoiser = null
            ready.set(false)
        }
    }

    /**
     * 对一段 PCM16 降噪。**必须由采集线程单线程调用**。
     *
     * @param pcm16 输入 PCM16（short，16kHz mono）
     * @return 降噪后 PCM16（等长）；未就绪或单帧异常时原样返回输入（直通）
     */
    fun denoise(pcm16: ShortArray): ShortArray {
        val d = denoiser ?: return pcm16
        if (!ready.get() || pcm16.isEmpty()) return pcm16
        return try {
            // ShortArray → Float32 归一化（÷32768）。
            val input = FloatArray(pcm16.size)
            for (i in pcm16.indices) input[i] = pcm16[i] / 32768f
            // 流式推理：内部按 frameShift 分帧，有状态。
            val out = d.run(input, SAMPLE_RATE)
            val samples = out.samples
            // Float32 → Short16 反归一化（×32768），钳到 short 范围防溢出。
            val result = ShortArray(samples.size)
            for (i in samples.indices) {
                result[i] = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
            result
        } catch (e: Exception) {
            // 单帧推理异常不中断采集线程，该帧直通。
            Log.w(TAG, "单帧降噪异常，直通: ${e.message}")
            pcm16
        }
    }

    fun release() {
        ready.set(false)
        try { denoiser?.release() } catch (_: Exception) {}
        denoiser = null
    }
}
