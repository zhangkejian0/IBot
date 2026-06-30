package com.xbot.android.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.xbot.android.detection.FaceDetector
import com.xbot.android.detection.FaceRecognizer
import com.xbot.android.detection.HandDetector
import com.xbot.android.detection.PoseDetector
import com.xbot.android.model.DetectionResult
import java.util.concurrent.Executors

/**
 * 相机管理器:CameraX 绑定 + ImageAnalysis 后台线程取流 + Preview 预览。
 *
 * 同时绑定两个 use case:
 * - Preview:显示在 PreviewView 上(调试模式用)
 * - ImageAnalysis:后台 executor 推理(始终运行)
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    // 检测引擎
    private lateinit var faceDetector: FaceDetector
    private lateinit var handDetector: HandDetector
    private lateinit var poseDetector: PoseDetector
    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var frameAnalyzer: FrameAnalyzer

    /** 摄像头预览 View,供调试模式显示 */
    var previewView: PreviewView? = null
        private set

    /**
     * 初始化并启动相机管线。
     * @param onResult 每帧检测结果回调(在后台线程触发)
     * @param onInitialized 初始化完成回调(在主线程)
     */
    fun start(
        onResult: (DetectionResult, Long) -> Unit,
        onInitialized: (() -> Unit)? = null
    ) {
        // 创建 PreviewView(调试模式用)
        previewView = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        // 先在后台线程初始化检测引擎,完成后再绑定相机。
        // 避免竞态:bindCamera 时 frameAnalyzer 未就绪导致 setAnalyzer 被跳过。
        analysisExecutor.execute {
            try {
                faceDetector = FaceDetector(context, "models/face_landmarker.task")
                handDetector = HandDetector(context, "models/hand_landmarker.task")
                poseDetector = PoseDetector(context, "models/pose_landmarker_lite.task")
                faceRecognizer = FaceRecognizer(context, "models/mobilefacenet.tflite")
                faceRecognizer.initialize()

                frameAnalyzer = FrameAnalyzer(
                    faceDetector = faceDetector,
                    handDetector = handDetector,
                    poseDetector = poseDetector,
                    faceRecognizer = faceRecognizer,
                    onResult = onResult
                )

                Log.i(TAG, "检测引擎初始化完成: face=${faceDetector.isInitialized}, hand=${handDetector.isInitialized}, pose=${poseDetector.isInitialized}")

                // 引擎就绪后,在主线程绑定相机
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    bindCamera()
                    onInitialized?.invoke()
                    Log.i(TAG, "相机管线已启动(后台线程推理)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "检测引擎初始化失败: ${e.message}")
                // 即使引擎失败,也绑定相机(至少有预览)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    bindCamera()
                    onInitialized?.invoke()
                }
            }
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            doBind()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun doBind() {
        val provider = cameraProvider ?: return
        val pv = previewView ?: return

        // Preview use case:显示摄像头画面
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = pv.surfaceProvider
        }

        // ImageAnalysis use case:后台推理
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        if (::frameAnalyzer.isInitialized) {
            imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer)
            Log.i(TAG, "FrameAnalyzer 已绑定到 ImageAnalysis")
        } else {
            Log.w(TAG, "FrameAnalyzer 未就绪,跳过 setAnalyzer")
        }

        val selector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            Log.i(TAG, "CameraX 绑定完成(Preview + ImageAnalysis)")
        } catch (e: Exception) {
            Log.e(TAG, "相机绑定失败: ${e.message}")
        }
    }

    /** 更新人物库(供 FrameAnalyzer 身份识别用) */
    fun updatePeople(people: List<com.xbot.android.model.Person>) {
        if (::frameAnalyzer.isInitialized) {
            frameAnalyzer.people = people
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        if (::faceDetector.isInitialized) faceDetector.close()
        if (::handDetector.isInitialized) handDetector.close()
        if (::poseDetector.isInitialized) poseDetector.close()
        if (::faceRecognizer.isInitialized) faceRecognizer.close()
    }
}
