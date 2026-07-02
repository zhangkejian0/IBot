package com.xbot.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File

/**
 * 端侧整句语音识别（sherpa-onnx OnlineRecognizer 同步用法）。
 *
 * 复用与 [StreamingAsrService] 相同的流式 paraformer 模型
 * （sherpa-onnx-streaming-paraformer-bilingual-zh-en），但采用「喂完整段 PCM →
 * [OnlineStream.inputFinished] → decode 到底 → getResult」的同步整句范式，
 * 而非流式回调。适用于向导声纹录入这种「录完一句再识别」的场景。
 *
 * 与 [StreamingAsrService] 的区别：
 * - 无独立解码线程、无 onPartial/onFinal 回调；[recognize] 是同步阻塞方法。
 * - 不开端点检测（整句同步模式不需要端点）。
 * - 与字幕 ASR 共用同一份磁盘模型（cacheDir/xbot_asr_model/，文件名相同幂等复制），
 *   但持有独立的 [OnlineRecognizer] 实例（独立 native 指针）。
 *
 * 职责边界：仅做「PCM → 文字」的整句识别，不参与对话决策，不存任何状态。
 *
 * @param modelDir assets 中的模型目录
 */
class OfflineAsrRecognizer(
    private val context: Context,
    private val modelDir: String,
) {
    companion object {
        private const val TAG = "OfflineAsrRecognizer"
        private const val SAMPLE_RATE = 16000

        private val FILES_TO_COPY = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokens.txt",
        )
    }

    private var recognizer: OnlineRecognizer? = null

    /** 模型是否就绪可识别。 */
    val isReady: Boolean get() = recognizer != null

    /**
     * 初始化：模型从 assets 复制到 cacheDir，再用绝对路径构造 [OnlineRecognizer]。
     * 与 [StreamingAsrService.initialize] 同构（AssetManager 传 null，规避 native
     * 读取绝对路径的 "Read binary file failed"）。需在后台线程调用。
     */
    fun initialize() {
        try {
            val destDir = File(context.cacheDir, "xbot_asr_model").apply { mkdirs() }
            for (name in FILES_TO_COPY) {
                val dest = File(destDir, name)
                if (!dest.exists()) {
                    context.assets.open("$modelDir/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            val paraformerCfg = OnlineParaformerModelConfig().apply {
                encoder = File(destDir, "encoder.int8.onnx").absolutePath
                decoder = File(destDir, "decoder.int8.onnx").absolutePath
            }
            val modelCfg = OnlineModelConfig().apply {
                paraformer = paraformerCfg
                tokens = File(destDir, "tokens.txt").absolutePath
                numThreads = 2
                debug = false
                provider = "cpu"
                modelType = ""
                modelingUnit = ""
            }
            val cfg = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f)
                modelConfig = modelCfg
                enableEndpoint = false  // 整句同步模式不需要端点检测
                decodingMethod = "greedy_search"
                maxActivePaths = 4
            }
            recognizer = OnlineRecognizer(null, cfg)
            Log.i(TAG, "整句 ASR 已加载（streaming-paraformer-bilingual-zh-en int8）")
        } catch (e: Exception) {
            Log.e(TAG, "整句 ASR 初始化失败: ${e.message}")
            recognizer = null
        }
    }

    /**
     * 同步识别一段完整 PCM16 → 返回整句文字。
     *
     * 范式：createStream → 整段 acceptWaveform → inputFinished（通知流式引擎输入结束，
     * 触发最终解码）→ 循环 decode 到底 → getResult。这是流式 paraformer 的标准同步用法。
     *
     * **阻塞方法**，需在后台线程调用（向导在协程 Dispatchers.IO 内调用）。
     *
     * @param pcm16 16kHz/mono/PCM16 语音
     * @return 识别文字（可能为空串）；模型未就绪/异常返回 null
     */
    fun recognize(pcm16: ShortArray): String? {
        val rc = recognizer ?: return null
        var stream: OnlineStream? = null
        return try {
            stream = rc.createStream("")
            // PCM16 → Float32 归一化（÷32768），与 StreamingAsrService.feedPcm16 一致。
            val f = FloatArray(pcm16.size)
            for (i in pcm16.indices) f[i] = pcm16[i] / 32768f
            stream.acceptWaveform(f, SAMPLE_RATE)
            stream.inputFinished()  // 关键：标记输入结束，触发最终整句解码
            while (rc.isReady(stream)) rc.decode(stream)
            rc.getResult(stream).text
        } catch (e: Exception) {
            Log.w(TAG, "识别失败: ${e.message}")
            null
        } finally {
            try { stream?.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
