package com.xbot.android.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.xbot.android.model.NormalizedPoint
import com.xbot.android.model.PoseOverlay

/**
 * 人体姿态引擎：封装 MediaPipe Pose Landmarker（33 点）。
 *
 * 对应 Flutter face_engine.dart 的 pose 部分（kwon_mediapipe_landmarker 插件同时开 face+pose）。
 * 原生用独立的 PoseLandmarker（与 FaceLandmarkEngine 分开推理，都在后台线程）。
 *
 * 输出 33 个归一化关键点 + visibility + 外接框，供 ActivityTracker 做几何判定
 *（举手/托腮/坐姿不良/喝水姿态）。
 *
 * @param modelPath assets 中的 .task 模型路径（pose_landmarker_lite.task）
 */
class PoseLandmarkEngine(
    context: Context,
    modelPath: String,
) {
    companion object {
        private const val TAG = "PoseLandmarkEngine"
    }

    private val poseLandmarker: PoseLandmarker? = try {
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(modelPath).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "PoseLandmarker 初始化失败（模型缺失？）: ${e.message}")
        null
    }

    val isReady: Boolean get() = poseLandmarker != null

    /**
     * 对一张摆正后的 [bitmap] 做推理（**后台线程调用**）。返回至多 1 个人体姿态，无人返回 null。
     */
    fun detect(bitmap: Bitmap): PoseOverlay? {
        val landmarker = poseLandmarker ?: return null
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            handleResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            null
        }
    }

    private fun handleResult(result: PoseLandmarkerResult): PoseOverlay? {
        val poses = result.landmarks()
        if (poses.isEmpty()) return null
        val landmarks0 = poses[0] // List<NormalizedLandmark>，33 点
        val ordered = ArrayList<NormalizedPoint>(33)
        val vis = ArrayList<Float>(33)
        var loX = 1f; var loY = 1f; var hiX = 0f; var hiY = 0f
        var any = false
        for (lm in landmarks0) {
            val x = lm.x().coerceIn(0f, 1f)
            val y = lm.y().coerceIn(0f, 1f)
            ordered.add(NormalizedPoint(x, y))
            vis.add(lm.visibility().orElse(0f).coerceIn(0f, 1f))
            any = true
            if (x < loX) loX = x
            if (y < loY) loY = y
            if (x > hiX) hiX = x
            if (y > hiY) hiY = y
        }
        if (!any) return null
        return PoseOverlay(
            landmarks = ordered,
            visibilities = vis,
            boundingBox = RectF(loX, loY, hiX, hiY),
        )
    }

    fun close() {
        poseLandmarker?.close()
    }
}
