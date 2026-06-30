package com.xbot.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.xbot.android.camera.CameraManager
import com.xbot.android.camera.FrameAnalyzer
import com.xbot.android.model.FaceOverlay
import com.xbot.android.vision.FaceLandmarkEngine
import com.xbot.android.webview.FaceWebView

/**
 * 阶段 0 主界面控制器：相机管线 + FaceLandmarkEngine + FaceWebView 桥接。
 *
 * 在 Compose 中通过 [rememberMainScreenController] 获取单例，由 [MainScreen] 消费。
 * 持有调试统计（推理耗时/帧率/人脸数）供右上角浮层展示（对标 native-prototype）。
 */
class MainScreenController(
    val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val hasCameraPermission: () -> Boolean,
    private val onRequestCamera: () -> Unit,
) {
    companion object {
        private const val FACE_MODEL_PATH = "face_landmarker.task"
    }

    /** 虚拟形象 WebView（由 MainScreen 创建并注入）。 */
    var faceWebView: FaceWebView? = null
        private set

    private val cameraManager = CameraManager(context)
    private var faceEngine: FaceLandmarkEngine? = null

    // —— 调试统计（对标 native-prototype 的右上角浮层）——
    var inferMs by mutableLongStateOf(0L)
        private set
    var faceCount by mutableIntStateOf(0)
        private set
    var faceCenterX by mutableFloatStateOf(0.5f)
        private set
    var faceCenterY by mutableFloatStateOf(0.5f)
        private set
    var fps by mutableFloatStateOf(0f)
        private set

    // 帧率统计。
    private var frameCounter = 0
    private var lastStatTime = System.currentTimeMillis()

    /** 是否已绑定相机管线。 */
    var cameraStarted by mutableStateOf(false)
        private set

    /** 由 MainScreen 注入它创建好的 FaceWebView（含 bridge）。 */
    fun attachWebView(webView: FaceWebView) {
        faceWebView = webView
        webView.setup()
    }

    /** 启动相机管线（权限通过后调用）。 */
    fun startCameraPipeline() {
        if (cameraStarted) return
        if (!hasCameraPermission()) {
            onRequestCamera()
            return
        }
        val engine = FaceLandmarkEngine(context, FACE_MODEL_PATH)
        if (!engine.isReady) {
            // 模型加载失败，不启动。
            engine.close()
            return
        }
        faceEngine = engine

        val analyzer = FrameAnalyzer(
            detect = { bitmap -> engine.detect(bitmap) },
            onResult = { face, infer ->
                // 后台线程回调：更新统计 + 推给 bridge（bridge 内部切主线程）。
                frameCounter++
                inferMs = infer
                faceCount = if (face != null) 1 else 0
                if (face != null) {
                    faceCenterX = face.boundingBox.centerX()
                    faceCenterY = face.boundingBox.centerY()
                }
                // 推送前端：阶段 0 behaviorState 传 null（注意力态阶段 1 接入）。
                faceWebView?.bridge?.onFrame(face, behaviorState = null)
            },
        )
        cameraManager.start(lifecycleOwner, analyzer, useFront = true)
        cameraStarted = true
    }

    /** 刷新帧率统计（由 MainScreen 定时调用）。 */
    fun tickStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatTime
        if (elapsed >= 1000) {
            fps = frameCounter * 1000f / elapsed
            frameCounter = 0
            lastStatTime = now
        }
    }

    /** 释放资源。 */
    fun release() {
        cameraManager.release()
        faceEngine?.close()
        faceEngine = null
    }
}

/**
 * 在 Compose 中记忆一个 [MainScreenController]。
 * @param onRequestCamera 权限请求回调（permissionLauncher.launch）
 */
@Composable
fun rememberMainScreenController(
    lifecycleOwner: LifecycleOwner,
    hasCameraPermission: () -> Boolean,
    onRequestCamera: () -> Unit,
): MainScreenController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember {
        MainScreenController(
            context = context.applicationContext,
            lifecycleOwner = lifecycleOwner,
            hasCameraPermission = hasCameraPermission,
            onRequestCamera = onRequestCamera,
        )
    }

    // 进入时若已有权限则自动启动；否则请求。
    LaunchedEffect(Unit) {
        if (hasCameraPermission()) controller.startCameraPipeline()
        else onRequestCamera()
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    return controller
}
