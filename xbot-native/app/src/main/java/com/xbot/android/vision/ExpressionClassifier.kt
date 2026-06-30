package com.xbot.android.vision

import com.xbot.android.model.Expression
import com.xbot.android.model.ExpressionResult
import kotlin.math.max

/**
 * 基于 ARKit 52 blendshapes 的规则式表情分类器（对应 Flutter ExpressionClassifier）。
 *
 * 参照 Apple ARFaceAnchor.blendShapes 的语义，将若干 blendshape 系数组合为 7 种常见
 * 表情的打分，取最高者；若所有表情得分都很低（< [activationThreshold]）则判为「中性」。
 *
 * 规则式分类（非训练模型），便于调试阶段直观验证关键点 → 表情的映射。
 *
 * blendshape key 为 MediaPipe tasks-vision 输出的 camelCase category name
 *（如 "eyeBlinkLeft", "mouthSmileLeft", "jawOpen", ...），与 ARKit 命名一致。
 */
class ExpressionClassifier(
    /** 判定为非中性表情所需的最小得分。 */
    private val activationThreshold: Float = 0.28f,
) {
    fun classify(b: Map<String, Float>): ExpressionResult {
        if (b.isEmpty()) return ExpressionResult.NEUTRAL

        fun v(k: String): Float = (b[k] ?: 0f).coerceIn(0f, 1f)
        fun avg(a: String, c: String): Float = (v(a) + v(c)) / 2f

        val smile = avg("mouthSmileLeft", "mouthSmileRight")
        val cheekSquint = avg("cheekSquintLeft", "cheekSquintRight")
        val frown = avg("mouthFrownLeft", "mouthFrownRight")
        val browInnerUp = v("browInnerUp")
        val browOuterUp = avg("browOuterUpLeft", "browOuterUpRight")
        val browDown = avg("browDownLeft", "browDownRight")
        val jawOpen = v("jawOpen")
        val eyeWide = avg("eyeWideLeft", "eyeWideRight")
        val eyeSquint = avg("eyeSquintLeft", "eyeSquintRight")
        val noseSneer = avg("noseSneerLeft", "noseSneerRight")
        val upperLipUp = avg("mouthUpperUpLeft", "mouthUpperUpRight")
        val mouthStretch = avg("mouthStretchLeft", "mouthStretchRight")
        val mouthPress = avg("mouthPressLeft", "mouthPressRight")

        val scores = LinkedHashMap<Expression, Float>()
        // 高兴：嘴角上扬 + 脸颊上提（杜乡微笑）
        scores[Expression.HAPPY] = smile * 0.8f + cheekSquint * 0.2f
        // 伤心：嘴角下垂 + 眉心内侧上抬（八字眉）
        scores[Expression.SAD] = frown * 0.6f + browInnerUp * 0.4f - smile * 0.3f
        // 惊讶：张嘴 + 眉毛整体上抬 + 睁大眼
        scores[Expression.SURPRISED] =
            jawOpen * 0.45f + browOuterUp * 0.25f + browInnerUp * 0.15f + eyeWide * 0.15f
        // 愤怒：皱眉（眉下压）+ 抿嘴/眯眼
        scores[Expression.ANGRY] =
            browDown * 0.6f + mouthPress * 0.2f + eyeSquint * 0.2f - jawOpen * 0.2f
        // 厌恶：皱鼻 + 上唇上提
        scores[Expression.DISGUSTED] = noseSneer * 0.6f + upperLipUp * 0.4f
        // 恐惧：睁大眼 + 眉内抬 + 嘴角横向拉伸
        scores[Expression.FEARFUL] = eyeWide * 0.4f + browInnerUp * 0.3f + mouthStretch * 0.3f

        var best = Expression.NEUTRAL
        var bestScore = 0f
        for ((expr, s) in scores) {
            val clamped = s.coerceIn(0f, 1f)
            if (clamped > bestScore) {
                bestScore = clamped
                best = expr
            }
        }

        val fullScores = LinkedHashMap<Expression, Float>()
        fullScores[Expression.NEUTRAL] = max(0f, 1f - bestScore)
        for ((k, value) in scores) fullScores[k] = value.coerceIn(0f, 1f)

        return if (bestScore < activationThreshold) {
            ExpressionResult(
                expression = Expression.NEUTRAL,
                score = fullScores[Expression.NEUTRAL] ?: 1f,
                scores = fullScores,
            )
        } else {
            ExpressionResult(expression = best, score = bestScore, scores = fullScores)
        }
    }
}
