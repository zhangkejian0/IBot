package com.xbot.android.webview

import kotlin.math.abs
import kotlin.math.floor

/**
 * 3×3 注视九宫格量化器（带边界死区）。对应 Flutter GazeZoneDetector。
 *
 * 把（平滑后的）人脸中心位置量化到 3×3 网格之一，emit 该格中心（-1/0/1 组合）作为
 * 瞳孔目标。格内移动脸眼睛不动（只跨格才变），配合 JS 侧 spring 自然过渡，避免低帧率下
 * 的连续映射抖动。带死区（边界 ±5%）防止人脸在格边界抖动时反复跳格。
 */
class GazeZoneDetector(
    private val cols: Int = 3,
    private val rows: Int = 3,
    /** 死区比例（按单格宽算，0.05 → 半宽 ≈ 0.0167 在 0..1 空间）。 */
    private val deadZoneRatio: Float = 0.05f,
) {
    private var currentCol: Int? = null
    private var currentRow: Int? = null

    /**
     * 用归一化人脸中心（-1..1，画面中心为原点）更新。
     * @return 若跨格（确认变化）返回 true；否则 false。
     */
    fun update(normalizedX: Float, normalizedY: Float): Boolean {
        // -1..1 → 0..1
        val x01 = (normalizedX + 1f) / 2f
        val y01 = (normalizedY + 1f) / 2f
        val col = calcZone(x01, cols)
        val row = calcZone(y01, rows)

        val cc = currentCol
        val cr = currentRow
        // 首次检测：直接落格。
        if (cc == null || cr == null) {
            currentCol = col; currentRow = row
            return true
        }
        // 无变化。
        if (col == cc && row == cr) return false
        // 在死区：停留在旧格。
        if (isInBoundaryDeadZone(x01, cc, col, cols) ||
            isInBoundaryDeadZone(y01, cr, row, rows)
        ) {
            return false
        }
        // 确认跨格。
        currentCol = col; currentRow = row
        return true
    }

    /** 当前格中心（-1..1），无格时（未检测到脸）返回 null。 */
    val currentZoneCenter: Pair<Float, Float>?
        get() {
            val c = currentCol ?: return null
            val r = currentRow ?: return null
            val cx01 = (c + 0.5f) / cols
            val cy01 = (r + 0.5f) / rows
            return Pair(cx01 * 2f - 1f, cy01 * 2f - 1f)
        }

    fun reset() {
        currentCol = null
        currentRow = null
    }

    private fun calcZone(value01: Float, divisions: Int): Int =
        floor(value01.coerceIn(0f, 1f) * divisions).toInt().coerceIn(0, divisions - 1)

    private fun isInBoundaryDeadZone(value01: Float, oldZone: Int, newZone: Int, totalZones: Int): Boolean {
        // 仅相邻格（跨 1 格）才检查死区；跨 2 格直接通过。
        if (abs(newZone - oldZone) != 1) return false
        val zoneWidth = 1f / totalZones
        val deadZone = zoneWidth * deadZoneRatio
        val boundary = if (newZone > oldZone) {
            (oldZone + 1) * zoneWidth // 旧格右边界
        } else {
            oldZone * zoneWidth // 旧格左边界
        }
        return abs(value01 - boundary) < deadZone
    }
}
