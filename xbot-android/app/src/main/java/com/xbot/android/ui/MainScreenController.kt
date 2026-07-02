package com.xbot.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.xbot.android.camera.CameraManager
import com.xbot.android.camera.FrameAnalyzer
import com.xbot.android.core.AppTuning
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.Person
import com.xbot.android.store.SettingsStore
import com.xbot.android.voice.ConversationLogger
import com.xbot.android.vision.FaceLandmarkEngine
import com.xbot.android.vision.FaceRecognizer
import com.xbot.android.vision.GestureEngine
import com.xbot.android.vision.MlKitFaceEngine
import com.xbot.android.vision.ObjectEngine
import com.xbot.android.vision.PoseLandmarkEngine
import com.xbot.android.vision.VisionPipeline
import com.xbot.android.webview.FaceWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * 主界面控制器（阶段 1：完整视觉感知 + 行为聚合）。
 *
 * 相机管线 → VisionPipeline（face+hand+pose+多脸+身份+物体）→ BehaviorTracker/ActivityTracker
 * → FaceBridge 注入 setState/setGazeTarget（视觉态仅注意力态驱动）。
 *
 * @param peopleProvider 人物库提供者（阶段 2 接入 PersonRepository；阶段 1 返回空列表）
 */
class MainScreenController(
    val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val hasCameraPermission: () -> Boolean,
    private val onRequestCamera: () -> Unit,
    private val hasMicPermission: () -> Boolean,
    private val onRequestMic: () -> Unit,
    val settingsStore: SettingsStore,
    private val peopleProvider: () -> List<Person> = { emptyList() },
    private val voiceRecognizerProvider: () -> com.xbot.android.voice.VoiceRecognizer? = { null },
    /** 资源管理器：供端侧字幕 ASR 解析已下载的 paraformer 模型路径。 */
    private val resources: com.xbot.android.core.ResourceManager? = null,
) {
    companion object {
        private const val FACE_MODEL_PATH = "face_landmarker.task"
        private const val POSE_MODEL_PATH = "pose_landmarker_lite.task"
        private const val GESTURE_MODEL_PATH = "gesture_recognizer.task"
        private const val MOBILEFACENET_PATH = "mobilefacenet.tflite"
        private const val YOLO_PATH = "yolo26n_int8.tflite"

        // —— 麦克风音量经验 dB 校准（详见 levelToDb 注释）——
        // 斜率 13.3：降噪后人声动态更舒展（比降噪前的 28.6 平缓，避免说话过早顶到 90）。
        private const val DB_SLOPE = 13.3f
        // 截距 81.9：使降噪后安静底噪 level≈0.001 落在 ≈42dB；全体平移改这个值即可。
        private const val DB_OFFSET = 81.9f
    }

    var faceWebView: FaceWebView? = null
        private set

    /** 对话交互日志（设置页「交互日志」查看）。 */
    val conversationLog = ConversationLogger()

    /** 语音识别记录（设置页「语音记录」查看）：每句识别文字 + 声纹判定说话人。 */
    val voiceLog = com.xbot.android.voice.VoiceLogStore()

    /** 语音助手（惰性初始化：仅当需要时才创建，避免启动阶段因 sherpa-onnx 等 native 库加载导致崩溃）。 */
    @Volatile var voiceAssistant: com.xbot.android.voice.VoiceAssistant? = null
        private set

    private val cameraManager = CameraManager(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // —— 视觉引擎 ——
    private val faceEngine = FaceLandmarkEngine(context, FACE_MODEL_PATH)
    private val mlkitEngine = MlKitFaceEngine(maxFaces = 3)
    private val handEngine = GestureEngine(context, GESTURE_MODEL_PATH, numHands = 2)
    private val poseEngine = PoseLandmarkEngine(context, POSE_MODEL_PATH)
    private val objectEngine = ObjectEngine(context, YOLO_PATH)
    private val faceRecognizer = FaceRecognizer(context, MOBILEFACENET_PATH)

    private val pipeline = VisionPipeline(
        faceEngine = faceEngine,
        mlkitEngine = mlkitEngine,
        handEngine = handEngine,
        poseEngine = poseEngine,
        objectEngine = objectEngine,
        faceRecognizer = faceRecognizer,
        peopleProvider = peopleProvider,
    )

    // —— 行为聚合 ——
    private val behaviorTracker = com.xbot.android.behavior.BehaviorTracker()
    private val activityTracker = com.xbot.android.behavior.ActivityTracker()

    // —— 调试统计（对标 native-prototype 右上角浮层）——
    var inferMs by mutableLongStateOf(0L)
        private set
    var fps by mutableStateOf(0f)
        private set

    /**
     * 麦克风实时音量（经验 dB，30~90 区间）。
     * 由 [voiceLevel]（PCM RMS 归一化 0..1）映射而来，非真实环境声压级，
     * 仅用于右上角浮层直观观察说话强度。随 [tickStats] 每 500ms 刷新。
     * 麦克风未采流时为 30（最低值）。
     */
    var voiceDb by mutableStateOf(30f)
        private set

    // —— 当前检测结果（驱动调试覆盖层）——
    var result by mutableStateOf(DetectionResult())
        private set
    var behaviorState by mutableStateOf(com.xbot.android.behavior.BehaviorTracker.State.ABSENT)
        private set
    var activity by mutableStateOf(com.xbot.android.behavior.ActivityTracker.Activity.NONE)
        private set

    /** 调试模式：true 显示摄像头识别覆盖层，false 显示虚拟形象网页（取自持久化设置）。 */
    val debugMode: Boolean get() = settingsStore.settings.debugMode

    var cameraStarted by mutableStateOf(false)
        private set

    /**
     * 是否处于「聆听」可视态（刚唤醒过渡 WAKING 与正式聆听 LISTENING）。
     * 驱动聆听态彩色跑马灯的淡入淡出（对标 Flutter _ListeningMarquee）。
     */
    var voiceListening by mutableStateOf(false)
        private set

    /** 端侧流式 ASR 实时识别文本（仅聆听相位显示字幕；离开聆听时清空）。 */
    var recognizedText by mutableStateOf("")
        private set

    /** 当前麦克风实时音量 0..1（聆听跑马灯呼吸用，无语音助手时为 0）。 */
    val voiceLevel: Float get() = voiceAssistant?.micLevel ?: 0f

    private var frameCounter = 0
    private var lastStatTime = System.currentTimeMillis()

    /** 惰性创建语音助手（仅当需要且有麦克风权限时才创建，避免启动时 native 库加载导致崩溃）。 */
    @Synchronized
    fun ensureVoiceAssistant(): com.xbot.android.voice.VoiceAssistant {
        return voiceAssistant ?: com.xbot.android.voice.VoiceAssistant(
            context = context,
            onStateChange = { state, robotState ->
                // 聆听跑马灯仅在唤醒过渡/聆听相位显示（与 Flutter 对齐）。
                voiceListening = state == com.xbot.android.voice.VoiceState.WAKING ||
                    state == com.xbot.android.voice.VoiceState.LISTENING
                faceWebView?.bridge?.apply {
                    voiceActive = state.isActive
                    voiceState = robotState ?: state.faceState
                }
            },
            onLevel = { lvl ->
                faceWebView?.bridge?.voiceLevel = lvl
            },
            perceptionProvider = {
                // 由当前检测结果构造 Pophie 感知上下文（表情/身份/手势/物体/场景）。
                // 说话人身份（声纹）作为人脸身份的回退，传给 Perception.build。
                com.xbot.android.voice.Perception.build(
                    result,
                    com.xbot.android.core.AppTuning.FLIP_FRONT_CAMERA_HORIZONTAL,
                    speaker = voiceAssistant?.currentSpeaker,
                )
            },
            conversationLog = conversationLog,
            voiceLog = voiceLog,
            config = settingsStore.toPophieConfig(),
            onPartialText = { recognizedText = it },
            resources = resources,
        ).also {
            // 注入声纹识别器与人物库（对话时识别说话人）。
            it.voiceRecognizer = voiceRecognizerProvider()
            it.peopleProvider = peopleProvider
            applyVoiceSettings(it)
            voiceAssistant = it
        }
    }

    @Volatile private var lastAppliedKeyword: String = "你好小白"

    /** 把当前设置应用到语音助手（创建时 + 设置页保存后调用）。 */
    fun applyVoiceSettings(va: com.xbot.android.voice.VoiceAssistant = ensureVoiceAssistant()) {
        val s = settingsStore.settings
        va.wakeWordEnabled = s.wakeWordEnabled
        va.ttsEnabled = s.ttsEnabled
        va.streamingSttEnabled = s.streamingSttEnabled
        va.voiceIdentityEnabled = s.voiceIdentityEnabled
        va.pophie.config = va.pophie.config.copy(
            baseUrl = s.baseUrl,
            voiceId = s.voiceId,
        )
        // 唤醒词变化才触发模型重建（重建较重，避免无谓重载）。
        if (s.wakeWord.isNotBlank() && s.wakeWord != lastAppliedKeyword) {
            lastAppliedKeyword = s.wakeWord
            if (va.wakeWordReady) va.setWakeKeyword(s.wakeWord)
        }
    }

    fun attachWebView(webView: FaceWebView) {
        faceWebView = webView
        webView.setup()
        // WebView 层直接捕获双击（绕过 Compose 手势被平台视图吞掉的问题）。
        webView.onDoubleTap = { onDoubleTap() }
    }

    /** 双击触发语音助手。 */
    private fun onDoubleTap() {
        android.util.Log.i("MainScreenController", "双击 → 语音助手")
        if (!settingsStore.settings.voiceEnabled) {
            android.util.Log.i("MainScreenController", "语音助手已关闭，忽略双击")
            return
        }
        try {
            val v = ensureVoiceAssistant()
            if (!v.isAvailable) {
                android.util.Log.w("MainScreenController", "语音不可用，请求麦克风权限")
                onRequestMic()
                return
            }
            if (!v.isRunning) v.start()
            if (v.isRunning && v.state.value == com.xbot.android.voice.VoiceState.IDLE) {
                android.util.Log.i("MainScreenController", "触发对话 double_tap")
                v.triggerManually("double_tap")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainScreenController", "双击处理异常: ${e.message}")
        }
    }

    /** 请求麦克风权限（双击时若不可用则调用）。 */
    fun requestMic() {
        onRequestMic()
    }

    fun startCameraPipeline() {
        if (cameraStarted) return
        if (!hasCameraPermission()) {
            onRequestCamera()
            return
        }
        val analyzer = FrameAnalyzer(
            process = { bitmap, startedAt ->
                // 后台线程：按当前识别开关跑视觉管线。
                val s = settingsStore.settings
                val r = pipeline.process(
                    bitmap, startedAt,
                    objectEnabled = s.objectEnabled,
                    faceEnabled = s.faceEnabled,
                    handEnabled = s.handEnabled,
                    identityEnabled = s.identityEnabled,
                )
                // 同时触发后台物体检测（独立节流，复用 lastObjects）。
                pipeline.runObjectDetection(bitmap, startedAt, objectEnabled = s.objectEnabled)
                r
            },
            onResult = { r, infer ->
                // 后台线程回调：行为聚合 + 更新统计 + 推 bridge。
                val now = System.currentTimeMillis()
                val behavior = behaviorTracker.update(r, now)
                val act = activityTracker.update(r, now)
                result = r
                behaviorState = behavior.state
                activity = act.activity
                inferMs = infer
                frameCounter++
                // 推前端：视觉态仅注意力态驱动（drowsy→sleepy, focused→gazing）。
                faceWebView?.bridge?.onFrame(r.face, behaviorState.apiKey)
            },
        )
        cameraManager.start(lifecycleOwner, analyzer, useFront = true)
        cameraStarted = true

        // 启用语音助手（需语音总开关 + 麦克风权限；惰性创建，避免启动时 native 加载崩溃）。
        if (settingsStore.settings.voiceEnabled && hasMicPermission()) {
            try {
                val va = ensureVoiceAssistant()
                va.markAvailable()
                va.initialize()
                scope.launch {
                    delay(2000)
                    try { if (va.isAvailable) va.start() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                // 语音初始化失败不影响视觉识别主流程。
                android.util.Log.e("MainScreenController", "语音助手启动失败: ${e.message}")
            }
        } else if (settingsStore.settings.voiceEnabled) {
            onRequestMic()
        }
    }

    /** 设置页改了语音总开关后调用：开则启动，关则停止。 */
    fun onVoiceEnabledChanged() {
        val enabled = settingsStore.settings.voiceEnabled
        if (enabled) {
            if (hasMicPermission()) {
                try {
                    val va = ensureVoiceAssistant()
                    va.markAvailable()
                    if (!va.wakeWordReady) va.initialize()
                    applyVoiceSettings(va)
                    if (!va.isRunning) va.start()
                } catch (e: Exception) {
                    android.util.Log.e("MainScreenController", "启用语音失败: ${e.message}")
                }
            } else {
                onRequestMic()
            }
        } else {
            try { voiceAssistant?.stop() } catch (_: Exception) {}
        }
    }

    fun tickStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatTime
        if (elapsed >= 1000) {
            fps = frameCounter * 1000f / elapsed
            frameCounter = 0
            lastStatTime = now
        }
        // 持久化服务端回传的 sessionId（延续多轮会话记忆）。
        voiceAssistant?.pophie?.config?.sessionId?.let { sid ->
            if (sid != settingsStore.settings.sessionId) settingsStore.persistSessionId(sid)
        }
        // 刷新右上角麦克风音量（经验 dB）。
        voiceDb = levelToDb(voiceLevel)
    }

    /**
     * PCM RMS 归一化值（0..1）→ 经验 dB（30~90）。
     *
     * 校准依据（降噪后实测 2026-07-01）：开启 GTCRN 降噪后，安静办公环境底噪 level≈4e-4~2e-3
     * （EMA 平滑后，比降噪前的 0.022~0.039 低约一个数量级）。取安静代表值 0.001 锚定 42dB：
     * - 极安静（level≈1e-4）→ ≈30 dB（下限）
     * - 安静（level≈0.001）→ ≈42 dB
     * - 轻微声响（level≈0.01）→ ≈55 dB
     * - 正常说话（level≈0.03~0.05）→ ≈62~65 dB
     * - 大声/贴近（level≈0.2+）→ ≈73 dB+
     *
     * 降噪后人声动态范围比降噪前舒展，故斜率从 28.6 降至 13.3（避免说话过早顶到 90）。
     * 非真实 SPL（无硬件校准），仅作直观参考。麦克风未采流（level=0）→ 30 dB。
     * 若实测整体偏高/偏低，调 [DB_OFFSET]（每 +1 全体 +1dB）；动态范围过窄/过宽调 [DB_SLOPE]。
     */
    private fun levelToDb(level: Float): Float {
        if (level <= 0f) return 30f
        val clamped = level.coerceIn(1e-5f, 1f)
        val db = DB_OFFSET + DB_SLOPE * kotlin.math.log10(clamped)
        return db.coerceIn(30f, 90f)
    }

    fun release() {
        try { voiceAssistant?.release() } catch (_: Exception) {}
        try { cameraManager.release() } catch (_: Exception) {}
        try { faceEngine.close() } catch (_: Exception) {}
        try { mlkitEngine.close() } catch (_: Exception) {}
        try { handEngine.close() } catch (_: Exception) {}
        try { poseEngine.close() } catch (_: Exception) {}
        try { objectEngine.close() } catch (_: Exception) {}
        try { faceRecognizer.close() } catch (_: Exception) {}
        try { behaviorTracker.reset() } catch (_: Exception) {}
        try { activityTracker.reset() } catch (_: Exception) {}
    }
}

@Composable
fun rememberMainScreenController(
    lifecycleOwner: LifecycleOwner,
    hasCameraPermission: () -> Boolean,
    onRequestCamera: () -> Unit,
    hasMicPermission: () -> Boolean,
    onRequestMic: () -> Unit,
    settingsStore: SettingsStore,
    peopleProvider: () -> List<Person> = { emptyList() },
    voiceRecognizerProvider: () -> com.xbot.android.voice.VoiceRecognizer? = { null },
    resources: com.xbot.android.core.ResourceManager? = null,
): MainScreenController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember {
        // 传 Activity context（非 applicationContext）：CameraX 的 bindToLifecycle 与
        // WindowManager 取 Display 都需要视觉 Context；applicationContext 不关联 Display，
        // 会导致相机绑定抛 "obtain display from a Context not associated with one"。
        MainScreenController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            hasCameraPermission = hasCameraPermission,
            onRequestCamera = onRequestCamera,
            hasMicPermission = hasMicPermission,
            onRequestMic = onRequestMic,
            settingsStore = settingsStore,
            peopleProvider = peopleProvider,
            voiceRecognizerProvider = voiceRecognizerProvider,
            resources = resources,
        )
    }
    LaunchedEffect(Unit) {
        if (hasCameraPermission()) controller.startCameraPipeline()
        else onRequestCamera()
    }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
    return controller
}
