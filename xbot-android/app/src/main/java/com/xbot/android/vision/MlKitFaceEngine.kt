package com.xbot.android.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.NormalizedPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit 多脸检测引擎。
 *
 * 对应 Flutter 的 mlkit_face_engine.dart。与单脸的 [FaceLandmarkEngine]（MediaPipe，478 点
 * + 表情，仅主脸）互补：ML Kit 天然支持多人脸，返回每张脸的归一化包围盒（按面积降序，
 * 主脸在前），供身份识别逐张裁剪比对。
 *
 * 输入：摆正后的 bitmap（图已 upright，rotation 传 0）。
 * 输出：归一化（0..1）包围盒列表，按面积降序，至多 [maxFaces] 个。
 *
 * @param maxFaces 至多检测几张脸（默认 3，与 Flutter 一致）
 */
class MlKitFaceEngine(
    private val maxFaces: Int = 3,
) {
    companion object {
        private const val TAG = "MlKitFaceEngine"
    }

    private val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // 仅取人脸框用于身份识别裁剪（landmarks/classification 已关闭），
            // fast 模式足够且比 accurate 快很多。
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            // 只需要包围盒；不调 enableTracking（默认关闭）、不设 landmarks/contours/classification 以降低开销。
            .build()
    )

    val isReady: Boolean get() = true

    /** 摆正后的 RGBA bitmap → 归一化包围盒列表（按面积降序，主脸在前）。 */
    suspend fun detectBoxes(bitmap: Bitmap): List<RectF> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()
        val input = InputImage.fromBitmap(bitmap, 0) // 图已摆正，rotation 0
        return try {
            val faces = detectAsync(input)
            val boxes = ArrayList<Pair<RectF, Float>>(faces.size)
            for (f in faces) {
                val b = f.boundingBox
                val nx = (b.left.toFloat() / w).coerceIn(0f, 1f)
                val ny = (b.top.toFloat() / h).coerceIn(0f, 1f)
                val nx2 = (b.right.toFloat() / w).coerceIn(0f, 1f)
                val ny2 = (b.bottom.toFloat() / h).coerceIn(0f, 1f)
                val area = (nx2 - nx) * (ny2 - ny)
                if (area > 0f) boxes.add(RectF(nx, ny, nx2, ny2) to area)
            }
            // 面积降序：主脸（最大）在前。
            boxes.sortByDescending { it.second }
            boxes.take(maxFaces).map { it.first }
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            emptyList()
        }
    }

    private suspend fun detectAsync(input: InputImage): List<com.google.mlkit.vision.face.Face> =
        suspendCancellableCoroutine { cont ->
            detector.process(input)
                .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }

    fun close() {
        detector.close()
    }
}
