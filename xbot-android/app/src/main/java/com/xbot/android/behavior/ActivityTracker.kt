package com.xbot.android.behavior

import android.graphics.RectF
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.NormalizedPoint
import com.xbot.android.model.PoseOverlay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 日常活动 FSM（时序聚合）。对应 Flutter activity_state_tracker.dart。
 *
 * 与 [BehaviorTracker]（注意力）正交的多模态聚合（人脸 + 物体 + 姿态），产出单一当前活动，
 * 互斥、strongest-wins。窗口 5s，滞回 dwell。
 *
 * 8 活动（优先级）：none/drinking/lookingAtPhone/talking/yawning/handRaised/restingCheek/poorPosture。
 *
 * 物体标签中文：杯子/瓶子/酒杯=drinkware，手机=phone。
 * 姿态索引：nose=0, ear=7/8, shoulder=11/12, wrist=15/16, hip=23/24；手部 wrist=0, indexTip=8, middleTip=12。
 */
class ActivityTracker(
    private val windowMs: Long = 5000L,
    private val minVisibility: Float = 0.5f,
    private val cupNearFaceDist: Float = 0.12f,
    private val poorPostureTiltDeg: Float = 25f,
    private val talkMouthLow: Float = 0.15f,
    private val talkMouthHigh: Float = 0.8f,
    private val yawnBlinkThreshold: Float = 0.3f,
    private val yawnPeakThreshold: Float = 0.6f,
    private val yawnWideRatio: Float = 0.3f,
    private val talkVarianceThreshold: Float = 0.005f,
    private val talkSpeechRatio: Float = 0.35f,
    private val talkPeakMax: Float = 0.65f,
    private val talkFluxRatio: Float = 0.5f,
) {
    /** 8 活动。 */
    enum class Activity(val apiKey: String, val label: String) {
        NONE("none", "无活动"),
        DRINKING("drinking", "喝水"),
        LOOKING_AT_PHONE("looking_at_phone", "看手机"),
        TALKING("talking", "交谈"),
        YAWNING("yawning", "打哈欠"),
        HAND_RAISED("hand_raised", "举手"),
        RESTING_CHEEK("resting_cheek", "托腮"),
        POOR_POSTURE("poor_posture", "坐姿不良");
    }

    data class Transition(val from: Activity, val to: Activity, val previousDurationMs: Long)

    data class Snapshot(
        val activity: Activity,
        val durationMs: Long,
        val transition: Transition?,
    ) {
        companion object {
            val INITIAL = Snapshot(Activity.NONE, 0L, null)
        }
    }

    private data class Sample(
        val t: Long,
        val hasFace: Boolean,
        val mouthOpenness: Float,
        val eyeBlink: Float,
        val gazeDown: Float,
        val holdingDrinkware: Boolean,
        val holdingPhone: Boolean,
        val drinkwareNearMouth: Boolean,
        val armRaised: Boolean,
        val handOnCheek: Boolean,
        val torsoTiltDeg: Float,
        val shoulderTensed: Boolean,
    )

    private val samples = ArrayList<Sample>()
    private var activity: Activity = Activity.NONE
    private var activitySince: Long = 0L
    private var candidate: Activity? = null
    private var candidateSince: Long = 0L

    fun update(result: DetectionResult, now: Long): Snapshot {
        samples.add(extract(result, now))
        val windowStart = now - windowMs
        while (samples.isNotEmpty() && samples[0].t < windowStart) samples.removeAt(0)
        val desired = computeDesired(now)
        val transition = applyHysteresis(desired, now)
        return Snapshot(activity, now - activitySince, transition)
    }

    private fun extract(result: DetectionResult, now: Long): Sample {
        val face = result.face
        val held = result.heldObject?.label
        val holdingDrinkware = held in setOf("杯子", "瓶子", "酒杯")
        val holdingPhone = held == "手机"

        val drinkwareNearMouth = holdingDrinkware && face != null &&
            result.heldObject != null && isCupNearFace(result.heldObject!!.boundingBox, face.boundingBox)

        // 手部托腮（不依赖 pose）。
        val handOnCheek = !holdingDrinkware && face != null &&
            result.hands.isNotEmpty() && isHandOnCheekByHand(result.hands, face.boundingBox)

        // pose 几何。
        val pose = result.poses.firstOrNull()
        val poseOk = isPoseReliable(pose)
        val armRaised = poseOk && pose != null && isArmRaised(pose)
        val torsoTiltDeg = if (poseOk && pose != null) torsoTiltDeg(pose) else 0f
        val shoulderTensed = poseOk && pose != null && isShoulderTensed(pose)

        return Sample(
            t = now,
            hasFace = face != null,
            mouthOpenness = face?.mouthOpenness ?: 0f,
            eyeBlink = face?.eyeBlink ?: 0f,
            gazeDown = (face?.gazeY ?: 0f).coerceIn(0f, 1f),
            holdingDrinkware = holdingDrinkware,
            holdingPhone = holdingPhone,
            drinkwareNearMouth = drinkwareNearMouth,
            armRaised = armRaised,
            handOnCheek = handOnCheek,
            torsoTiltDeg = torsoTiltDeg,
            shoulderTensed = shoulderTensed,
        )
    }

    /** 优先级 strongest-wins：drinking > lookingAtPhone > yawning > talking > restingCheek > handRaised > poorPosture > none。 */
    private fun computeDesired(now: Long): Activity {
        val recent = samples.filter { it.hasFace }
        if (recent.isEmpty()) return Activity.NONE
        val n = recent.size

        // drinking：持杯且靠近嘴 占比 ≥ 0.4
        val drinkHits = recent.count { it.holdingDrinkware && it.drinkwareNearMouth }
        if (drinkHits.toFloat() / n >= 0.4f) return Activity.DRINKING

        // lookingAtPhone：持手机且 gazeDown>0.2 占比 ≥ 0.5
        val phoneHits = recent.count { it.holdingPhone && it.gazeDown > 0.2f }
        if (phoneHits.toFloat() / n >= 0.5f) return Activity.LOOKING_AT_PHONE

        // yawning vs talking（需 ≥ 4 帧）
        if (n >= 4) {
            val mouths = recent.map { it.mouthOpenness }
            val blinks = recent.map { it.eyeBlink }
            val maxMouth = mouths.max()
            val flux = mouthFlux(mouths)
            val blinkRatio = blinks.count { it > yawnBlinkThreshold }.toFloat() / blinks.size
            val wideRatio = mouths.count { it > yawnPeakThreshold }.toFloat() / mouths.size
            // yawning：maxMouth≥0.6 且 wideRatio≥0.3 且 (flux<0.5 或 blinkRatio>0.3)
            if (maxMouth >= yawnPeakThreshold && wideRatio >= yawnWideRatio &&
                (flux < talkFluxRatio || blinkRatio > yawnBlinkThreshold)
            ) return Activity.YAWNING
            // talking：flux≥0.5 且 var>0.005 且 speechRatio>0.35 且 maxMouth<0.65
            val speechRatio = mouths.count { it > talkMouthLow && it < talkMouthHigh }.toFloat() / mouths.size
            val varMouth = variance(mouths)
            if (flux >= talkFluxRatio && varMouth > talkVarianceThreshold &&
                speechRatio > talkSpeechRatio && maxMouth < talkPeakMax
            ) return Activity.TALKING
        }

        // restingCheek：托腮 占比 ≥ 0.6
        val cheekHits = recent.count { it.handOnCheek }
        if (cheekHits.toFloat() / n >= 0.6f) return Activity.RESTING_CHEEK

        // handRaised：举手 占比 ≥ 0.6
        val armHits = recent.count { it.armRaised }
        if (armHits.toFloat() / n >= 0.6f) return Activity.HAND_RAISED

        // poorPosture：torsoTilt>25 或 shoulderTensed 占比 ≥ 0.7
        val postureHits = recent.count { it.torsoTiltDeg > poorPostureTiltDeg || it.shoulderTensed }
        if (postureHits.toFloat() / n >= 0.7f) return Activity.POOR_POSTURE

        return Activity.NONE
    }

    private fun applyHysteresis(desired: Activity, now: Long): Transition? {
        if (desired == activity) {
            candidate = null
            return null
        }
        if (candidate != desired) {
            candidate = desired
            candidateSince = now
            return null
        }
        if (now - candidateSince >= dwellFor(desired)) {
            val prev = activity
            val prevDuration = now - activitySince
            activity = desired
            activitySince = now
            candidate = null
            return Transition(prev, desired, prevDuration)
        }
        return null
    }

    private fun dwellFor(target: Activity): Long = when (target) {
        Activity.DRINKING -> 1000L
        Activity.LOOKING_AT_PHONE -> 1500L
        Activity.TALKING -> 2000L
        Activity.YAWNING -> 1500L
        Activity.HAND_RAISED -> 600L
        Activity.RESTING_CHEEK -> 2500L
        Activity.POOR_POSTURE -> 3000L
        Activity.NONE -> 1500L
    }

    // ============ pose / hand 几何判定 ============

    /** pose 是否可靠：landmarks≥13 且两肩 visibility>0.5（索引 11/12）。 */
    private fun isPoseReliable(pose: PoseOverlay?): Boolean {
        if (pose == null) return false
        if (pose.landmarks.size < 13 || pose.visibilities.size < 13) return false
        return pose.visibilities[11] > minVisibility && pose.visibilities[12] > minVisibility
    }

    /** 举手：任一手腕高于肩（屏幕 y 向下，wrist.dy < shoulder.dy-0.02），且 wrist 可见非 0。 */
    private fun isArmRaised(pose: PoseOverlay): Boolean {
        if (pose.landmarks.size < 17) return false
        // 左臂 shoulder=11 wrist=15；右臂 shoulder=12 wrist=16。
        val leftRaised = raisedArm(pose, shoulderIdx = 11, wristIdx = 15)
        val rightRaised = raisedArm(pose, shoulderIdx = 12, wristIdx = 16)
        return leftRaised || rightRaised
    }

    private fun raisedArm(pose: PoseOverlay, shoulderIdx: Int, wristIdx: Int): Boolean {
        if (pose.visibilities.size <= wristIdx) return false
        if (pose.visibilities[wristIdx] <= minVisibility) return false
        val wrist = pose.landmarks[wristIdx]
        val shoulder = pose.landmarks[shoulderIdx]
        if (wrist.x == 0f && wrist.y == 0f) return false
        return wrist.y < shoulder.y - 0.02f
    }

    /** 躯干前倾角：肩中点 - 髋中点 的偏离垂直角度（0=直立）。索引 shoulder=11/12, hip=23/24。 */
    private fun torsoTiltDeg(pose: PoseOverlay): Float {
        if (pose.landmarks.size < 25) return 0f
        val shoulderMid = midpoint(pose.landmarks[11], pose.landmarks[12])
        val hipMid = midpoint(pose.landmarks[23], pose.landmarks[24])
        val dx = shoulderMid.x - hipMid.x
        val dy = shoulderMid.y - hipMid.y
        if (abs(dy) < 1e-3f) return 90f
        return Math.toDegrees(atan2(abs(dx).toDouble(), abs(dy).toDouble())).toFloat()
    }

    /** 耸肩：耳-肩距离 < 0.08（索引 ear=7/8, shoulder=11/12）。 */
    private fun isShoulderTensed(pose: PoseOverlay): Boolean {
        if (pose.landmarks.size < 12) return false
        val leftGap = earShoulderGap(pose, earIdx = 7, shoulderIdx = 11)
        val rightGap = earShoulderGap(pose, earIdx = 8, shoulderIdx = 12)
        val gap = minOf(leftGap, rightGap)
        return gap < 0.08f
    }

    private fun earShoulderGap(pose: PoseOverlay, earIdx: Int, shoulderIdx: Int): Float {
        if (pose.visibilities.size <= shoulderIdx) return Float.MAX_VALUE
        if (pose.visibilities[earIdx] <= minVisibility || pose.visibilities[shoulderIdx] <= minVisibility) return Float.MAX_VALUE
        val ear = pose.landmarks[earIdx]
        val shoulder = pose.landmarks[shoulderIdx]
        if ((ear.x == 0f && ear.y == 0f) || (shoulder.x == 0f && shoulder.y == 0f)) return Float.MAX_VALUE
        return hypot(ear.x - shoulder.x, ear.y - shoulder.y)
    }

    private fun midpoint(a: NormalizedPoint, b: NormalizedPoint): NormalizedPoint =
        NormalizedPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun isCupNearFace(cupBox: RectF, faceBox: RectF): Boolean =
        distToBox(NormalizedPoint(cupBox.centerX(), cupBox.centerY()), faceBox) < cupNearFaceDist

    private fun isHandOnCheekByHand(hands: List<com.xbot.android.model.HandOverlay>, faceBox: RectF): Boolean {
        for (hand in hands) {
            if (hand.landmarks.size < 13) continue
            val wrist = hand.landmarks[0]
            val indexTip = hand.landmarks[8]
            val middleTip = hand.landmarks[12]
            if (wrist.x == 0f && wrist.y == 0f) continue
            if (indexTip.x == 0f && indexTip.y == 0f) continue
            val tipDist = minOf(
                distToBox(indexTip, faceBox),
                distToBox(middleTip, faceBox),
            )
            if (tipDist >= 0.06f) continue
            val faceUpperThird = faceBox.top + faceBox.height() / 3f
            val tipY = (indexTip.y + middleTip.y) / 2f
            if (tipY < faceUpperThird) continue
            if (wrist.y < faceBox.top + faceBox.height() / 4f) continue
            return true
        }
        return false
    }

    /** 点到矩形归一化距离（点在矩形内为 0）。 */
    private fun distToBox(p: NormalizedPoint, box: RectF): Float {
        val dx = when {
            p.x < box.left -> box.left - p.x
            p.x > box.right -> p.x - box.right
            else -> 0f
        }
        val dy = when {
            p.y < box.top -> box.top - p.y
            p.y > box.bottom -> p.y - box.bottom
            else -> 0f
        }
        return hypot(dx, dy)
    }

    /** 嘴部开合方向反转率（yawn vs talk 鉴别）。eps=0.03 噪声底。 */
    private fun mouthFlux(mouths: List<Float>): Float {
        if (mouths.size < 2) return 0f
        val eps = 0.03f
        var dir = 0
        var changes = 0
        for (i in 1 until mouths.size) {
            val delta = mouths[i] - mouths[i - 1]
            val cur = when {
                delta > eps -> 1
                delta < -eps -> -1
                else -> 0
            }
            if (cur != 0 && dir != 0 && cur != dir) changes++
            if (cur != 0) dir = cur
        }
        return changes.toFloat() / (mouths.size - 1)
    }

    private fun variance(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        val m = xs.sum() / xs.size
        var sum = 0.0
        for (x in xs) sum += ((x - m) * (x - m)).toDouble()
        return (sum / xs.size).toFloat()
    }

    fun reset() {
        samples.clear()
        activity = Activity.NONE
        activitySince = 0L
        candidate = null
        candidateSince = 0L
    }
}
