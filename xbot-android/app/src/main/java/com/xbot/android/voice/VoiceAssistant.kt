package com.xbot.android.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 语音助手协调器：状态机驱动 唤醒 → 流式 STT → Pophie 对话 → 流式 TTS。
 * 对应 Flutter voice_assistant.dart 的主流程（流式模式）。
 *
 * 默认半双工：TTS 期间不向 STT 上行 chunk（state != listening），物理上无回声路径。
 *
 * 多轮复用：一次唤醒/双击建立一条 WS，识别到一句（final）即处理一轮对话，播完
 * 回到聆听继续等待下一句；只有服务端 session_end / error / WS 关闭 / 总开关 stop
 * 才结束会话回到 idle。
 *
 * @param onStateChange 状态变化回调（驱动虚拟形象 setState + 嘴部）
 * @param onLevel 音量变化（listening 用麦音量，speaking 用 TTS 音量）
 * @param perceptionProvider 每轮对话上传的感知上下文（表情/身份/手势/物体/场景）
 * @param conversationLog 交互日志收集器（可空；设置页「交互日志」查看）
 * @param voiceLog 语音识别记录收集器（可空；设置页「语音记录」查看）——每句 final 记录文字+说话人
 * @param config Pophie 后端配置（地址/robotId/音色；可由设置注入）
 */
