package com.xbot.android.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * 相机管理：CameraX ImageAnalysis 绑定到**后台 executor**（换原生的核心动机）。
 *
 * 对应 Flutter 的 CameraController + 按需采样。原生方案的根本差异：
 * - 相机帧投递到绑定时指定的后台 executor（[analysisExecutor]），全程不碰主线程。
 * - 推理在同一后台线程完成（见 [FrameAnalyzer]），不经过任何 platform channel。
 * - STRATEGY_KEEP_ONLY_LATEST 自动丢帧，无堆积，**无 CameraX session 重建**。
 *
 * 故不再需要 Flutter 的「按需采样」补丁（300ms 定时开/关流）——恢复成持续帧流。
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    /** 后台单线程 executor：相机帧投递 + 推理都在这里。 */
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xbot-camera-analysis").apply { isDaemon = true }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analyzer: FrameAnalyzer? = null

    /** 是否使用前置摄像头（陪伴场景对着用户，默认前置）。 */
    var useFrontCamera: Boolean = true
        private set

    /** 当前是否已绑定取流。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 启动相机管线：绑定 ImageAnalysis 到后台 executor。
     *
     * @param lifecycleOwner CameraX 要求绑定到生命周期
     * @param analyzer 帧分析器（在后台线程被回调）
     * @param useFront 是否前置摄像头
     */
    @SuppressLint("RestrictedApi")
    fun start(
        lifecycleOwner: LifecycleOwner,
        analyzer: FrameAnalyzer,
        useFront: Boolean = true,
    ) {
        this.useFrontCamera = useFront
        this.analyzer = analyzer
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    // 自动丢弃忙时的帧，无堆积。
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 输出旋转：CameraX 据此把帧摆正，FrameAnalyzer 拿到的 bitmap 已 upright。
                    // 横屏 app：始终是两个横屏方向之一。
                    .setTargetRotation(context.display?.rotation ?: 0)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    // 转发给 FrameAnalyzer，它在同一后台线程处理这一帧。
                    analyzer.analyze(image)
                }
                imageAnalysis = analysis

                val selector = if (useFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                isRunning = true
                Log.i(TAG, "相机管线已启动（后台线程推理，${if (useFront) "前置" else "后置"}）")
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 停止取流。 */
    fun stop() {
        cameraProvider?.unbindAll()
        isRunning = false
    }

    /** 切换前后摄像头。 */
    fun toggle(lifecycleOwner: LifecycleOwner) {
        val a = analyzer ?: return
        start(lifecycleOwner, a, useFront = !useFrontCamera)
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }
}
