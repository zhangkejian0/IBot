package com.xbot.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 唤醒词服务（sherpa-onnx KWS，开放词表）。对应 Flutter wake_word_service.dart。
 *
 * - 模型：sherpa-onnx-kws-zipformer-wenetspeech-3.3M（int8 encoder/decoder/joiner + tokens.txt）
 * - KeywordSpotter 通过 AssetManager 构造，模型路径相对 assets 根
 * - numThreads=2, provider=cpu, modelingUnit=cjkchar, modelType 必须空
 * - keywordsThreshold=0.35, keywordsScore=1.0
 * - 关键词文本 → 拼音（带声调）→ 拆声母韵母 → "toks @keyword"，写入临时文件作为 keywordsFile
 * - 喂 Float32（÷32768）@16kHz
 *
 * @param modelDir assets 中的模型目录（如 "voice/sherpa-onnx-kws-.../"）
 * @param keyword 唤醒词（默认 "你好小白"）
 */
class WakeWordService(
    private val context: Context,
    private val modelDir: String,
    private var keyword: String = "你好小白",
    private val onWake: (keyword: String) -> Unit,
) {
    companion object {
        private const val TAG = "WakeWordService"
        private const val SAMPLE_RATE = 16000
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private val running = AtomicBoolean(false)
    private var detectThread: Thread? = null

    val currentKeyword: String get() = keyword
    val isReady: Boolean get() = spotter != null

    /** 初始化：把模型从 assets 复制到 cacheDir，再用无 AssetManager 的构造函数加载。
     *  原因：sherpa-onnx 的 AssetManager 构造函数无法处理 keywordsFile 的绝对路径，
     *  会触发 native fatal（"Read binary file failed"）。故所有文件都用绝对路径。 */
    fun initialize() {
        try {
            val keywordLine = Pinyin.buildKeywordLine(keyword) ?: run {
                Log.e(TAG, "拼音字典未覆盖「$keyword」，唤醒词初始化失败")
                return
            }
            // 1. 把模型从 assets 复制到 cacheDir（仅 int8 + tokens）。
            val destDir = File(context.cacheDir, "xbot_kws_model").apply { mkdirs() }
            val filesToCopy = listOf(
                "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "tokens.txt",
            )
            for (name in filesToCopy) {
                val dest = File(destDir, name)
                if (!dest.exists()) {
                    context.assets.open("$modelDir/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            // 2. 写关键词文件。
            val kwFile = File(context.cacheDir, "xbot_keywords.txt")
            kwFile.writeText(keywordLine)

            // 3. 用绝对路径构造 config，用无 AssetManager 构造函数。
            val transducerCfg = OnlineTransducerModelConfig().apply {
                encoder = File(destDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
                decoder = File(destDir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
                joiner = File(destDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
            }
            val modelCfg = OnlineModelConfig().apply {
                transducer = transducerCfg
                tokens = File(destDir, "tokens.txt").absolutePath
                numThreads = 2
                debug = false
                provider = "cpu"
                modelType = ""
                modelingUnit = "cjkchar"
            }
            val cfg = KeywordSpotterConfig().apply {
                featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f)
                modelConfig = modelCfg
                maxActivePaths = 4
                keywordsFile = kwFile.absolutePath
                keywordsThreshold = 0.35f
                keywordsScore = 1.0f
                numTrailingBlanks = 1
            }
            // KeywordSpotter(AssetManager, config)：AssetManager 传 null（文件从 SD 卡绝对路径加载）。
            // sherpa native 层检查到 null 时走文件系统路径分支。
            spotter = KeywordSpotter(null, cfg)
            Log.i(TAG, "唤醒词已加载（$keyword → $keywordLine）")
        } catch (e: Exception) {
            Log.e(TAG, "唤醒词初始化失败: ${e.message}")
            spotter = null
        }
    }

    /** 开始监听：在独立线程循环解码。音频由 [feedPcm16] 喂入。 */
    fun start() {
        val s = spotter ?: return
        if (running.get()) return
        stream = s.createStream("")
        running.set(true)
        detectThread = Thread({
            while (running.get()) {
                val st = stream ?: break
                try {
                    if (s.isReady(st)) {
                        s.decode(st)
                        val result = s.getResult(st)
                        val kw = result.keyword
                        if (!kw.isNullOrEmpty()) {
                            s.reset(st)
                            onWake(kw)
                        }
                    } else {
                        Thread.sleep(10)
                    }
                } catch (_: Exception) {
                    // 单次解码异常不中断。
                }
            }
        }, "xbot-wake-detect").apply { isDaemon = true; start() }
    }

    /** 喂入一段 PCM16（short）→ Float32 归一化 → 送入 stream。 */
    fun feedPcm16(samples: ShortArray) {
        val st = stream ?: return
        val f = FloatArray(samples.size)
        for (i in samples.indices) f[i] = samples[i] / 32768f
        st.acceptWaveform(f, SAMPLE_RATE)
    }

    fun stop() {
        running.set(false)
        try { detectThread?.join(200) } catch (_: Exception) {}
        detectThread = null
        stream?.release()
        stream = null
    }

    /** 运行时改关键词（需重新 initialize + start）。 */
    fun setKeyword(newKeyword: String) {
        if (newKeyword == keyword) return
        keyword = newKeyword
        stop()
        spotter?.release()
        spotter = null
        initialize()
    }

    fun release() {
        stop()
        spotter?.release()
        spotter = null
    }
}
