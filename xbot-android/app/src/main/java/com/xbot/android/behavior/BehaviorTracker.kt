package com.xbot.android.behavior

import com.xbot.android.model.DetectionResult
import com.xbot.android.model.Expression
import com.xbot.android.model.NormalizedPoint

/**
 * 注意力行为 FSM（时序聚合）。对应 Flutter behavior_state_tracker.dart。
 *
 * 把高频单帧观测（表情、gaze、人脸位置、眨眼）聚合成稳定的语义注意力状态，
 * 抵消单帧误检抖动。与 [ActivityTracker] 正交（一个人可「专注地喝水」）。
 *
 * 5 态：absent/present/focused/distracted/drowsy。
 *
 * 窗口 8s，滑动窗口 + 滞回 dwell（每个目标态需持续 N ms 才确认切换）。
 */
class BehaviorTracker(
    private val windowMs: Long = 8000L,
    private val recentPresenceWindowMs: Long = 1500L,
    private val movementThreshold: Float = 0.15f,
    private val gazeStableStd: Float = 0.12f,
    private val zoneChangeRatePerSec: Float = 0.6f,
    private val blinkClosedThreshold: Float = 0.55f,
    private val minJudgeSpanMs: Long = 2500L,
) {
    /** 5 态。 */
    enum class State(val apiKey: String, val label: String) {
        ABSENT("absent", "离开"),
        PRESENT("present", "在场"),
        FOCUSED("focused", "专注"),
        DISTRACTED("distracted", "走神"),
        DROWSY("drowsy", "困倦");
    }

    /** 状态转移事件。 */
    data class Transition(val from: State, val to: State, val previousDurationMs: Long)

    /** 快照。 */
    data class Snapshot(
        val state: State,
        val durationMs: Long,
        val dominantExpression: Expression,
        val dominantExpressionRatio: Float,
        val transition: Transition?,
    ) {
        companion object {
            val INITIAL = Snapshot(State.ABSENT, 0L, Expression.NEUTRAL, 1f, null)
        }
    }

    private data class Sample(
        val t: Long,
        val hasFace: Boolean,
        val cx: Float,
        val cy: Float,
        val size: Float,
        val gazeX: Float,
        val gazeY: Float,
        val blink: Float,
        val expr: Expression,
        val exprScore: Float,
    )

    private val samples = ArrayList<Sample>()
    private var state: State = State.ABSENT
    private var stateSince: Long = 0L
    private var candidate: State? = null
    private var candidateSince: Long = 0L

    fun update(result: DetectionResult, now: Long): Snapshot {
        val face = result.face
        val hasFace = face != null
        val s = Sample(
            t = now,
            hasFace = hasFace,
            cx = face?.boundingBox?.centerX() ?: 0f,
            cy = face?.boundingBox?.centerY() ?: 0f,
            size = maxOf(face?.boundingBox?.width() ?: 1e-3f, 1e-3f),
            gazeX = face?.gazeX ?: 0f,
            gazeY = face?.gazeY ?: 0f,
            blink = face?.eyeBlink ?: 0f,
            expr = face?.expression?.expression ?: Expression.NEUTRAL,
            exprScore = face?.expression?.score ?: 0f,
        )
        samples.add(s)
        // 滑出窗口。
        val windowStart = now - windowMs
        while (samples.isNotEmpty() && samples[0].t < windowStart) samples.removeAt(0)

        val desired = computeDesired(now)
        val transition = applyHysteresis(desired, now)
        val dominant = dominantExpression()
        return Snapshot(
            state = state,
            durationMs = now - stateSince,
            dominantExpression = dominant.first,
            dominantExpressionRatio = dominant.second,
            transition = transition,
        )
    }

    private fun computeDesired(now: Long): State {
        if (samples.isEmpty()) return State.ABSENT
        // absent 判定：近窗内有脸比例 < 0.3。
        val recentStart = now - recentPresenceWindowMs
        val recent = samples.filter { it.t >= recentStart }
        if (recent.isNotEmpty()) {
            val withFace = recent.count { it.hasFace }
            if (withFace.toFloat() / recent.size < 0.3f) return State.ABSENT
        }
        val faces = samples.filter { it.hasFace }
        if (faces.size < 3) return State.PRESENT
        // 困倦：mean blink > 0.55（无需 min-span，闭眼是明确信号）。
        val meanBlink = mean(faces.map { it.blink })
        if (meanBlink > blinkClosedThreshold) return State.DROWSY
        // 跨度不足：span < 2500ms。
        val span = now - faces.first().t
        if (span < minJudgeSpanMs) return State.PRESENT
        // 运动稳定性。
        val meanSize = maxOf(mean(faces.map { it.size }), 1e-3f)
        val cxStd = std(faces.map { it.cx })
        val cyStd = std(faces.map { it.cy })
        val moveMetric = Math.sqrt((cxStd * cxStd + cyStd * cyStd).toDouble()).toFloat() / meanSize
        val moveStable = moveMetric < movementThreshold
        // gaze 稳定性。
        val gazeStd = Math.sqrt(
            (std(faces.map { it.gazeX }).toDouble().pow(2) +
                std(faces.map { it.gazeY }).toDouble().pow(2))
        ).toFloat()
        val gazeStable = gazeStd < gazeStableStd
        // zone 变化率。
        val zoneChanges = zoneChanges(faces)
        val zoneRate = zoneChanges.toFloat() / (span / 1000f)
        // 判定。
        return when {
            moveStable && gazeStable -> State.FOCUSED
            gazeStd > gazeStableStd * 1.8f || zoneRate > zoneChangeRatePerSec -> State.DISTRACTED
            else -> State.PRESENT
        }
    }

    /** 滞回 dwell：候选态需持续 dwellFor 才提交。 */
    private fun applyHysteresis(desired: State, now: Long): Transition? {
        if (desired == state) {
            candidate = null
            return null
        }
        if (candidate != desired) {
            candidate = desired
            candidateSince = now
            return null
        }
        if (now - candidateSince >= dwellFor(desired)) {
            val prev = state
            val prevDuration = now - stateSince
            state = desired
            stateSince = now
            candidate = null
            return Transition(prev, desired, prevDuration)
        }
        return null
    }

    private fun dwellFor(target: State): Long = when (target) {
        State.FOCUSED -> 3000L
        State.DISTRACTED -> 2500L
        State.DROWSY -> 2000L
        State.ABSENT -> 1500L
        State.PRESENT -> 1500L
    }

    /** 主导表情：窗口内加权投票（权重 0.2+score）。 */
    private fun dominantExpression(): Pair<Expression, Float> {
        val faces = samples.filter { it.hasFace }
        if (faces.isEmpty()) return Expression.NEUTRAL to 1f
        val weights = HashMap<Expression, Float>()
        var total = 0f
        for (f in faces) {
            val w = 0.2f + f.exprScore
            weights[f.expr] = (weights[f.expr] ?: 0f) + w
            total += w
        }
        val best = weights.maxByOrNull { it.value } ?: return Expression.NEUTRAL to 1f
        val ratio = if (total == 0f) 1f else best.value / total
        return best.key to ratio
    }

    /** 9 宫格切换计数（无死区，仅用于 distract rate）。 */
    private fun zoneChanges(faces: List<Sample>): Int {
        if (faces.isEmpty()) return 0
        var prevZone = -1
        var count = 0
        for (f in faces) {
            val col = (f.cx * 3f).toInt().coerceIn(0, 2)
            val row = (f.cy * 3f).toInt().coerceIn(0, 2)
            val zone = row * 3 + col
            if (prevZone >= 0 && zone != prevZone) count++
            prevZone = zone
        }
        return count
    }

    fun reset() {
        samples.clear()
        state = State.ABSENT
        stateSince = 0L
        candidate = null
        candidateSince = 0L
    }

    private fun mean(xs: List<Float>): Float = if (xs.isEmpty()) 0f else xs.sum() / xs.size

    private fun std(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        val m = mean(xs)
        var sum = 0.0
        for (x in xs) sum += ((x - m) * (x - m)).toDouble()
        return Math.sqrt(sum / xs.size).toFloat()
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
