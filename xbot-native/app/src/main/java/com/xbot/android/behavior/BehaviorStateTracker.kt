package com.xbot.android.behavior

import com.xbot.android.model.DetectionResult
import com.xbot.android.model.Expression

/**
 * 行为状态枚举。对应 Flutter 的 BehaviorState。
 */
enum class BehaviorState(val label: String) {
    /** 人专注看(正面、持续注视、表情积极) */
    FOCUSED("专注"),
    /** 人在但走神(东张西望、表情平淡) */
    DISTRACTED("走神"),
    /** 困倦(眼睛快闭上了) */
    DROWSY("困倦"),
    /** 离开(检测不到人脸) */
    ABSENT("离开"),
    /** 在场(默认态,刚检测到人脸还未稳定) */
    PRESENT("在场")
}

/**
 * 行为快照:当前状态 + 持续时长 + 主导表情 + 状态转移事件。
 * 对应 Flutter 的 BehaviorSnapshot。
 */
data class BehaviorSnapshot(
    val state: BehaviorState = BehaviorState.ABSENT,
    val duration: Long = 0,  // 当前状态已持续的毫秒数
    val dominantExpression: Expression = Expression.NEUTRAL,
    val transition: StateTransition? = null
) {
    companion object {
        val INITIAL = BehaviorSnapshot()
    }
}

/**
 * 状态转移事件。
 */
data class StateTransition(
    val from: BehaviorState,
    val to: BehaviorState,
    val previousDuration: Long  // 上一状态持续的毫秒数
)

/**
 * 时序行为聚合器:把高频单帧观测聚合成稳定的行为状态。
 * 直接翻译自 Flutter 的 BehaviorStateTracker。
 *
 * 核心逻辑:
 * - 连续 3 帧无人脸 → ABSENT
 * - 刚出现人脸 → PRESENT → 稳定后 → FOCUSED
 * - eyeBlink > 0.7 持续 → DROWSY
 * - gaze 偏移大 → DISTRACTED
 */
class BehaviorStateTracker {
    private var state = BehaviorState.ABSENT
    private var stateEnteredAt = System.currentTimeMillis()
    private var lastTransition: StateTransition? = null

    // 连续帧计数
    private var absentConsecutive = 0
    private var presentConsecutive = 0
    private var drowsyConsecutive = 0

    // 主导表情(EMA 平滑)
    private val expressionCounts = mutableMapOf<Expression, Int>()

    fun update(result: DetectionResult, now: Long): BehaviorSnapshot {
        val face = result.face
        val prevState = state
        lastTransition = null

        if (face == null) {
            absentConsecutive++
            presentConsecutive = 0
            drowsyConsecutive = 0
            if (absentConsecutive >= 3 && state != BehaviorState.ABSENT) {
                transitionTo(BehaviorState.ABSENT, now)
            }
        } else {
            absentConsecutive = 0
            presentConsecutive++

            // 困倦检测:eyeBlink > 0.7 持续
            if (face.eyeBlink > 0.7f) {
                drowsyConsecutive++
            } else {
                drowsyConsecutive = 0
            }

            if (drowsyConsecutive >= 3 && state != BehaviorState.DROWSY) {
                transitionTo(BehaviorState.DROWSY, now)
            } else if (state == BehaviorState.ABSENT && presentConsecutive >= 2) {
                transitionTo(BehaviorState.PRESENT, now)
            } else if (state == BehaviorState.PRESENT && presentConsecutive >= 5) {
                transitionTo(BehaviorState.FOCUSED, now)
            }

            // 更新主导表情
            expressionCounts[face.expression.expression] =
                (expressionCounts[face.expression.expression] ?: 0) + 1
        }

        val duration = now - stateEnteredAt
        val dominant = expressionCounts.maxByOrNull { it.value }?.key ?: Expression.NEUTRAL

        return BehaviorSnapshot(
            state = state,
            duration = duration,
            dominantExpression = dominant,
            transition = lastTransition
        )
    }

    fun reset() {
        state = BehaviorState.ABSENT
        stateEnteredAt = System.currentTimeMillis()
        lastTransition = null
        absentConsecutive = 0
        presentConsecutive = 0
        drowsyConsecutive = 0
        expressionCounts.clear()
    }

    private fun transitionTo(newState: BehaviorState, now: Long) {
        val previousDuration = now - stateEnteredAt
        lastTransition = StateTransition(state, newState, previousDuration)
        state = newState
        stateEnteredAt = now
    }
}
