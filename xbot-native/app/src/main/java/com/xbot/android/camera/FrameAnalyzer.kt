package com.xbot.android.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xbot.android.model.DetectionResult

/**
 * CameraX 帧分析器：在后台线程把 [ImageProxy] 转 bitmap → 送入视觉管线。
 *
 * 对应 Flutter 的 _onFrame / _processFrame（按需采样版）。
 *
 * 原生方案简化点：CameraX 的 RGBA_8888 输出直接给单平面 RGBA，无需 Flutter 的逐像素
 * YUV→RGB 转换 + copyRotate。
 *
 * @param process 把一帧 bitmap 跑完整视觉管线，返回 [DetectionResult]（**后台线程**）。
 *                内部也会触发后台物体检测。
 * @param onResult 结果回调（**后台线程触发**，调用方自行切主线程）。
 *                 参数：聚合结果、单帧耗时（ms）。
 */
class FrameAnalyzer(
    private val process: (Bitmap, Long) -> DetectionResult,
    private val onResult: (result: DetectionResult, inferMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val start = System.currentTimeMillis()
        try {
            val bitmap = image.toRgbBitmap()
            val result = if (bitmap != null) {
                process(bitmap, start)
            } else {
                DetectionResult()
            }
            val inferMs = System.currentTimeMillis() - start
            onResult(result, inferMs)
        } catch (_: Exception) {
            // 单帧异常不影响后续帧。
        } finally {
            image.close()
        }
    }

    /**
     * ImageProxy → Bitmap。RGBA_8888 输出格式下 planes[0] 即单平面 RGBA buffer。
     * CameraX 已按 targetRotation 摆正，得到的 bitmap 是 upright。
     */
    private fun ImageProxy.toRgbBitmap(): Bitmap? {
        return try {
            val plane = planes[0]
            val buffer = plane.buffer as java.nio.ByteBuffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - width * pixelStride
            val expanded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            buffer.rewind()
            expanded.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) expanded
            else Bitmap.createBitmap(expanded, 0, 0, width, height)
        } catch (_: Exception) {
            try { this.toBitmap() } catch (_: Exception) { null }
        }
    }
}
