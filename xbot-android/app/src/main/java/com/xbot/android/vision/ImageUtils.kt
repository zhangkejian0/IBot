package com.xbot.android.vision

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 图像工具：人脸裁剪 + 像素读取。
 *
 * 对应 Flutter 的 camera_image_utils.dart（cropNormalized + 像素访问部分）。
 * 原生方案下 CameraX 已摆正图像（toBitmap），无需 Flutter 的逐像素 YUV→RGB。
 */
object ImageUtils {

    /**
     * 按归一化矩形（0..1）从 bitmap 裁剪人脸区域，并向外扩展 [paddingRatio]。
     * 对应 Flutter cropNormalized（padding 0.2）。
     */
    fun cropNormalized(bitmap: Bitmap, box: RectF, paddingRatio: Float = 0.2f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val padW = box.width() * paddingRatio
        val padH = box.height() * paddingRatio
        val left = ((box.left - padW) * w).toInt().coerceIn(0, w - 1)
        val top = ((box.top - padH) * h).toInt().coerceIn(0, h - 1)
        val right = ((box.right + padW) * w).toInt().coerceIn(0, w - 1)
        val bottom = ((box.bottom + padH) * h).toInt().coerceIn(0, h - 1)
        val cropW = maxOf(1, right - left)
        val cropH = maxOf(1, bottom - top)
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }
}
