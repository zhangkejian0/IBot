package com.xbot.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧流式语音识别（sherpa-onnx OnlineRecognizer），仅用于右上角实时字幕显示。
 *
 * 与 [WakeWordService] 同构，是其流式 ASR 版本：
 * - 模型：sherpa-onnx-streaming-paraformer-bilingual-zh-en（中英双语，int8）
 * - OnlineRecognizer 通过文件系统绝对路径加载（AssetManager 传 null，规避 native
 *   读取绝对路径的 "Read binary file failed"，与 KWS 同一 workaround）
 * - numThreads=2, provider=cpu, decodingMethod=greedy_search, enableEndpoint=true
 * - 喂 Float32（÷32768）@16kHz，与 KWS / 降噪共用同一路 PCM
 *
 * 职责边界：本服务**只做字幕显示**（onPartial/onFinal 回调），不参与对话决策。
 * 云端 STT（[SttStreamClient]）继续负责喂给 LLM 的正式文本，两路并行、互不影响。
 *
 * 线程模型：[feedPcm16] 在 `xbot-audio-capture` 线程被调用（仅 acceptWaveform，非阻塞）；
 * 解码/取结果在本类专属守护线程 `xbot-asr-decode` 上完成。[reset] 不直接调用 native
 * （避免跨线程竞态），而是置位 [pendingReset]，由解码线程在下一轮迭代内安全执行。
 *
 * 抗噪门控（仅字幕侧激进过滤，宁可漏显不显噪声碎字）：
 * - 输入侧「自适应能量 + 近场门控」（[feedPcm16] 复用 [AudioGate]）：低能量的环境杂音与小
 *   音量旁人说话被挡在 ASR 之外；带 hangover 尾音保护，句尾自然静音仍会喂入以触发端点；
 *   门控完全关闭后请求 [reset]，避免噪声/上句文本残留粘连。
 * - 输出侧「稳定性 + 最短长度过滤」（解码线程）：partial 需前缀连续稳定 [STABILITY_HITS] 次
 *   且长度≥[MIN_PARTIAL_LEN] 才刷 UI，抑制噪声导致的跳字/回删闪现。
 *
 * @param modelDir assets 中的模型目录
 * @param onPartial 流式增量文本（每 ~150ms 回调一次，节流防刷屏）
 * @param onFinal 端点检出后的整句（[OnlineRecognizer.isEndpoint] 为真后回调一次并 reset）
 */
