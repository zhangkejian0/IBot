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
 * @param config Pophie 后端配置（地址/robotId/音色；可由设置注入）
 */
class VoiceAssistant(
    private val context: Context,
    private val onStateChange: (VoiceState, String?) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val perceptionProvider: () -> JSONObject? = { null },
    val conversationLog: ConversationLogger? = null,
    config: PophieConfig = PophieConfig(),
) {
    companion object { private const val TAG = "VoiceAssistant" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = AudioCapture()
    private lateinit var wakeWord: WakeWordService
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

    /** 唤醒词喂流监听器（稳定引用，避免重复 add 累积）。 */
    private val wakeFeedListener: (ShortArray) -> Unit = { samples ->
        if (::wakeWord.isInitialized) wakeWord.feedPcm16(samples)
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

    /** 启动：set idle，开麦，唤醒监听。 */
    fun start() {
        if (isRunning) return
        isRunning = true
        setState(VoiceState.IDLE, null)
        audio.start(context)
        startWakeListening()
    }

    fun stop() {
        isRunning = false
        conversationJob?.cancel()
        conversationJob = null
        try { activeStt?.close() } catch (_: Exception) {}
        activeStt = null
        if (::wakeWord.isInitialized) wakeWord.stop()
        audio.removeChunkListener(wakeFeedListener)
        audio.stop()
        tts.release()
        setState(VoiceState.IDLE, null)
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
        // 当前原生版仅实现流式对话模式（streamingSttEnabled 关闭时亦走流式）。
        conversationJob = scope.launch { runStreamingConversation() }
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
        sawPartialSinceListen = false
        lastPartialText = ""
        lastFinalText = ""

        try {
            try {
                stt.connect { ev ->
                    when (ev) {
                        is SttStreamClient.Event.Meta -> {}
                        is SttStreamClient.Event.Ready -> {
                            sawPartialSinceListen = false
                            lastPartialText = ""
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
                        }
                        is SttStreamClient.Event.SessionEnd -> {
                            log("end", "服务端结束会话: ${ev.message ?: "空闲超时"}")
                            sessionDone.set(true)
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
            val feedListener: (ShortArray) -> Unit = { samples ->
                if (_state.value == VoiceState.LISTENING && !handlingTurn.get() && !sessionDone.get()) {
                    stt.sendChunk(pophie.pcm16ToBytes(samples))
                }
            }
            audio.addChunkListener(feedListener)
            try {
                while (!sessionDone.get() && isRunning) {
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
                        if (!sessionDone.get() && isRunning) {
                            // 回到聆听（多轮复用同一 WS）。
                            sawPartialSinceListen = false
                            lastPartialText = ""
                            lastFinalText = ""
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
                log("speak", "播报完成")
            }
        } catch (e: Exception) {
            log("error", "handleTurn 失败: ${e.message}", true)
        } finally {
            audio.externalLevel = false
        }
    }

    private fun setState(s: VoiceState, robotStateOverride: String?) {
        _state.value = s
        val fs = robotStateOverride ?: s.faceState
        onStateChange(s, fs)
    }

    fun release() {
        stop()
        if (::wakeWord.isInitialized) wakeWord.release()
        scope.coroutineContext[Job]?.cancel()
    }
}
