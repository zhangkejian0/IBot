package com.xbot.android.behavior

import com.xbot.android.model.DetectionResult

/**
 * 日常活动状态枚举。对应 Flutter 的 ActivityState。
 * 与注意力状态正交,记录「正在做什么」。
 */
enum class ActivityState(val label: String) {
    IDLE("待机"),
    DRINKING("喝水"),
    SITTING("坐着"),
    TALKING("交谈"),
    PHONE("看手机"),
    YAWNING("打哈欠"),
    RAISING_HAND("举手")
}

/**
 * 日常活动快照。
 */
data class ActivitySnapshot(
    val state: ActivityState = ActivityState.IDLE,
    val duration: Long = 0,
    val transition: ActivityTransition? = null
) {
    companion object {
        val INITIAL = ActivitySnapshot()
    }
}

data class ActivityTransition(
    val from: ActivityState,
    val to: ActivityState,
    val previousDuration: Long
)

/**
 * 日常活动状态机:从人脸+物体+姿态多模态信号聚合而来。
 * 对应 Flutter 的 ActivityStateTracker。
 *
 * 简化版(原型):主要基于嘴部张开和手部姿态推断。
 */
class ActivityStateTracker {
    private var state = ActivityState.IDLE
    private var stateEnteredAt = System.currentTimeMillis()
    private var lastTransition: ActivityTransition? = null

    fun update(result: DetectionResult, now: Long): ActivitySnapshot {
        val face = result.face
        lastTransition = null

        // 打哈欠:嘴部大开 + 眼睛半闭
        val isYawning = face != null &&
            face.mouthOpenness > 0.6f &&
            face.eyeBlink > 0.3f

        // 交谈:嘴部中等张开(说话)
        val isTalking = face != null &&
            face.mouthOpenness > 0.2f &&
            face.mouthOpenness < 0.6f

        if (isYawning && state != ActivityState.YAWNING) {
            transitionTo(ActivityState.YAWNING, now)
        } else if (isTalking && state != ActivityState.TALKING) {
            transitionTo(ActivityState.TALKING, now)
        } else if (face != null && !isYawning && !isTalking &&
            state != ActivityState.SITTING && state != ActivityState.IDLE
        ) {
            transitionTo(ActivityState.SITTING, now)
        } else if (face == null && state != ActivityState.IDLE) {
            transitionTo(ActivityState.IDLE, now)
        }

        return ActivitySnapshot(
            state = state,
            duration = now - stateEnteredAt,
            transition = lastTransition
        )
    }

    fun reset() {
        state = ActivityState.IDLE
        stateEnteredAt = System.currentTimeMillis()
        lastTransition = null
    }

    private fun transitionTo(newState: ActivityState, now: Long) {
        val previousDuration = now - stateEnteredAt
        lastTransition = ActivityTransition(state, newState, previousDuration)
        state = newState
        stateEnteredAt = now
    }
}
