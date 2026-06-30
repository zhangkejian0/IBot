package com.xbot.android.behavior

import com.xbot.android.model.Offset

/**
 * 注视区域检测器:把连续的人脸中心坐标量化到 3×3 九宫格。
 * 对应 Flutter 的 GazeZoneDetector。
 *
 * 格内移动脸眼睛不动(只跨格才变),配合 JS 侧 spring 平滑过渡,
 * 避免了连续映射在低帧率下的抖动。自带死区(边界±5%)防止格边界抖动。
 */
class GazeZoneDetector {
    /** 当前九宫格中心(-1/0/1 的组合) */
    var currentZoneCenter: Offset? = null
        private set

    // 上一次的格坐标,用于死区判断
    private var lastZoneX = 0
    private var lastZoneY = 0

    companion object {
        /** 死区:在格边界 ±此范围内不切换 */
        private const val DEAD_ZONE = 0.05f
    }

    /**
     * 更新人脸中心坐标(归一化 -1..1,画面中心为原点)。
     * @param x 画面中心→0,右→+1
     * @param y 画面中心→0,下→+1
     */
    fun update(x: Float, y: Float) {
        val rawZoneX = quantizeWithDeadZone(x, lastZoneX)
        val rawZoneY = quantizeWithDeadZone(y, lastZoneY)

        lastZoneX = rawZoneX
        lastZoneY = rawZoneY

        currentZoneCenter = Offset(rawZoneX.toFloat(), rawZoneY.toFloat())
    }

    fun reset() {
        currentZoneCenter = null
        lastZoneX = 0
        lastZoneY = 0
    }

    /**
     * 量化到 -1/0/1,带死区:在格边界 ±DEAD_ZONE 范围内保持上一次的值。
     */
    private fun quantizeWithDeadZone(v: Float, last: Int): Int {
        val base = when {
            v < -0.33f -> -1
            v > 0.33f -> 1
            else -> 0
        }
        // 死区:在边界附近不切换
        val distFromBoundary = minOf(
            kotlin.math.abs(v - (-0.33f)),
            kotlin.math.abs(v - 0.33f)
        )
        return if (distFromBoundary < DEAD_ZONE) last else base
    }
}
