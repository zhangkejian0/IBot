package com.xbot.android.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.xbot.android.model.ExpressionResult
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.NormalizedPoint

/**
 * 人脸引擎：封装 MediaPipe Face Landmarker（tasks-vision Java API）。
 *
 * 对应 Flutter 的 lib/services/face_engine.dart（kwon_mediapipe_landmarker 插件）。
 *
 * 输出 478 个关键点 + 52 blendshapes，交由 [ExpressionClassifier] 得到 7 种表情之一，
 * 并从 blendshapes 派生 gaze / eyeBlink / mouthOpenness。
 *
 * **线程模型**：FaceLandmarker 要求初始化与推理在同一线程。本引擎由
 * [com.xbot.android.camera.CameraManager] 绑定到 CameraX 的后台 executor，
 * 全程在后台线程创建并推理，**不碰 Android 主线程**。
 *
 * @param modelPath assets 中的 .task 模型路径（face_landmarker.task）
 */
class FaceLandmarkEngine(
    context: Context,
    modelPath: String,
    private val expressionClassifier: ExpressionClassifier = ExpressionClassifier(),
) {
    companion object {
        private const val TAG = "FaceLandmarkEngine"
    }

    val faceLandmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        // CPU 委托：兼容性最好（与 prototype 一致），GPU 可后续加。
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                // 开启 blendshapes 输出：表情分类 + gaze + eyeBlink + mouthOpenness 都依赖它。
                .setOutputFaceBlendshapes(true)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "FaceLandmarker 初始化失败（模型缺失？）: ${e.message}")
        null
    }

    val isReady: Boolean get() = faceLandmarker != null

    /**
     * 对一张摆正后的 [bitmap] 做推理（**后台线程调用**）。
     *
     * 调用方负责把 CameraX ImageProxy 摆正成 upright bitmap（CameraX toBitmap 已按
     * targetRotation 摆正）。返回主脸 [FaceOverlay]，无人脸返回 null。
     */
    fun detect(bitmap: Bitmap): FaceOverlay? {
        val landmarker = faceLandmarker ?: return null
        return try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            handleResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            null
        }
    }

    /** 提取主脸：478 点 + blendshapes → 表情 + gaze + eyeBlink + mouthOpenness。 */
    private fun handleResult(result: FaceLandmarkerResult): FaceOverlay? {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null

        val landmarks0 = faces[0]
        val points = ArrayList<NormalizedPoint>(landmarks0.size)
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (lm in landmarks0) {
            val x = lm.x().coerceIn(0f, 1f)
            val y = lm.y().coerceIn(0f, 1f)
            points.add(NormalizedPoint(x, y))
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val boundingBox = RectF(minX, minY, maxX, maxY)

        // blendshapes：faceBlendshapes() 返回 Optional<List<List<Category>>>（外层=每张脸，
        // 内层=该脸的 52 个 blendshape 类别）。numFaces=1，取 face 0 的类别列表。
        val blendshapes: Map<String, Float> = result.faceBlendshapes().orElse(null)?.let { perFace ->
            // perFace: List<List<Category>>，取第一张脸的类别列表。
            if (perFace.isEmpty()) null else perFace[0]
        }?.let { categories ->
            val map = HashMap<String, Float>(categories.size)
            for (c in categories) {
                map[c.categoryName()] = c.score()
            }
            map
        } ?: emptyMap()

        val expression = expressionClassifier.classify(blendshapes)
        val gaze = GazeUtils.gazeFromBlendshapes(blendshapes)
        val eyeBlink = GazeUtils.eyeBlinkFromBlendshapes(blendshapes)
        val mouthOpenness = (blendshapes["jawOpen"] ?: 0f).coerceIn(0f, 1f)

        return FaceOverlay(
            landmarks = points,
            boundingBox = boundingBox,
            expression = expression,
            gazeX = gaze[0],
            gazeY = gaze[1],
            eyeBlink = eyeBlink,
            mouthOpenness = mouthOpenness,
        )
    }

    fun close() {
        faceLandmarker?.close()
    }
}
