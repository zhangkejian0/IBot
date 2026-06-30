package com.xbot.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.xbot.android.model.Offset
import com.xbot.android.model.PoseOverlay

/**
 * 姿态检测器:MediaPipe PoseLandmarker,33 点骨骼。
 * 在后台线程创建并推理。
 */
class PoseDetector(
    context: Context,
    modelPath: String = "pose_landmarker_lite.task"
) {
    companion object {
        private const val TAG = "PoseDetector"
    }

    private val landmarker: PoseLandmarker? = try {
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "PoseLandmarker 初始化失败: ${e.message}")
        null
    }

    val isInitialized: Boolean get() = landmarker != null

    fun detect(bitmap: Bitmap): PoseOverlay? {
        val lm = landmarker ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = try {
            lm.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            return null
        }

        val poseList = result.landmarks()
        if (poseList.isEmpty()) return null

        val pose = poseList[0]
        val ordered = MutableList(33) { Offset(0f, 0f) }
        val vis = MutableList(33) { 0f }
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f

        for ((idx, lm) in pose.withIndex()) {
            if (idx >= 33) break
            val x = lm.x().coerceIn(0f, 1f)
            val y = lm.y().coerceIn(0f, 1f)
            ordered[idx] = Offset(x, y)
            vis[idx] = (lm.visibility() as? Float ?: 0f).coerceIn(0f, 1f)
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        return PoseOverlay(
            landmarks = ordered,
            visibilities = vis,
            boundingBox = android.graphics.RectF(minX, minY, maxX, maxY)
        )
    }

    fun close() {
        landmarker?.close()
    }
}
