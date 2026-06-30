package com.xbot.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.xbot.android.model.GestureType
import com.xbot.android.model.Handedness
import com.xbot.android.model.HandOverlay
import com.xbot.android.model.Offset

/**
 * 手势检测器:MediaPipe HandLandmarker,21 点 + 手势分类。
 * 在后台线程创建并推理。
 */
class HandDetector(
    context: Context,
    modelPath: String = "hand_landmarker.task"
) {
    companion object {
        private const val TAG = "HandDetector"
    }

    private val landmarker: HandLandmarker? = try {
        HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelPath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "HandLandmarker 初始化失败: ${e.message}")
        null
    }

    val isInitialized: Boolean get() = landmarker != null

    fun detect(bitmap: Bitmap): List<HandOverlay> {
        val lm = landmarker ?: return emptyList()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = try {
            lm.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            return emptyList()
        }

        val handLandmarksList = result.landmarks()
        val handednessList = result.handedness()

        return handLandmarksList.mapIndexed { index, hand ->
            val ordered = MutableList(21) { Offset(0f, 0f) }
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f

            for ((idx, lm) in hand.withIndex()) {
                val nx = lm.x().coerceIn(0f, 1f)
                val ny = lm.y().coerceIn(0f, 1f)
                if (idx in 0..20) {
                    ordered[idx] = Offset(nx, ny)
                }
                if (nx < minX) minX = nx
                if (ny < minY) minY = ny
                if (nx > maxX) maxX = nx
                if (ny > maxY) maxY = ny
            }

            val gesture = classifyGesture(ordered)
            val handedness = if (index < handednessList.size) {
                val categories = handednessList[index]
                if (categories.isNotEmpty()) {
                    if (categories[0].categoryName() == "Left") Handedness.LEFT
                    else Handedness.RIGHT
                } else null
            } else null

            val gestureConf = if (index < handednessList.size && handednessList[index].isNotEmpty()) {
                handednessList[index][0].score()
            } else 0f

            HandOverlay(
                landmarks = ordered,
                boundingBox = android.graphics.RectF(minX, minY, maxX, maxY),
                handedness = handedness,
                gesture = gesture,
                gestureConfidence = gestureConf
            )
        }
    }

    private fun classifyGesture(landmarks: List<Offset>): GestureType? {
        if (landmarks.size < 21) return null

        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val middleTip = landmarks[12]
        val ringTip = landmarks[16]
        val pinkyTip = landmarks[20]

        val thumbUp = thumbTip.y < wrist.y - 0.15f
        val indexUp = indexTip.y < wrist.y - 0.15f
        val middleUp = middleTip.y < wrist.y - 0.15f
        val ringUp = ringTip.y < wrist.y - 0.15f
        val pinkyUp = pinkyTip.y < wrist.y - 0.15f
        val fingersUp = listOf(indexUp, middleUp, ringUp, pinkyUp).count { it }

        return when {
            thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> GestureType.THUMB_UP
            indexUp && middleUp && !ringUp && !pinkyUp -> GestureType.VICTORY
            fingersUp >= 4 -> GestureType.OPEN_PALM
            fingersUp == 0 && !thumbUp -> GestureType.CLOSED_FIST
            indexUp && !middleUp && !ringUp && !pinkyUp -> GestureType.POINTING_UP
            thumbUp && indexUp && !middleUp && !ringUp && pinkyUp -> GestureType.I_LOVE_YOU
            else -> null
        }
    }

    fun close() {
        landmarker?.close()
    }
}