class VoiceAssistant(
    private val context: Context,
    private val onStateChange: (VoiceState, String?) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val perceptionProvider: () -> JSONObject? = { null },
    val conversationLog: ConversationLogger? = null,
    val voiceLog: VoiceLogStore? = null,
    config: PophieConfig = PophieConfig(),
    /** 端侧流式 ASR 的实时识别文本（驱动字幕浮层）。仅显示，不影响对话。 */
    private val onPartialText: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceAssistant"
        /** TTS 结束后抑制麦克上行/字幕/唤醒的时长(ms)，覆盖扬声器物理余音 + 房间短混响。
         *  过短 → 回声泄漏被识别成用户语音（自激循环）；过长 → 接接略迟。默认 350ms。 */
        private const val ECHO_SUPPRESSION_MS = 350L
        /** 声纹识别 PCM 环形缓冲容量（采样数）。约 2s@16k，足够 CAM++ 提嵌入。 */
        private const val SPEAKER_BUF_SAMPLES = 32000
        /** 常驻声纹 PCM 环形缓冲容量（采样数）。约 2s@16k，供 [onAsrFinal] 提嵌入。 */
        private const val AMBIENT_BUF_SAMPLES = 32000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = AudioCapture()
    private lateinit var wakeWord: WakeWordService
    /** 端侧降噪器（GTCRN）。由 [start] 异步初始化并注入 [audio]；失败降级直通。 */
    private var denoiser: SpeechDenoiser? = null
    /** 端侧流式 ASR（仅字幕显示）。由 [start] 异步初始化；失败降级为不显示字幕。 */
    private var asr: StreamingAsrService? = null
    val pophie = PophieClient(config)
    private val tts = StreamingTtsPlayer(onLevel = onLevel)

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 后端回传的 robotState（精确 FSM 态，覆盖本地映射）。 */
    @Volatile var robotState: String? = null
        private set

    @Volatile var isRunning = false
        private set
    @Volatile var isAvailable = false
        private set
    @Volatile var wakeWordEnabled = true
    @Volatile var ttsEnabled = true
    @Volatile var streamingSttEnabled = true
    /** 端侧实时字幕开关（默认开）。关闭后不再喂流给 [asr]，字幕不显示。 */
    @Volatile var onDeviceSubtitleEnabled = true
    /** 声纹识别开关（默认开）。关闭后不提嵌入、不上传说话人身份。 */
    @Volatile var voiceIdentityEnabled = true

    /** 声纹识别器（由外部注入；为 null 时不启用说话人识别）。 */
    @Volatile var voiceRecognizer: VoiceRecognizer? = null
    /** 人物库提供者（用于声纹比对）。返回全部已录入人物。 */
    @Volatile var peopleProvider: () -> List<com.xbot.android.model.Person> = { emptyList() }

    /**
     * 当前说话人姓名（声纹识别结果）。每轮 final 时刷新，供 perceptionProvider 读取。
     * - 命中已录入主人 → 主人昵称
     * - 未命中/未启用/模型未就绪 → null（不上传身份）
     */
    @Volatile var currentSpeaker: String? = null
        private set

    /** 唤醒词是否就绪（模型加载成功）。 */
    val wakeWordReady: Boolean get() = ::wakeWord.isInitialized && wakeWord.isReady

    /**
     * 当前实时音量 0..1：聆听相位为麦克风 RMS（驱动聆听跑马灯呼吸），
     * 播报相位由 TTS 通过 onLevel 单独驱动。供 UI 轮询读取（线程安全）。
     */
    val micLevel: Float get() = audio.level

    private var conversationJob: Job? = null
    @Volatile private var activeStt: SttStreamClient? = null
    private var lastPartialText: String = ""
    @Volatile private var sawPartialSinceListen = false
    @Volatile private var lastFinalText: String = ""

    // —— 常驻语音记录（设置页「语音记录」）：只要识别到整句就记录，无需唤醒对话 ——
    /** 常驻声纹 PCM 环形缓冲：持续累积麦克风 PCM，[onAsrFinal] 检出整句时 snapshot 提嵌入。
     *  单写者（ambientFeedListener 在 xbot-audio-capture 线程），无需同步。 */
    private val ambientSpeakerBuf = ArrayDeque<Short>()
    /** 常驻记录去重：上一次记录的文字，避免端侧 ASR 把同一句切成多段重复记录。 */
    @Volatile private var lastLoggedText: String = ""

    /** 用户主动中止当前对话轮（由 stopConversation 置位，runStreamingConversation 据此干净退出）。 */
    @Volatile private var abortConversation = false

    /** TTS 回声冷却期截止时间戳：此时间之前抑制所有麦克消费路径（上行/字幕/唤醒喂流），
     *  避免扬声器物理余音 + 房间混响被麦克风拾取后误识别成用户语音（自激循环）。
     *  由 [applyEchoSuppression] 在每次 TTS 播放结束时刷新。 */
    @Volatile private var suppressMicUntilMs: Long = 0L

    /** 是否处于 TTS 回声冷却期内。在所有麦克喂流监听器里调用，单读无锁。 */
    private fun micSuppressed(): Boolean = System.currentTimeMillis() < suppressMicUntilMs

    /** 设置冷却期：当前时间 + [ECHO_SUPPRESSION_MS]。TTS 播放结束（含主动消息）时调用。 */
    private fun applyEchoSuppression() {
        suppressMicUntilMs = System.currentTimeMillis() + ECHO_SUPPRESSION_MS
    }

    /**
     * 异步声纹识别：snapshot 当前聆听窗口的 PCM 缓冲，提嵌入并与人物库比对。
     * 在 STT WS 回调线程调用，提嵌入/native 推理丢到协程，不阻塞对话主流程。
     * 结果写入 [currentSpeaker]（命中主人昵称 / null）。未启用/模型未就绪/过短 → null。
     *
     * 仅负责对话轮的说话人身份（供 perceptionProvider 读取）；语音记录由常驻 ASR
     * 的 final 回调（[onAsrFinal]）统一记录，这里不再重复写日志。
     */
    private fun identifySpeakerAsync(buf: ArrayDeque<Short>) {
        val recognizer = voiceRecognizer
        if (!voiceIdentityEnabled || recognizer == null || !recognizer.isReady) {
            currentSpeaker = null
            return
        }
        // snapshot：拷贝当前缓冲内容（后续 chunk 会继续追加，且 WS 回调与协程异步）。
        val pcm = ShortArray(buf.size) { buf[it] }
        scope.launch {
            val emb = recognizer.embed(pcm)
            val name = if (emb != null) recognizer.identify(emb, peopleProvider())?.name else null
            currentSpeaker = name
            if (name != null) log("voice_id", "声纹识别：$name")
        }
    }

    /** 主动消息轮询协程。 */
    private var proactiveJob: Job? = null
    @Volatile private var lastProactiveId: Long = 0L

    /** 唤醒词喂流监听器（稳定引用，避免重复 add 累积）。回声冷却期内跳过（避免主动消息播报尾音误触发）。 */
    private val wakeFeedListener: (ShortArray) -> Unit = { samples ->
        if (::wakeWord.isInitialized && !micSuppressed()) wakeWord.feedPcm16(samples)
    }

    /**
     * 常驻 ASR 喂流 + 声纹缓冲累积监听器（稳定引用，[initAsr] 成功后挂载一次，常驻整个运行期）。
     *
     * 在 **IDLE 和 LISTENING** 两个相位都工作，使端侧 ASR 能持续检出整句（不仅限于对话中），
     * 实现「只要说出完整句子就记录」。回声冷却期内同样跳过（TTS/主动消息的扬声器余音不应被当成用户语音）。
     *
     * 双重职责：
     * 1. 喂 PCM 给端侧 ASR（[asr]）做整句检出 → final 回调到 [onAsrFinal] 记录。
     * 2. 累积 PCM 到 [ambientSpeakerBuf]，供 [onAsrFinal] snapshot 提嵌入识别说话人。
     *
     * LISTENING 期不累积声纹缓冲：对话路径的 [identifySpeakerAsync]（用 [speakerBuf]）已负责
     * 该相位的说话人识别；[onAsrFinal] 也仅在 IDLE 记录，避免重复。LISTENING 期仍喂流给 ASR
     * 以驱动对话字幕显示（与原行为一致）。
     */
    private val ambientFeedListener: (ShortArray) -> Unit = { samples ->
        if (!micSuppressed() && onDeviceSubtitleEnabled) {
            val s = _state.value
            if (s == VoiceState.IDLE || s == VoiceState.LISTENING) {
                asr?.feedPcm16(samples)
                // 仅在 IDLE 累积声纹缓冲（LISTENING 期由对话路径 identifySpeakerAsync 负责，
                // 避免双重识别 + 重复记录）。
                if (s == VoiceState.IDLE && voiceIdentityEnabled && voiceRecognizer != null) {
                    for (sm in samples) {
                        ambientSpeakerBuf.addLast(sm)
                        if (ambientSpeakerBuf.size > AMBIENT_BUF_SAMPLES) ambientSpeakerBuf.removeFirst()
                    }
                }
            }
        }
    }

    /**
     * 常驻 ASR 整句回调：端侧 ASR 检出一句即记录到 [voiceLog]，并 snapshot [ambientSpeakerBuf]
     * 提嵌入识别说话人（主人/其他人/未知）。仅在 [VoiceState.IDLE] 记录——对话聆听相位由对话
     * 主流程处理，避免重复。
     *
     * 去重：与上一条相同文字不重复记录（端侧 ASR 可能把尾音切成多段 final）。
     */
    private fun onAsrFinal(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // 仅在 IDLE 记录：对话期间（WAKING/LISTENING/...）由对话路径处理，避免重复。
        if (_state.value != VoiceState.IDLE) return
        if (t == lastLoggedText) return
        lastLoggedText = t
        val vl = voiceLog ?: return
        val recognizer = voiceRecognizer
        // 声纹未启用/未就绪：直接记未知说话人。
        if (!voiceIdentityEnabled || recognizer == null || !recognizer.isReady || ambientSpeakerBuf.isEmpty()) {
            vl.log(t, null, false)
            return
        }
        // snapshot 当前缓冲并异步提嵌入识别。
        val pcm = ShortArray(ambientSpeakerBuf.size) { ambientSpeakerBuf[it] }
        scope.launch {
            val emb = recognizer.embed(pcm)
            val person = if (emb != null) recognizer.identify(emb, peopleProvider()) else null
            val name = person?.name
            val owner = person?.relation == com.xbot.android.model.FamilyRelation.OWNER
            vl.log(t, name, owner)
        }
    }

    /** 标记可用（麦克风权限通过）。 */
    fun markAvailable() { isAvailable = true }

    private fun log(stage: String, msg: String, error: Boolean = false) {
        conversationLog?.log(stage, msg, error)
    }

    fun initialize() {
        wakeWord = WakeWordService(
            context = context,
            modelDir = "voice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01",
            keyword = "你好小白",
            onWake = { onWake(it, "wake") },
        )
        scope.launch { wakeWord.initialize() }
    }

    /** 启动：set idle，开麦，唤醒监听，主动消息轮询。 */
    fun start() {
        if (isRunning) return
        isRunning = true
        setState(VoiceState.IDLE, null)
        // 端侧降噪：异步初始化（native 加载），完成后注入采集管线。失败降级直通。
        initDenoiser()
        // 端侧实时字幕：异步初始化（native 加载），完成后挂喂流监听。失败降级为不显示。
        initAsr()
        audio.start(context)
        startWakeListening()
        startProactivePolling()
    }

    /** 初始化 GTCRN 降噪器并注入 [audio]。幂等：已存在则跳过。 */
    private fun initDenoiser() {
        if (denoiser != null) return
        scope.launch {
            val d = SpeechDenoiser(context)
            d.initialize()
            if (d.isReady) {
                denoiser = d
                audio.denoiser = d
                log("denoise", "GTCRN 降噪已启用")
            } else {
                // 加载失败：保留实例便于 release，但采集直通（audio.denoiser 保持 null）。
                denoiser = d
                log("denoise", "GTCRN 降噪未就绪，采集直通", true)
            }
        }
    }

    /** 初始化端侧流式 ASR（字幕 + 常驻语音记录）。幂等：已存在则跳过。模型加载失败则字幕降级关闭。 */
    private fun initAsr() {
        if (asr != null) return
        scope.launch {
            val a = StreamingAsrService(
                context = context,
                modelDir = "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
                onPartial = onPartialText,
                // final：既刷新字幕（与原行为一致），又驱动常驻语音记录（[onAsrFinal]）。
                onFinal = { text ->
                    onPartialText(text)
                    onAsrFinal(text)
                },
            )
            a.initialize()
            if (a.isReady) {
                a.start()
                asr = a
                // 挂上常驻喂流监听（IDLE+LISTENING 都喂流，实现持续整句检出 + 语音记录）。
                // 回声冷却与相位门控在 listener 内，靠 state 门控喂流与缓冲累积。
                audio.removeChunkListener(ambientFeedListener)
                audio.addChunkListener(ambientFeedListener)
                log("asr", "端侧实时字幕已启用")
            } else {
                // 加载失败：保留实例便于 release，但不挂监听（字幕不显示）。
                asr = a
                log("asr", "端侧 ASR 未就绪，实时字幕关闭", true)
            }
        }
    }

    fun stop() {
        isRunning = false
        // 先取消对话协程（避免 finally 块与下面的释放竞争）。
        conversationJob?.cancel()
        conversationJob = null
        proactiveJob?.cancel()
        proactiveJob = null
        try { activeStt?.close() } catch (_: Exception) {}
        activeStt = null
        if (::wakeWord.isInitialized) {
            try { wakeWord.stop() } catch (_: Exception) {}
        }
        try { audio.removeChunkListener(wakeFeedListener) } catch (_: Exception) {}
        try { audio.removeChunkListener(ambientFeedListener) } catch (_: Exception) {}
        // 先摘除 denoiser 引用再 stop（避免采集线程在释放中仍访问）。
        audio.denoiser = null
        try { audio.stop() } catch (_: Exception) {}
        try { denoiser?.release() } catch (_: Exception) {}
        denoiser = null
        try { asr?.release() } catch (_: Exception) {}
        asr = null
        try { tts.release() } catch (_: Exception) {}
        try { setState(VoiceState.IDLE, null) } catch (_: Exception) {}
    }

    // ============ 主动消息轮询（每 5s 检查后端是否有到点的欢迎语/提醒）============

    private fun startProactivePolling() {
        proactiveJob?.cancel()
        proactiveJob = scope.launch {
            while (isRunning) {
                delay(5000)
                if (_state.value != VoiceState.IDLE) continue
                try {
                    val messages = pophie.fetchProactiveMessages(lastProactiveId)
                    if (messages.isNotEmpty()) {
                        val msg = messages.last()
                        lastProactiveId = msg.id
                        log("proactive", "收到主动消息: ${msg.trigger}")
                        // 在 idle 时播报。
                        if (_state.value == VoiceState.IDLE && isRunning) {
                            playProactive(msg.content)
                        }
                    }
                } catch (_: Exception) {
                    // 轮询失败静默，下次重试。
                }
            }
        }
    }

    /** 播报一条主动消息（走 TTS）。 */
    private suspend fun playProactive(text: String) {
        if (!ttsEnabled || text.isBlank()) return
        try {
            setState(VoiceState.SPEAKING, null)
            audio.externalLevel = true
            tts.start(22050)
            pophie.ttsStream(
                text = text, voice = null,
                onMeta = {},
                onChunk = { pcm -> tts.feedChunk(pcm) },
                onDone = {},
            )
            tts.markFeedingDone()
            val waitMs = (tts.totalSamplesFed.toLong() * 1000 / 22050) + 2000
            val startAt = System.currentTimeMillis()
            while (!tts.drained && System.currentTimeMillis() - startAt < waitMs && isRunning) {
                delay(50)
            }
            tts.release()
            audio.externalLevel = false
            onLevel(0f)
            // 设置回声冷却期：扬声器余音+房间混响不应触发唤醒词或被误识别。
            applyEchoSuppression()
            log("proactive", "主动消息播报完成")
        } catch (e: Exception) {
            log("proactive", "主动消息播报失败: ${e.message}", true)
        } finally {
            audio.externalLevel = false
            setState(VoiceState.IDLE, null)
        }
    }

    /** 恢复唤醒监听（幂等：先移除再添加稳定监听器）。 */
    private fun startWakeListening() {
        if (!wakeWordEnabled || !wakeWordReady) return
        audio.removeChunkListener(wakeFeedListener)
        audio.addChunkListener(wakeFeedListener)
        wakeWord.start()
    }

    /** 手动触发一轮对话（双击 / 注视触发）。 */
    fun triggerManually(source: String = "manual") {
        onWake(currentKeyword(), source)
    }

    /**
     * 主动退出聆听/中止当前轮（barge-in 退出）：停 TTS、取消后端请求、关 STT WS、回 idle。
     * 对齐 IBotServer 的 exitConversationMode(USER_TOGGLE)。
     * 复用 [PophieClient.cancelAll] / [StreamingTtsPlayer.release] / [SttStreamClient.close]。
     * 取消 conversationJob 后由 runStreamingConversation 的 finally 收尾（回 idle + 恢复唤醒）。
     */
    fun stopConversation(reason: String = "user_stop") {
        if (_state.value == VoiceState.IDLE) return
        log("barge_in", "主动退出聆听/中止当前轮: $reason")
        abortConversation = true
        try { pophie.cancelAll() } catch (_: Exception) {}                    // 立即取消 chatStream/ttsStream 的 OkHttp 请求
        try { tts.release() } catch (_: Exception) {}                         // 立即停播（不等协程取消传播）
        try { audio.externalLevel = false; onLevel(0f) } catch (_: Exception) {}
        try { activeStt?.close(sendEndFrame = true) } catch (_: Exception) {} // 通知服务端结束聆听（发 {type:"end"}）
        try { conversationJob?.cancel() } catch (_: Exception) {}             // 触发 runStreamingConversation finally → idle + 唤醒
    }

    /** 运行时更新唤醒词（设置页修改后调用）。 */
    fun setWakeKeyword(keyword: String) {
        if (::wakeWord.isInitialized) {
            val wasListening = isRunning && _state.value == VoiceState.IDLE
            wakeWord.setKeyword(keyword)
            scope.launch {
                wakeWord.initialize()
                if (wasListening) startWakeListening()
            }
        }
    }

    private fun currentKeyword(): String =
        if (::wakeWord.isInitialized) wakeWord.currentKeyword else "你好小白"

    /** 唤醒入口。 */
    private fun onWake(keyword: String, source: String) {
        if (!isRunning) return
        // 仅在 idle 时触发，避免聆听/思考/播报中重复触发。
        if (_state.value != VoiceState.IDLE) return
        if (conversationJob?.isActive == true) return
        log("wake", "触发对话: $source" + if (source == "wake") "（唤醒词「$keyword」）" else "")
        // 流式模式默认；关闭流式或流式失败时走批量 fallback。
        conversationJob = scope.launch {
            if (streamingSttEnabled) {
                try {
                    runStreamingConversation()
                } catch (e: Exception) {
                    log("error", "流式对话异常，fallback 批量: ${e.message}", true)
                    runBatchConversation()
                }
            } else {
                runBatchConversation()
            }
        }
    }

    /**
     * 批量对话 fallback：VAD 采集一段语音 → WAV → /api/chat → 流式 TTS。
     * 对应 Flutter voice_assistant._runConversation。
     */
    private suspend fun runBatchConversation() {
        setState(VoiceState.WAKING, null)
        if (::wakeWord.isInitialized) wakeWord.stop()
        audio.removeChunkListener(wakeFeedListener)
        try {
            // 1. 聆听：VAD 采集一段语音。
            setState(VoiceState.LISTENING, null)
            log("listen", "批量模式：VAD 采集语音…")
            val vad = audio.captureUtterance(context)
            if (vad == null || vad.pcm16.isEmpty()) {
                log("listen", "未采集到语音（超时或无人说话）")
                return
            }
            log("listen", "采集完成: ${vad.pcm16.size} samples, 静音 ${vad.silenceMs}ms")
            // 2. 思考：批量对话（WAV → /api/chat）。
            val wav = pophie.pcm16ToWav(vad.pcm16)
            setState(VoiceState.THINKING, null)
            log("think", "POST /api/chat（批量）…")
            val result = pophie.chat(wavBytes = wav, perception = perceptionProvider())
            robotState = result.robotState
            log("think", "/api/chat 完成 | LLM=\"${result.text}\" | robotState=${result.robotState}")
            if (result.text.isBlank()) {
                log("think", "静默回复，不播报", true)
                return
            }
            // 3. 播报：流式 TTS。
            if (ttsEnabled) {
                speakText(result.text, null)
            }
        } catch (e: Exception) {
            log("error", "批量对话异常: ${e.message}", true)
        } finally {
            robotState = null
            if (isRunning) {
                startWakeListening()
                setState(VoiceState.IDLE, null)
                log("end", "批量对话结束，回到 idle")
            }
        }
    }

    /** 播报一段文本（流式 TTS）。供 handleTurn / 批量 / 主动消息共用。 */
    private suspend fun speakText(text: String, voice: JSONObject?) {
        setState(VoiceState.SPEAKING, robotState)
        audio.externalLevel = true
        tts.start(22050)
        try {
            pophie.ttsStream(
                text = text, voice = voice,
                onMeta = {},
                onChunk = { pcm -> tts.feedChunk(pcm) },
                onDone = {},
            )
        } catch (e: Exception) {
            log("speak", "TTS 失败: ${e.message}", true)
        }
        tts.markFeedingDone()
        val waitMs = (tts.totalSamplesFed.toLong() * 1000 / 22050) + 2000
        val startAt = System.currentTimeMillis()
        while (!tts.drained && System.currentTimeMillis() - startAt < waitMs && isRunning) {
            delay(50)
        }
        tts.release()
        audio.externalLevel = false
        onLevel(0f)
        // 设置回声冷却期：扬声器余音+房间混响在随后切回聆听时不应被当作用户语音上行。
        applyEchoSuppression()
        log("speak", "播报完成")
    }

    /**
     * 流式对话主循环：WS 建连 → ready → 持续聆听；每个 final 处理一轮（chatStream
     * + ttsStream），播完回到聆听（多轮复用同一 WS）；session_end/error/closed/stop 退出。
     */
    private suspend fun runStreamingConversation() {
        setState(VoiceState.WAKING, null)
        if (::wakeWord.isInitialized) wakeWord.stop()
        audio.removeChunkListener(wakeFeedListener)

        log("listen", "连接流式 STT WebSocket…")
        val stt = SttStreamClient(pophie.config.sttWsUrl())
        activeStt = stt
        val sessionDone = AtomicBoolean(false)
        val handlingTurn = AtomicBoolean(false)
        val pendingTurn = AtomicReference<Pair<String, JSONObject?>?>(null)
        // 上行抗噪门控：与端侧字幕同一判据，挡住低能量杂音/小音量旁人被上传给后端。
        // 仅在采集线程（feedListener）调用，单写者无需同步。
        val uplinkGate = AudioGate()
        // 声纹 PCM 环形缓冲：聆听相位累积，final 时 snapshot 提嵌入。单写者（feedListener）。
        val speakerBuf = ArrayDeque<Short>()
        fun resetSpeakerBuf() { speakerBuf.clear() }
        sawPartialSinceListen = false
        lastPartialText = ""
        lastFinalText = ""
        abortConversation = false

        try {
            try {
                stt.connect { ev ->
                    when (ev) {
                        is SttStreamClient.Event.Meta -> {}
                        is SttStreamClient.Event.Ready -> {
                            sawPartialSinceListen = false
                            lastPartialText = ""
                            // 回声冷却期内不重置门控：保留较高底噪估计，避免在残留回声窗内变得过松。
                            if (!micSuppressed()) uplinkGate.reset()
                            resetSpeakerBuf()  // 新聆听窗口，清空声纹缓冲
                            asr?.reset()  // 端侧字幕对齐新聆听窗口
                            onPartialText("")
                            setState(VoiceState.LISTENING, null)
                            log("listen", "STT ready，开始聆听")
                        }
                        is SttStreamClient.Event.Partial -> {
                            if (!handlingTurn.get() && ev.text.isNotEmpty()) {
                                lastPartialText = ev.text
                                sawPartialSinceListen = true
                            }
                        }
                        is SttStreamClient.Event.Final -> {
                            if (handlingTurn.get()) return@connect
                            val text = ev.text.trim()
                            if (text.isEmpty()) return@connect
                            // 残留 final 过滤①：本窗口内无 partial → 疑似上段尾音残留，丢弃。
                            if (!sawPartialSinceListen) {
                                log("listen", "忽略无 partial 的 final（疑似残留尾音）: \"$text\"", true)
                                return@connect
                            }
                            // 残留 final 过滤②：与上一条相同 → 重复，丢弃。
                            if (text == lastFinalText) {
                                log("listen", "忽略重复 final（疑似残留）: \"$text\"", true)
                                return@connect
                            }
                            lastFinalText = text
                            sawPartialSinceListen = false
                            pendingTurn.set(text to ev.voice)
                            // 声纹识别：final 到来时本窗口已有一段完整语音，snapshot 缓冲提嵌入。
                            // 在 WS 回调线程，提嵌入耗时，丢协程异步执行（不阻塞对话主流程）。
                            identifySpeakerAsync(speakerBuf)
                        }
                        is SttStreamClient.Event.SessionEnd -> {
                            log("end", "服务端结束会话: ${ev.message ?: "空闲超时"}")
                            sessionDone.set(true)
                        }
                        is SttStreamClient.Event.StopSpeaking -> {
                            // 服务端判定用户要立即停止语音交互（关键词初筛+LLM 确认）：
                            // 停 TTS、关 STT WS、回 idle。复用 stopConversation 停止原语。
                            log("barge_in", "服务端判定停止语音交互: \"${ev.text}\"（${ev.reason ?: "无理由"}）")
                            sessionDone.set(true)
                            stopConversation("server_stop_speaking")
                        }
                        is SttStreamClient.Event.Error -> {
                            log("error", "STT 错误: ${ev.message}", true)
                            sessionDone.set(true)
                        }
                        is SttStreamClient.Event.Closed -> sessionDone.set(true)
                    }
                }
            } catch (e: Exception) {
                log("error", "流式 STT 连接失败，本轮结束: ${e.message}", true)
                return
            }

            stt.start()

            // 持续把麦克风 chunk 上行（仅聆听相位、未在处理轮、会话未结束）。
            // 经 uplinkGate 抗噪门控：低能量杂音/小音量旁人不上传，减少后端误识别。
            // 回声冷却期内禁止上行：TTS 刚结束的扬声器余音+房间混响会被误识别成用户语音（自激循环）。
            val feedListener: (ShortArray) -> Unit = { samples ->
                val listening = _state.value == VoiceState.LISTENING &&
                    !handlingTurn.get() && !sessionDone.get() && !micSuppressed()
                if (listening) {
                    if (uplinkGate.accept(samples)) {
                        stt.sendChunk(pophie.pcm16ToBytes(samples))
                    }
                    // 声纹缓冲：累积本窗口 PCM（无论是否过门控，保证有足够语音提嵌入）。
                    // 仅在启用声纹且有识别器时累积，避免无谓开销。
                    if (voiceIdentityEnabled && voiceRecognizer != null) {
                        for (s in samples) {
                            speakerBuf.addLast(s)
                            if (speakerBuf.size > SPEAKER_BUF_SAMPLES) speakerBuf.removeFirst()
                        }
                    }
                }
            }
            audio.addChunkListener(feedListener)
            try {
                while (!sessionDone.get() && isRunning && !abortConversation) {
                    val turn = pendingTurn.getAndSet(null)
                    if (turn != null) {
                        handlingTurn.set(true)
                        try {
                            handleTurn(turn.first, turn.second)
                        } catch (e: Exception) {
                            log("error", "处理对话轮异常: ${e.message}", true)
                        } finally {
                            handlingTurn.set(false)
                        }
                        if (!sessionDone.get() && isRunning && !abortConversation) {
                            // 回到聆听（多轮复用同一 WS）。
                            sawPartialSinceListen = false
                            lastPartialText = ""
                            lastFinalText = ""
                            // 回声冷却期内不重置门控：刚播完 TTS，保留较高底噪估计挡住扬声器余音，
                            // 避免在残留回声窗内 reset 到敏感初值 0.0025 而放过回声。
                            if (!micSuppressed()) uplinkGate.reset()
                            resetSpeakerBuf()  // 新聆听窗口，清空声纹缓冲
                            asr?.reset()  // 端侧字幕对齐新聆听窗口
                            onPartialText("")
                            setState(VoiceState.LISTENING, null)
                            log("listen", "回到聆听（多轮复用同一 WS）")
                        }
                    } else {
                        delay(50)
                    }
                }
            } finally {
                audio.removeChunkListener(feedListener)
            }
        } catch (e: Exception) {
            log("error", "流式对话异常: ${e.message}", true)
        } finally {
            activeStt = null
            try { stt.close(sendEndFrame = true) } catch (_: Exception) {}
            robotState = null
            currentSpeaker = null  // 会话结束，清空说话人身份（避免上轮残留）
            if (isRunning) {
                startWakeListening()
                setState(VoiceState.IDLE, null)
                log("end", "流式对话结束，回到 idle")
            }
        }
    }

    /** 一轮对话：chatStream → 逐段 ttsStream。半双工（聆听已暂停）。 */
    private suspend fun handleTurn(text: String, voice: JSONObject?) {
        log("listen", "识别完成: \"$text\"")
        try {
            setState(VoiceState.THINKING, null)
            log("think", "POST /api/chat/stream（流式）…")
            val segments = mutableListOf<String>()
            val result = pophie.chatStream(
                text = text,
                perception = perceptionProvider(),
                onSpeak = { seg -> if (seg.isNotBlank()) segments.add(seg) },
            )
            robotState = result.robotState
            log("think", "/api/chat/stream 完成 | LLM=\"${result.text}\" | robotState=${result.robotState}")

            if (result.text.isBlank() && segments.isEmpty()) {
                log("think", "静默回复（空 STT 或 LLM 失败），不播报", true)
                return
            }

            if (ttsEnabled) {
                setState(VoiceState.SPEAKING, result.robotState)
                audio.externalLevel = true
                tts.start(22050)
                val toSpeak = if (segments.isNotEmpty()) segments else listOf(result.text)
                for (seg in toSpeak) {
                    if (seg.isBlank()) continue
                    try {
                        pophie.ttsStream(
                            text = seg, voice = voice,
                            onMeta = { /* 首段已 start；后续沿用 */ },
                            onChunk = { pcm -> tts.feedChunk(pcm) },
                            onDone = { /* first_packet_ms */ },
                        )
                    } catch (e: Exception) {
                        log("speak", "分句合成失败，跳过: ${e.message}", true)
                    }
                }
                tts.markFeedingDone()
                // 等 drain（最多等 totalSamples/sampleRate + 2s）。
                val waitMs = (tts.totalSamplesFed.toLong() * 1000 / 22050) + 2000
                val startAt = System.currentTimeMillis()
                while (!tts.drained && System.currentTimeMillis() - startAt < waitMs && isRunning) {
                    delay(50)
                }
                tts.release()
                audio.externalLevel = false
                onLevel(0f)
                // 设置回声冷却期：扬声器余音+房间混响在随后切回聆听时不应被当作用户语音上行。
                applyEchoSuppression()
                log("speak", "播报完成")
            }
        } catch (e: Exception) {
            log("error", "handleTurn 失败: ${e.message}", true)
        } finally {
            audio.externalLevel = false
        }
    }

    private fun setState(s: VoiceState, robotStateOverride: String?) {
        val prev = _state.value
        _state.value = s
        // 离开聆听相位时清空字幕（进入 THINKING/SPEAKING/IDLE 时隐藏）。
        if (prev == VoiceState.LISTENING && s != VoiceState.LISTENING) {
            onPartialText("")
        }
        val fs = robotStateOverride ?: s.faceState
        onStateChange(s, fs)
    }

    fun release() {
        stop()
        if (::wakeWord.isInitialized) {
            try { wakeWord.release() } catch (_: Exception) {}
        }
        scope.coroutineContext[Job]?.cancel()
    }
}
