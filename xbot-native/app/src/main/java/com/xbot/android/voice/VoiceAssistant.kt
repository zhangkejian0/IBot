package com.xbot.android.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 语音助手协调器：状态机驱动 唤醒 → 流式 STT → Pophie 对话 → 流式 TTS。
 * 对应 Flutter voice_assistant.dart 的主流程（流式模式）。
 *
 * 默认半双工：TTS 时停止采流（audio.stop()），物理上无回声路径，无需 barge-in。
 *
 * @param onStateChange 状态变化回调（驱动虚拟形象 setState + 嘴部）
 * @param onLevel 音量变化（listening 用麦音量，speaking 用 TTS 音量）
 * @param perceptionProvider 每轮对话上传的感知上下文（表情/身份/手势/物体/场景）
 */
class VoiceAssistant(
    private val context: Context,
    private val onStateChange: (VoiceState, String?) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val perceptionProvider: () -> JSONObject? = { null },
) {
    companion object { private const val TAG = "VoiceAssistant" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = AudioCapture()
    private lateinit var wakeWord: WakeWordService
    val pophie = PophieClient(PophieConfig())
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

    private var conversationJob: Job? = null
    private var lastPartialText: String = ""
    private var sawPartialSinceListen = false

    /** 标记可用（麦克风权限通过）。 */
    fun markAvailable() { isAvailable = true }

    fun initialize() {
        wakeWord = WakeWordService(
            context = context,
            modelDir = "voice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01",
            keyword = "你好小白",
            onWake = { onWake(it, "wake") },
        )
        scope.launch { wakeWord.initialize() }
    }

    /** 启动：set idle，订阅唤醒，开麦，唤醒监听。 */
    fun start() {
        if (isRunning) return
        isRunning = true
        setState(VoiceState.IDLE, null)
        audio.start(context)
        if (wakeWordEnabled && wakeWord.isReady) {
            audio.addChunkListener { samples -> wakeWord.feedPcm16(samples) }
            wakeWord.start()
        }
    }

    fun stop() {
        isRunning = false
        conversationJob?.cancel()
        conversationJob = null
        wakeWord.stop()
        audio.stop()
        tts.release()
        setState(VoiceState.IDLE, null)
    }

    /** 手动触发一轮对话（双击 / 注视触发）。 */
    fun triggerManually(source: String = "manual") {
        onWake(currentKeyword(), source)
    }

    private fun currentKeyword(): String = if (::wakeWord.isInitialized) wakeWord.currentKeyword else "你好小白"

    /** 唤醒入口：分流流式 / 批量。 */
    private fun onWake(keyword: String, source: String) {
        if (!isRunning) return
        // 仅在 idle 时触发，避免聆听/思考/播报中重复触发。
        if (_state.value != VoiceState.IDLE) return
        if (conversationJob?.isActive == true) return
        conversationJob = scope.launch {
            if (streamingSttEnabled) runStreamingConversation() else runBatchConversation()
        }
    }

    /** 流式对话：WS STT → chatStream → ttsStream。 */
    private suspend fun runStreamingConversation() {
        setState(VoiceState.WAKING, null)
        wakeWord.stop()
        val stt = SttStreamClient(pophie.config.sttWsUrl())
        var sessionDone = false
        try {
            // 建连；失败 fallback 批量。
            stt.connect { ev ->
                when (ev) {
                    is SttStreamClient.Event.Meta -> {}
                    is SttStreamClient.Event.Ready -> {
                        sawPartialSinceListen = false
                        lastPartialText = ""
                        setState(VoiceState.LISTENING, null)
                    }
                    is SttStreamClient.Event.Partial -> {
                        if (ev.text.isNotEmpty()) {
                            lastPartialText = ev.text
                            sawPartialSinceListen = true
                        }
                    }
                    is SttStreamClient.Event.Final -> {
                        val text = ev.text.trim()
                        // 两个残留 final 过滤：(a) 无 partial 见过 (b) 与上一次相同。
                        if (text.isNotEmpty() && sawPartialSinceListen && text != lastFinalText) {
                            lastFinalText = text
                            sessionDone = true
                            // 半双工：停麦，进入思考+播报（在协程中跑）。
                            scope.launch { handleTurn(text, ev.voice) }
                        }
                    }
                    is SttStreamClient.Event.SessionEnd, is SttStreamClient.Event.Error,
                    is SttStreamClient.Event.Closed -> { sessionDone = true }
                }
            }
            stt.start()
            // 持续喂 chunk（仅 listening 且未在处理 turn 时）。
            val feedListener: (ShortArray) -> Unit = { samples ->
                if (_state.value == VoiceState.LISTENING && !sessionDone) {
                    stt.sendChunk(pophie.pcm16ToBytes(samples))
                }
            }
            audio.addChunkListener(feedListener)
            // 等会话结束（final / session_end / error）。
            while (!sessionDone && isRunning) Thread.sleep(50)
            audio.removeChunkListener(feedListener)
        } catch (e: Exception) {
            Log.e(TAG, "流式对话失败，fallback 批量: ${e.message}")
            runBatchConversation()
        } finally {
            stt.close()
            if (isRunning) {
                // 恢复唤醒监听。
                audio.start(context)
                if (wakeWordEnabled && wakeWord.isReady) {
                    audio.addChunkListener { samples -> wakeWord.feedPcm16(samples) }
                    wakeWord.start()
                }
                setState(VoiceState.IDLE, null)
            }
        }
    }

    @Volatile private var lastFinalText: String = ""

    /** 一轮对话：chatStream → 逐段 ttsStream。半双工（已停麦）。 */
    private suspend fun handleTurn(text: String, voice: JSONObject?) {
        try {
            setState(VoiceState.THINKING, null)
            // chatStream 逐段 speak → TTS 每段。
            val segments = mutableListOf<String>()
            val result = pophie.chatStream(
                text = text,
                perception = perceptionProvider(),
                onSpeak = { seg -> if (seg.isNotBlank()) segments.add(seg) },
            )
            robotState = result.robotState
            // 播报。
            if (ttsEnabled && (segments.isNotEmpty() || result.text.isNotBlank())) {
                setState(VoiceState.SPEAKING, result.robotState)
                audio.externalLevel = true
                tts.start(22050)
                val toSpeak = if (segments.isNotEmpty()) segments else listOf(result.text)
                for (seg in toSpeak) {
                    if (seg.isBlank()) continue
                    try {
                        pophie.ttsStream(
                            text = seg, voice = voice,
                            onMeta = { sr -> /* 首段已 start；后续沿用 */ },
                            onChunk = { pcm -> tts.feedChunk(pcm) },
                            onDone = { /* first_packet_ms */ },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS 段失败: ${e.message}")
                    }
                }
                tts.markFeedingDone()
                // 等 drain（最多等 totalSamples/sampleRate + 2s）。
                val waitMs = (tts.totalSamplesFed.toLong() * 1000 / 22050) + 2000
                val start = System.currentTimeMillis()
                while (!tts.drained && System.currentTimeMillis() - start < waitMs && isRunning) {
                    Thread.sleep(50)
                }
                tts.release()
                audio.externalLevel = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTurn 失败: ${e.message}")
        }
    }

    /** 批量对话 fallback：captureUtterance → chat → ttsStream。 */
    private suspend fun runBatchConversation() {
        // 简化：用最近 1.5s 的麦缓冲作为 utterance（生产级需 VAD）。
        // 这里复用流式路径的思路：直接 fallback 到流式更合理，故此处仅占位。
        runStreamingConversation()
    }

    private fun setState(s: VoiceState, robotStateOverride: String?) {
        _state.value = s
        val fs = robotStateOverride ?: s.faceState
        onStateChange(s, fs)
    }

    fun release() {
        stop()
        wakeWord.release()
        scope.coroutineContext[Job]?.cancel()
    }
}
