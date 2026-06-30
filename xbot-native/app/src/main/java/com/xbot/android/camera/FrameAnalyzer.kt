package com.xbot.android.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xbot.android.detection.FaceDetector
import com.xbot.android.detection.FaceRecognizer
import com.xbot.android.detection.HandDetector
import com.xbot.android.detection.PoseDetector
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.HandOverlay
import com.xbot.android.model.ObjectOverlay
import com.xbot.android.model.Person
import com.xbot.android.model.PoseOverlay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 帧分析器:CameraX 的 ImageAnalysis.Analyzer 实现。
 *
 * **核心**:所有推理在 CameraX 绑定的后台 executor 执行,不碰主线程。
 * 检测完成后通过 [onResult] 回调轻量结果给主线程。
 *
 * 调度逻辑(对应 Flutter 的 _processFrame):
 * - 人脸:每帧(2fps)
 * - 手势:每帧(与人脸并行)
 * - 姿态:每帧(与人脸并行)
 * - 身份:节流(identityInterval)
 * - 物体:节流(objectInterval,后台异步)
 */
class FrameAnalyzer(
    private val faceDetector: FaceDetector,
    private val handDetector: HandDetector,
    private val poseDetector: PoseDetector,
    private val faceRecognizer: FaceRecognizer,
    private val onResult: (DetectionResult, Long) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FrameAnalyzer"
    }

    private var identityIntervalMs = 1200L
    private var lastIdentityRun = 0L

    // 身份 slot:按位置追踪,避免逐帧串脸
    private val identitySlots = mutableListOf<IdentitySlot>()
    private val identityTtlMs = 3000L
    private val slotMatchDistance = 0.15f

    // 人物库引用
    @Volatile
    var people: List<Person> = emptyList()

    override fun analyze(image: ImageProxy) {
        val start = System.currentTimeMillis()
        try {
            val bitmap = image.toBitmap() // CameraX 自动处理旋转

            // 人脸检测(每帧)
            val face = if (faceDetector.isInitialized) faceDetector.detect(bitmap) else null

            // 手势检测(每帧)
            val hands = if (handDetector.isInitialized) handDetector.detect(bitmap) else emptyList()

            // 姿态检测(每帧)
            val pose = if (poseDetector.isInitialized) poseDetector.detect(bitmap) else null

            // 身份识别(节流)
            val now = System.currentTimeMillis()
            val faces = mutableListOf<FaceOverlay>()
            if (face != null) {
                val identityDue = faceRecognizer.isInitialized &&
                    now - lastIdentityRun >= identityIntervalMs

                var identity = if (identityDue) {
                    lastIdentityRun = now
                    val embedding = faceRecognizer.embed(cropFace(bitmap, face.boundingBox))
                    if (embedding != null) {
                        faceRecognizer.identify(embedding, people)
                    } else null
                } else {
                    // 非识别帧:从 slot 续身份
                    getSlotIdentity(face.boundingBox, now)
                }

                // 更新 slot
                if (identityDue && identity != null) {
                    updateSlot(face.boundingBox, identity, now)
                }

                faces.add(face.copy(identity = identity))
            }

            val result = DetectionResult(
                faces = faces,
                hands = hands,
                objects = emptyList(), // TODO:物体检测(YOLO)后续集成
                poses = if (pose != null) listOf(pose) else emptyList()
            )

            val inferMs = System.currentTimeMillis() - start
            onResult(result, inferMs)
        } catch (e: Exception) {
            Log.e(TAG, "analyze 异常: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** 裁剪人脸区域(带 padding) */
    private fun cropFace(bitmap: Bitmap, box: RectF, padding: Float = 0.2f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val padW = box.width() * padding
        val padH = box.height() * padding
        val left = max(0, ((box.left - padW) * w).toInt())
        val top = max(0, ((box.top - padH) * h).toInt())
        val right = min(w, ((box.right + padW) * w).toInt())
        val bottom = min(h, ((box.bottom + padH) * h).toInt())
        val cropW = max(1, right - left)
        val cropH = max(1, bottom - top)
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    /** 从 slot 续身份(非识别帧) */
    private fun getSlotIdentity(box: RectF, now: Long): com.xbot.android.model.IdentityMatch? {
        val center = boxCenter(box)
        val slot = identitySlots.firstOrNull { s ->
            now - s.lastSeen < identityTtlMs &&
                distance(s.center, center) < slotMatchDistance
        }
        return slot?.identity
    }

    /** 更新 slot */
    private fun updateSlot(
        box: RectF,
        identity: com.xbot.android.model.IdentityMatch,
        now: Long
    ) {
        val center = boxCenter(box)
        val existing = identitySlots.firstOrNull { s ->
            now - s.lastSeen < identityTtlMs &&
                distance(s.center, center) < slotMatchDistance
        }
        if (existing != null) {
            existing.center = center
            existing.identity = identity
            existing.lastSeen = now
        } else {
            identitySlots.add(IdentitySlot(center, identity, now))
        }
    }

    private fun boxCenter(box: RectF) =
        Offset2D(box.centerX(), box.centerY())

    private fun distance(a: Offset2D, b: Offset2D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private data class IdentitySlot(
        var center: Offset2D,
        var identity: com.xbot.android.model.IdentityMatch?,
        var lastSeen: Long
    )

    private data class Offset2D(val x: Float, val y: Float)
}
