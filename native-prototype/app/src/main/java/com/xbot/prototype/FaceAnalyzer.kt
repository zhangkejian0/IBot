package com.xbot.prototype

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * 人脸分析器:CameraX 的 ImageAnalysis.Analyzer 实现。
 *
 * **核心要点(换原生的全部理由都在这里)**:
 * - CameraX 把 [ImageProxy] 投递到绑定时指定的 **后台 executor**(见 MainActivity),
 *   本类的 [analyze] 全程在后台线程执行,不碰 Android 主线程。
 * - MediaPipe [FaceLandmarker] 也在该后台线程创建并推理(它要求初始化与推理同线程)。
 * - 整个「YUV→Bitmap→推理」管线完全留在原生后台线程,**不经过任何 platform channel**,
 *   不触发 CameraX session 重建。这是与 Flutter 方案的根本差异。
 *
 * 推理完成后,通过 [onResult] 把轻量结果(归一化人脸中心 + 真实推理耗时)回传——
 * 主线程只拿到几个数字,用于驱动 WebView 表情注视,负载极低。
 *
 * 原型用 IMAGE(同步)模式而非 LIVE_STREAM:同步 [detect] 直接返回结果与耗时,
 * 便于精确量化「原生后台线程单帧推理多少 ms」这个核心对比指标。
 *
 * @param modelPath assets 中的 .task 模型路径(face_landmarker.task)
 * @param onResult  推理结果回调(**在后台线程触发**,调用方需自行切到主线程)
 */
class FaceAnalyzer(
    context: Context,
    modelPath: String,
    private val onResult: (faceCenterX: Float, faceCenterY: Float, faceCount: Int, inferMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
    }

    private val faceLandmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        // CPU 委托:兼容性最好。原型优先跑通,GPU 可后续加。
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "FaceLandmarker 初始化失败(模型缺失?): ${e.message}")
        null
    }

    /**
     * CameraX 每帧回调(**后台线程**)。把 [image] 转成 MediaPipe 的 MPImage,
     * 同步送入推理,测真实耗时,回传结果。
     */
    override fun analyze(image: ImageProxy) {
        val landmarker = faceLandmarker
        if (landmarker == null) {
            image.close()
            return
        }
        val start = System.currentTimeMillis()
        try {
            val mpImage = image.toMPImage()
            // IMAGE 模式:detect 同步返回结果。整个推理在当前后台线程完成,不碰主线程。
            val result = landmarker.detect(mpImage)
            val inferMs = System.currentTimeMillis() - start
            handleResult(result, inferMs)
        } catch (e: Exception) {
            Log.e(TAG, "analyze 异常: ${e.message}")
        } finally {
            // analyze 必须 close image,否则 CameraX 不会投递下一帧。
            image.close()
        }
    }

    /** 提取人脸中心(归一化 0..1)+ 真实推理耗时,回传给调用方。 */
    private fun handleResult(result: FaceLandmarkerResult, inferMs: Long) {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            onResult(0.5f, 0.5f, 0, inferMs)
            return
        }
        // 取第一张脸的所有点,算包围盒中心(归一化 0..1)。
        val landmarks = faces[0]
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (lm in landmarks) {
            val x = lm.x(); val y = lm.y()
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        onResult(cx, cy, faces.size, inferMs)
    }

    /** ImageProxy → MPImage(Bitmap 路径)。原型用 toBitmap,CameraX 自动处理旋转。 */
    private fun ImageProxy.toMPImage(): MPImage {
        val bitmap = this.toBitmap()
        // toBitmap 已按 targetRotation 摆正,直接包装。
        return BitmapImageBuilder(bitmap).build()
    }

    fun close() {
        faceLandmarker?.close()
    }
}
