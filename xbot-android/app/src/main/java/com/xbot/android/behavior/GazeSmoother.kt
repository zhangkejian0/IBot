package com.xbot.android.behavior

import com.xbot.android.model.NormalizedPoint

/**
 * 注视方向 EMA（一阶低通）平滑器。对应 Flutter gaze_smoother.dart。
 *
 * 经典 EMA：out_n = out_{n-1} + k*(raw - out_{n-1})，独立作用于 dx/dy。
 * k=0.8 默认（越大越贴原始、越跟手；越小越平滑、越滞后）。
 */
class GazeSmoother(private val k: Float = 0.8f) {
    private var value = NormalizedPoint(0f, 0f)
    private var initialized = false

    fun update(x: Float, y: Float): NormalizedPoint {
        if (!initialized) {
            value = NormalizedPoint(x, y)
            initialized = true
        } else {
            value = NormalizedPoint(
                value.x + k * (x - value.x),
                value.y + k * (y - value.y),
            )
        }
        return value
    }

    fun reset() {
        value = NormalizedPoint(0f, 0f)
        initialized = false
    }
}
