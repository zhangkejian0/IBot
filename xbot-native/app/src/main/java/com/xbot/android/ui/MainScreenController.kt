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
import com.xbot.android.vision.FaceLandmarkEngine
import com.xbot.android.vision.FaceRecognizer
import com.xbot.android.vision.GestureEngine
import com.xbot.android.vision.MlKitFaceEngine
import com.xbot.android.vision.ObjectEngine
import com.xbot.android.vision.PoseLandmarkEngine
import com.xbot.android.vision.VisionPipeline
import com.xbot.android.webview.FaceWebView
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
    peopleProvider: () -> List<Person> = { emptyList() },
) {
    companion object {
        private const val FACE_MODEL_PATH = "face_landmarker.task"
        private const val POSE_MODEL_PATH = "pose_landmarker_lite.task"
        private const val GESTURE_MODEL_PATH = "gesture_recognizer.task"
        private const val MOBILEFACENET_PATH = "mobilefacenet.tflite"
        private const val YOLO_PATH = "yolo26n_int8.tflite"
    }

    var faceWebView: FaceWebView? = null
        private set

    /** 语音助手（唤醒 + 流式 STT + Pophie + 流式 TTS）。 */
    val voiceAssistant = com.xbot.android.voice.VoiceAssistant(
        context = context,
        onStateChange = { state, robotState ->
            // 语音状态/robotState → FaceBridge（语音优先级最高，接管表情 + 嘴部）。
            faceWebView?.bridge?.apply {
                voiceActive = state.isActive
                voiceState = robotState ?: state.faceState
            }
        },
        onLevel = { lvl ->
            // 语音音量（listening 用麦音量，speaking 用 TTS 音量）→ FaceBridge 嘴部。
            faceWebView?.bridge?.voiceLevel = lvl
        },
    )

    private val cameraManager = CameraManager(context)

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

    // —— 当前检测结果（驱动调试覆盖层）——
    var result by mutableStateOf(DetectionResult())
        private set
    var behaviorState by mutableStateOf(com.xbot.android.behavior.BehaviorTracker.State.ABSENT)
        private set
    var activity by mutableStateOf(com.xbot.android.behavior.ActivityTracker.Activity.NONE)
        private set

    /** 调试模式：true 显示摄像头识别覆盖层，false 显示虚拟形象网页（默认 false）。 */
    var debugMode by mutableStateOf(false)

    var cameraStarted by mutableStateOf(false)
        private set

    private var frameCounter = 0
    private var lastStatTime = System.currentTimeMillis()

    fun attachWebView(webView: FaceWebView) {
        faceWebView = webView
        webView.setup()
    }

    fun startCameraPipeline() {
        if (cameraStarted) return
        if (!hasCameraPermission()) {
            onRequestCamera()
            return
        }
        val analyzer = FrameAnalyzer(
            process = { bitmap, startedAt ->
                // 后台线程：跑完整视觉管线。
                val r = pipeline.process(bitmap, startedAt, objectEnabled = true)
                // 同时触发后台物体检测（独立节流，复用 lastObjects）。
                pipeline.runObjectDetection(bitmap, startedAt, objectEnabled = true)
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
    }

    fun tickStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatTime
        if (elapsed >= 1000) {
            fps = frameCounter * 1000f / elapsed
            frameCounter = 0
            lastStatTime = now
        }
    }

    fun release() {
        cameraManager.release()
        faceEngine.close()
        mlkitEngine.close()
        handEngine.close()
        poseEngine.close()
        objectEngine.close()
        faceRecognizer.close()
        behaviorTracker.reset()
        activityTracker.reset()
    }
}

@Composable
fun rememberMainScreenController(
    lifecycleOwner: LifecycleOwner,
    hasCameraPermission: () -> Boolean,
    onRequestCamera: () -> Unit,
    peopleProvider: () -> List<Person> = { emptyList() },
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
            peopleProvider = peopleProvider,
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
