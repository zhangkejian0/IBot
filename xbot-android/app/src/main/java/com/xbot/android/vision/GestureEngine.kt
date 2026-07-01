package com.xbot.android.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.xbot.android.model.HandGesture
import com.xbot.android.model.HandOverlay
import com.xbot.android.model.Handedness
import com.xbot.android.model.NormalizedPoint

/**
 * 手势引擎：封装 MediaPipe GestureRecognizer（一次调用同时得 21 点 + 左右手 + 7 类手势）。
 *
 * 对应 Flutter 的 hand_engine.dart（hand_detection 插件：21 点 + 手势分类）。
 *
 * GestureRecognizer 内部已含 hand_landmarker + hand_gesture_recognizer 两个子模型
 *（见 gesture_recognizer.task 解包），输出 21 点骨架 + handedness + gestures。
 *
 * 手势类别映射：MediaPipe 默认 7 类（None/Closed_Fist/Open_Palm/Pointing_Up/Thumb_Up/
 * Thumb_Down/Victory/I_Love_You）→ 本项目 [HandGesture]。
 *
 * @param modelPath assets 中的 .task 模型路径（gesture_recognizer.task）
 */
class GestureEngine(
    context: Context,
    modelPath: String,
    private val numHands: Int = 2,
) {
    companion object {
        private const val TAG = "GestureEngine"
    }

    private val gestureRecognizer: GestureRecognizer? = try {
        GestureRecognizer.createFromOptions(
            context,
            GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(modelPath).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(numHands)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "GestureRecognizer 初始化失败（模型缺失？）: ${e.message}")
        null
    }

    val isReady: Boolean get() = gestureRecognizer != null

    /**
     * 对一张摆正后的 [bitmap] 做推理（**后台线程调用**）。返回每只手的 21 点 + 手势，至多 [numHands] 只。
     */
    fun detect(bitmap: Bitmap): List<HandOverlay> {
        val recognizer = gestureRecognizer ?: return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = recognizer.recognize(mpImage)
            handleResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            emptyList()
        }
    }

    private fun handleResult(result: GestureRecognizerResult): List<HandOverlay> {
        val landmarksList = result.landmarks() // List<List<NormalizedLandmark>>，每只手 21 点
        if (landmarksList.isEmpty()) return emptyList()
        val handednessList = result.handedness() // List<List<Category>>
        val gesturesList = result.gestures()     // List<List<Category>>

        val out = ArrayList<HandOverlay>(landmarksList.size)
        for (i in landmarksList.indices) {
            val lms = landmarksList[i]
            if (lms.isEmpty()) continue
            val ordered = ArrayList<NormalizedPoint>(21)
            var loX = 1f; var loY = 1f; var hiX = 0f; var hiY = 0f
            for (lm in lms) {
                val x = lm.x().coerceIn(0f, 1f)
                val y = lm.y().coerceIn(0f, 1f)
                ordered.add(NormalizedPoint(x, y))
                if (x < loX) loX = x
                if (y < loY) loY = y
                if (x > hiX) hiX = x
                if (y > hiY) hiY = y
            }
            val handedness = handednessList.getOrNull(i)?.firstOrNull()?.categoryName()?.let { mapHandedness(it) }
            val topGesture = gesturesList.getOrNull(i)?.maxByOrNull { it.score() }
            val gesture = topGesture?.categoryName()?.let { mapGesture(it) }
            val gestureConf = topGesture?.score() ?: 0f
            out.add(
                HandOverlay(
                    landmarks = ordered,
                    boundingBox = RectF(loX, loY, hiX, hiY),
                    handedness = handedness,
                    gesture = gesture,
                    gestureConfidence = gestureConf,
                )
            )
        }
        return out
    }

    /** MediaPipe handedness category name → 本项目枚举。 */
    private fun mapHandedness(name: String): Handedness? = when (name) {
        "Left" -> Handedness.LEFT
        "Right" -> Handedness.RIGHT
        else -> null
    }

    /** MediaPipe 手势 category name → 本项目 7 类枚举。 */
    private fun mapGesture(name: String): HandGesture? = when (name) {
        "Closed_Fist" -> HandGesture.CLOSED_FIST
        "Open_Palm" -> HandGesture.OPEN_PALM
        "Pointing_Up" -> HandGesture.POINTING_UP
        "Thumb_Up" -> HandGesture.THUMB_UP
        "Thumb_Down" -> HandGesture.THUMB_DOWN
        "Victory" -> HandGesture.VICTORY
        "ILoveYou" -> HandGesture.I_LOVE_YOU
        "None" -> null
        else -> null
    }

    fun close() {
        gestureRecognizer?.close()
    }
}
