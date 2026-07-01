package com.xbot.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.xbot.android.model.Person
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧声纹/说话人识别服务（sherpa-onnx 3D-Speaker CAM++）。
 *
 * 把一段 PCM16 语音转换为定长声纹向量（embedding），与已录入人物比对得到「是谁在说话」。
 * 与 [com.xbot.android.vision.FaceRecognizer]（人脸）模态独立、范式对称：
 * - 人脸：图像 → MobileFaceNet → 192 维向量，余弦阈值 0.62
 * - 声纹：语音 → CAM++ → 192 维向量，余弦阈值 0.6
 *
 * 模型已 L2 归一化，点积即余弦（与 [FaceRecognizer] 的 cosine 逻辑一致）。
 *
 * - 模型：3D-Speaker CAM++ zh-cn 16k（~7MB），[assets]/[modelDir]/speaker.onnx
 * - 输入：PCM16（short，16kHz mono），**需至少约 1s 语音**否则 isReady 返回 false
 * - 输出：embedding（192 维，已 L2 归一化）
 *
 * 降级策略：模型缺失/加载失败 → [isReady]=false，[embed] 返回 null，不阻断功能。
 *
 * @param modelDir assets 中的模型目录（如 "voice/sherpa-onnx-3dspeaker-campplus-zh-cn-16k-common"），
 *                 其下应有 speaker.onnx
 * @param matchThreshold 识别命中阈值（余弦相似度）。家用单麦克风默认 0.6，误识/漏识可调。
 */
class VoiceRecognizer(
    private val context: Context,
    private val modelDir: String = "voice/sherpa-onnx-3dspeaker-campplus-zh-cn-16k-common",
    /** 识别命中阈值（余弦相似度，-1..1）。sherpa CAM++ 经验值约 0.6。 */
    var matchThreshold: Float = MATCH_THRESHOLD,
) {
    companion object {
        private const val TAG = "VoiceRecognizer"
        private const val MODEL_FILE = "speaker.onnx"
        private const val SAMPLE_RATE = 16000
        /** 喂入模型的最小采样数（约 1s）。短于此 isReady 返回 false，无法提嵌入。 */
        private const val MIN_SAMPLES = SAMPLE_RATE
        /** 默认识别阈值。家用单麦克风 + CAM++ 经验值。误识多调高，漏识多调低。 */
        const val MATCH_THRESHOLD = 0.6f
    }

    private var extractor: SpeakerEmbeddingExtractor? = null
    private val ready = AtomicBoolean(false)

    /** 声纹向量维度（模型加载后才有意义，CAM++ 为 192）。 */
    var embeddingDim = 192
        private set

    /** 提取器是否就绪（模型加载成功）。false 时 [embed] 返回 null。 */
    val isReady: Boolean get() = ready.get()

    /** 初始化：加载 CAM++ 模型。失败置 ready=false（降级），不抛异常。 */
    fun initialize() {
        try {
            // model 是 val（构造必传），其余可变字段用 apply 设置。
            val cfg = SpeakerEmbeddingExtractorConfig(model = "$modelDir/$MODEL_FILE").apply {
                numThreads = 1
                debug = false
                provider = "cpu"
            }
            // SpeakerEmbeddingExtractor(AssetManager, config)：模型从 assets 加载。
            // native 层通过 AssetManager 读取 .onnx，model 路径相对 assets 根（与 SpeechDenoiser 一致）。
            val ex = SpeakerEmbeddingExtractor(context.assets, cfg)
            embeddingDim = ex.dim()
            extractor = ex
            ready.set(true)
            Log.i(TAG, "3D-Speaker CAM++ 已加载（embedding 维度 $embeddingDim）")
        } catch (e: Exception) {
            // 模型缺失或 native 加载失败 → 降级，不影响语音主流程。
            Log.e(TAG, "声纹模型初始化失败，降级停用: ${e.message}")
            extractor = null
            ready.set(false)
        }
    }

    /**
     * 把一段 PCM16 语音转为声纹向量。
     *
     * @param pcm16 输入 PCM16（short，16kHz mono，**建议 ≥1s**）
     * @return L2 归一化后的特征向量；未就绪/语音过短/异常时返回 null
     */
    fun embed(pcm16: ShortArray): List<Float>? {
        val ex = extractor ?: return null
        if (!ready.get() || pcm16.size < MIN_SAMPLES) return null
        var stream: OnlineStream? = null
        return try {
            stream = ex.createStream()
            // ShortArray → Float32 归一化（÷32768），与 WakeWordService.feedPcm16 一致。
            val f = FloatArray(pcm16.size)
            for (i in pcm16.indices) f[i] = pcm16[i] / 32768f
            stream.acceptWaveform(f, SAMPLE_RATE)
            stream.inputFinished()
            // isReady=false 说明喂入语音过短，模型尚未积累足够上下文。
            if (!ex.isReady(stream)) null else l2Normalize(ex.compute(stream))
        } catch (e: Exception) {
            Log.w(TAG, "声纹 embed 异常: ${e.message}")
            null
        } finally {
            try { stream?.release() } catch (_: Exception) {}
        }
    }

    /**
     * L2 归一化：CAM++ 原始输出**未归一化**（向量模长远大于 1），
     * 直接用点积当余弦会得到远超 1 的值（实测可达 200+）。
     * 归一化后点积即余弦，落在 [-1,1]，阈值才有意义。
     */
    private fun l2Normalize(v: FloatArray): List<Float> {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val norm = kotlin.math.sqrt(sum)
        if (norm == 0.0) return v.toList()
        return List(v.size) { (v[it] / norm).toFloat() }
    }

    /**
     * 在已录入人物中寻找最相似的说话人；不足阈值返回 null。
     * 比对逻辑与 [com.xbot.android.vision.FaceRecognizer.identify] 对称：
     * 遍历每个 [Person.voiceEmbeddings] 的每个样本，取所有人所有样本的最高余弦。
     */
    fun identify(embedding: List<Float>, people: List<Person>): Person? {
        val m = identifyWithScore(embedding, people)
        return if (m.score >= matchThreshold) m.person else null
    }

    /**
     * 比对并返回最相似者及其分数（不套阈值）。
     * 供测试面板展示分数、由调用方按阈值判定匹配。
     *
     * @return [VoiceMatch]；人物库无人有声纹或嵌入无效时 person=null、score=-1。
     */
    fun identifyWithScore(embedding: List<Float>, people: List<Person>): VoiceMatch {
        var best: Person? = null
        var bestSim = -1f
        for (person in people) {
            for (sample in person.voiceEmbeddings) {
                val sim = cosine(embedding, sample)
                if (sim > bestSim) {
                    bestSim = sim
                    best = person
                }
            }
        }
        return VoiceMatch(best, bestSim)
    }

    /** 声纹比对结果（[identifyWithScore] 返回）。score 为最高余弦相似度，person 为对应人（可能 null）。 */
    data class VoiceMatch(val person: Person?, val score: Float)

    /** 余弦相似度 = a·b / (|a||b|)。嵌入已在 [embed] 做 L2 归一化（模长=1），
     *  此处仍保留除法以双保险，防止换用未归一化模型时点积越界。 */
    private fun cosine(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            na += (a[i] * a[i]).toDouble()
            nb += (b[i] * b[i]).toDouble()
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        if (denom == 0.0) return -1f
        return (dot / denom).toFloat()
    }

    fun release() {
        ready.set(false)
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }
}
