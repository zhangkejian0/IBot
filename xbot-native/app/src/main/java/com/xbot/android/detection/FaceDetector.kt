package com.xbot.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.xbot.android.model.ExpressionResult
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.Offset

/**
 * 人脸检测器:MediaPipe FaceLandmarker,478 点 + 52 blendshapes + gaze。
 * 直接翻译自 Flutter 的 FaceEngine。
 *
 * 在后台线程创建并推理(它要求初始化与推理同线程)。
 * 使用 IMAGE(同步)模式,直接返回结果与耗时。
 */
class FaceDetector(
    context: Context,
    modelPath: String = "face_landmarker.task"
) {
    companion object {
        private const val TAG = "FaceDetector"
    }

    private val expressionClassifier = ExpressionClassifier()

    private val landmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "FaceLandmarker 初始化失败: ${e.message}")
        null
    }

    val isInitialized: Boolean get() = landmarker != null

    /**
     * 检测一帧人脸。在后台线程调用。
     * @param bitmap 摆正后的 Bitmap
     * @return FaceOverlay? 检测到的人脸(含 478 点+blendshapes+gaze),null=未检测到
     */
    fun detect(bitmap: Bitmap): FaceOverlay? {
        val lm = landmarker ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: FaceLandmarkerResult
        try {
            result = lm.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            return null
        }

        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null

        val face = faces[0] // 第一张脸(单脸模式)
        val points = mutableListOf<Offset>()
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f

        for (lm in face) {
            val x = lm.x().coerceIn(0f, 1f)
            val y = lm.y().coerceIn(0f, 1f)
            points.add(Offset(x, y))
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        // blendshapes → 表情
        val blendshapes = mutableMapOf<String, Float>()
        val bsResult = result.faceBlendshapes()
        if (bsResult.isPresent && bsResult.get().isNotEmpty()) {
            for (cat in bsResult.get()[0]) {
                blendshapes[cat.categoryName()] = cat.score()
            }
        }
        val expression = expressionClassifier.classify(blendshapes)

        // 注视方向:从 faceLandmarks 计算(简化版)
        // 真实实现应从 transformation matrix 或 eye landmarks 计算
        val leftPupil = points.getOrNull(468) // 左瞳孔中心
        val rightPupil = points.getOrNull(473) // 右瞳孔中心
        val leftEyeInner = points.getOrNull(133)
        val leftEyeOuter = points.getOrNull(33)
        val rightEyeInner = points.getOrNull(362)
        val rightEyeOuter = points.getOrElse(263) { Offset(0f, 0f) }

        var gazeX = 0f
        var gazeY = 0f
        if (leftPupil != null && leftEyeInner != null && leftEyeOuter != null &&
            rightPupil != null && rightEyeInner != null
        ) {
            val eyeWidth = (leftEyeOuter.x - leftEyeInner.x).coerceAtLeast(0.001f)
            val leftGaze = (leftPupil.x - leftEyeInner.x) / eyeWidth * 2f - 1f
            val rightEyeWidth = (rightEyeOuter.x - rightEyeInner.x).coerceAtLeast(0.001f)
            val rightGaze = (rightPupil.x - rightEyeInner.x) / rightEyeWidth * 2f - 1f
            gazeX = (leftGaze + rightGaze) / 2f
            gazeY = ((leftPupil.y + rightPupil.y) / 2f - 0.5f) * 2f
        }

        // 眼睛闭合
        val blinkL = (blendshapes["eyeBlinkLeft"] ?: 0f).coerceIn(0f, 1f)
        val blinkR = (blendshapes["eyeBlinkRight"] ?: 0f).coerceIn(0f, 1f)
        val eyeBlink = (blinkL + blinkR) / 2f

        // 嘴部张开
        val mouthOpenness = (blendshapes["jawOpen"] ?: 0f).coerceIn(0f, 1f)

        return FaceOverlay(
            landmarks = points,
            boundingBox = android.graphics.RectF(minX, minY, maxX, maxY),
            expression = expression,
            gazeX = gazeX,
            gazeY = gazeY,
            eyeBlink = eyeBlink,
            mouthOpenness = mouthOpenness
        )
    }

    fun close() {
        landmarker?.close()
    }
}