class StreamingAsrService(
    private val context: Context,
    private val modelDir: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) {
    companion object {
        private const val TAG = "StreamingAsrService"
        private const val SAMPLE_RATE = 16000
        /** partial 回调节流间隔（ms）：避免每个 chunk 都刷一次 UI。
         *  80ms 兼顾实时感与刷屏——比帧间隔（~100ms）略小，保证每帧有更新机会。 */
        private const val PARTIAL_THROTTLE_MS = 80L

        // ============ 输出稳定性过滤参数 ============
        /** partial 前缀需连续稳定的次数才刷 UI。1 次 = 首个单调增长帧即显示；
         *  startsWith 检查本身已抑制回删/跳字，无需二次确认以换取实时性。 */
        private const val STABILITY_HITS = 1
        /** partial 最短显示长度（字符）。 */
        private const val MIN_PARTIAL_LEN = 2

        private val FILES_TO_COPY = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokens.txt",
        )
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val running = AtomicBoolean(false)
    private var decodeThread: Thread? = null

    /** 请求在下一次解码迭代内重置（线程安全地清空累积文本，开启下一句）。 */
    @Volatile
    private var pendingReset = false
    /** 上次 partial 回调时间戳，用于节流。 */
    @Volatile
    private var lastPartialEmitMs = 0L

    // ---- 输入侧抗噪门控（仅在 xbot-audio-capture 线程调用，单写者无需同步）----
    /** 自适应能量 + 近场门控；门控关闭时请求 reset 清残留文本。 */
    private val gate = AudioGate()

    // ---- 输出稳定性状态（仅在 xbot-asr-decode 线程读写，无需同步）----
    private var stableText = ""
    private var stableHits = 0

    val isReady: Boolean get() = recognizer != null

    /**
     * 初始化：把模型从 assets 复制到 cacheDir，再用无 AssetManager 的构造函数加载。
     * 原因同 [WakeWordService]：sherpa-onnx native 层用文件系统绝对路径加载模型最稳，
     * AssetManager 构造函数对绝对路径会触发 fatal。
     */
    fun initialize() {
        try {
            // 1. 把模型从 assets 复制到 cacheDir（已存在则跳过）。
            val destDir = File(context.cacheDir, "xbot_asr_model").apply { mkdirs() }
            for (name in FILES_TO_COPY) {
                val dest = File(destDir, name)
                if (!dest.exists()) {
                    context.assets.open("$modelDir/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            // 2. 用绝对路径构造 config，用无 AssetManager 构造函数。
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
                enableEndpoint = true
                // 端点检测三规则（与 sherpa-onnx 默认 getEndpointConfig() 一致）：
                // rule1 启动静音、rule2 说完+尾随静音达 1.2s、rule3 单句最长 20s。
                endpointConfig = EndpointConfig(
                    EndpointRule(false, 2.0f, 0.0f),
                    EndpointRule(true, 1.2f, 0.0f),
                    EndpointRule(true, 0.0f, 20.0f),
                )
                decodingMethod = "greedy_search"
                maxActivePaths = 4
            }
            // OnlineRecognizer(AssetManager, config)：AssetManager 传 null（文件从 SD 卡绝对路径加载）。
            recognizer = OnlineRecognizer(null, cfg)
            Log.i(TAG, "流式 ASR 已加载（streaming-paraformer-bilingual-zh-en int8）")
        } catch (e: Exception) {
            Log.e(TAG, "流式 ASR 初始化失败（字幕降级关闭）: ${e.message}")
            recognizer = null
        }
    }

    /** 开始解码：在独立线程循环 isReady→decode→getResult。音频由 [feedPcm16] 喂入。 */
    fun start() {
        val r = recognizer ?: return
        if (running.get()) return
        stream = r.createStream("")
        running.set(true)
        pendingReset = false
        lastPartialEmitMs = 0L
        // 复位门控与稳定性状态，避免跨会话残留。
        gate.reset()
        resetStability()
        decodeThread = Thread({
            while (running.get()) {
                val st = stream ?: break
                val rc = recognizer ?: break
                try {
                    // 线程安全地清空累积文本（由外部 reset() 请求触发）。
                    if (pendingReset) {
                        pendingReset = false
                        rc.reset(st)
                        resetStability()
                    }
                    if (rc.isReady(st)) {
                        rc.decode(st)
                        val text = rc.getResult(st).text
                        // 端点检出（一段话说完）→ 回调 final 并 reset 开启下一句。
                        if (rc.isEndpoint(st)) {
                            if (text.isNotEmpty()) onFinal(text)
                            rc.reset(st)
                            resetStability()
                        } else if (text.isNotEmpty()) {
                            // partial 节流 + 稳定性过滤：避免每个 chunk 都刷 UI，
                            // 且噪声导致的跳字/回删（前缀不连续）不显示。
                            val now = System.currentTimeMillis()
                            if (now - lastPartialEmitMs >= PARTIAL_THROTTLE_MS) {
                                lastPartialEmitMs = now
                                emitPartialIfStable(text)
                            }
                        }
                    } else {
                        // 无新数据可解码时让出 CPU。
                        Thread.sleep(10)
                    }
                } catch (_: Exception) {
                    // 单次解码异常不中断。
                }
            }
        }, "xbot-asr-decode").apply { isDaemon = true; start() }
    }

    /**
     * 喂入一段 PCM16（short）→ 自适应能量/近场门控（[gate]）→ Float32 归一化 → 送入 stream。
     * 门控关闭时不喂入，并在从「开→关」的瞬间请求 [reset] 清除累积文本，防止残留粘连下一句。
     */
    fun feedPcm16(samples: ShortArray) {
        val st = stream ?: return
        if (!gate.accept(samples, onClose = { reset() })) return
        val f = FloatArray(samples.size)
        for (i in samples.indices) f[i] = samples[i] / 32768f
        st.acceptWaveform(f, SAMPLE_RATE)
    }

    /**
     * partial 稳定性过滤：仅当文本前缀连续稳定 [STABILITY_HITS] 次且长度≥[MIN_PARTIAL_LEN]
     * 才回调，抑制噪声导致的跳字/回删闪现。仅在解码线程调用。
     */
    private fun emitPartialIfStable(text: String) {
        // 前缀单调增长视为稳定；出现回删/前缀变化则重新计数。
        if (text.startsWith(stableText) && text.length >= stableText.length) {
            stableHits++
        } else {
            stableHits = 0
        }
        stableText = text
        if (stableHits >= STABILITY_HITS && text.length >= MIN_PARTIAL_LEN) {
            onPartial(text)
        }
    }

    /** 清空输出稳定性状态。仅在解码线程调用。 */
    private fun resetStability() {
        stableText = ""
        stableHits = 0
    }

    /**
     * 请求清空当前累积的识别文本（开启下一句）。线程安全：仅置标志，由解码线程执行。
     * 用于云端 STT 进入新聆听窗口 / 检出 final 时，让端侧字幕与之对齐。
     */
    fun reset() {
        pendingReset = true
    }

    fun stop() {
        running.set(false)
        try { decodeThread?.join(200) } catch (_: Exception) {}
        decodeThread = null
        stream?.release()
        stream = null
    }

    fun release() {
        stop()
        recognizer?.release()
        recognizer = null
    }
}
