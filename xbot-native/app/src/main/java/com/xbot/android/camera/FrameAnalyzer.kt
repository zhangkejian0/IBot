package com.xbot.android.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xbot.android.model.FaceOverlay

/**
 * CameraX 帧分析器：在后台线程把 [ImageProxy] 转 bitmap → 送入人脸引擎推理。
 *
 * 对应 Flutter 的 _onFrame / _processFrame（按需采样版）。
 *
 * 原生方案简化点：CameraX 的 ImageProxy.toBitmap() 已按 targetRotation 摆正，
 * 且 OUTPUT_IMAGE_FORMAT_RGBA_8888 直接给出单平面 RGBA，**无需 Flutter 的逐像素
 * YUV→RGB 转换 + copyRotate**（那两个是 Flutter 主线程最大开销之一）。
 *
 * 阶段 0：只做人脸，产出主脸 [FaceOverlay]。阶段 1 起在此扩展多脸/手势/物体/身份。
 *
 * @param onResult 推理结果回调（**在后台线程触发**，调用方需自行切到主线程）。
 *                 参数：主脸（无人脸为 null）、单帧推理耗时（ms）。
 */
class FrameAnalyzer(
    private val detect: (Bitmap) -> FaceOverlay?,
    private val onResult: (face: FaceOverlay?, inferMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val start = System.currentTimeMillis()
        try {
            val bitmap = image.toRgbBitmap()
            val face = if (bitmap != null) detect(bitmap) else null
            val inferMs = System.currentTimeMillis() - start
            onResult(face, inferMs)
        } catch (e: Exception) {
            // 单帧异常不影响后续帧。
        } finally {
            // analyze 必须 close image，否则 CameraX 不会投递下一帧。
            image.close()
        }
    }

    /**
     * ImageProxy → Bitmap。
     *
     * 用 RGBA_8888 输出格式时（见 CameraManager OUTPUT_IMAGE_FORMAT_RGBA_8888），
     * image.planes[0] 即单平面 RGBA buffer，直接构 Bitmap。CameraX 已按 targetRotation
     * 摆正，故得到的 bitmap 是 upright（无需再 copyRotate）。
     *
     * 注：toBitmap() 在 CameraX 1.4.x 可用，但走 YUV→转换路径；这里直接读 RGBA plane 更省。
     * 兜底：若 plane 不可用，回退 image.toBitmap()。
     */
    private fun ImageProxy.toRgbBitmap(): Bitmap? {
        return try {
            val plane = planes[0]
            val buffer = plane.buffer as java.nio.ByteBuffer
            val pixelStride = plane.pixelStride // RGBA_8888 下通常 = 4
            val rowStride = plane.rowStride
            val rowPadding = rowStride - width * pixelStride
            // 含 rowPadding 的展开 bitmap，再 crop 成紧凑 bitmap（去掉右侧 padding）。
            val expanded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            buffer.rewind()
            expanded.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                expanded
            } else {
                Bitmap.createBitmap(expanded, 0, 0, width, height)
            }
        } catch (e: Exception) {
            // 回退：CameraX 内置转换（含旋转）。
            try {
                this.toBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
}
