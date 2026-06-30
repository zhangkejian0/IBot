package com.xbot.android.detection

import com.xbot.android.model.Expression
import com.xbot.android.model.ExpressionResult

/**
 * 规则引擎表情分类:从 ARKit 52 blendshapes 计算 7 种表情分数。
 * 直接翻译自 Flutter 的 ExpressionClassifier。无训练模型,纯数学。
 */
class ExpressionClassifier(
    private val activationThreshold: Float = 0.28f
) {

    /**
     * 从 blendshape 系数计算表情。
     * @param blendshapes MediaPipe FaceLandmarker 输出的 blendshape Map(key=名称, value=0..1)
     */
    fun classify(blendshapes: Map<String, Float>): ExpressionResult {
        val happy = (blendshapes["mouthSmileLeft"] ?: 0f) * 0.8f +
                (blendshapes["cheekSquintLeft"] ?: 0f) * 0.2f

        val sad = (blendshapes["mouthFrownLeft"] ?: 0f) * 0.6f +
                (blendshapes["browInnerUp"] ?: 0f) * 0.4f -
                (blendshapes["mouthSmileLeft"] ?: 0f) * 0.3f

        val surprised = (blendshapes["jawOpen"] ?: 0f) * 0.45f +
                (blendshapes["browOuterUpLeft"] ?: 0f) * 0.25f +
                (blendshapes["browInnerUp"] ?: 0f) * 0.15f +
                (blendshapes["eyeWideLeft"] ?: 0f) * 0.15f

        val angry = (blendshapes["browDownLeft"] ?: 0f) * 0.6f +
                (blendshapes["mouthPressLeft"] ?: 0f) * 0.2f +
                (blendshapes["eyeSquintLeft"] ?: 0f) * 0.2f -
                (blendshapes["jawOpen"] ?: 0f) * 0.2f

        val disgusted = (blendshapes["noseSneerLeft"] ?: 0f) * 0.6f +
                (blendshapes["upperLipUpLeft"] ?: 0f) * 0.4f

        val fearful = (blendshapes["eyeWideLeft"] ?: 0f) * 0.4f +
                (blendshapes["browInnerUp"] ?: 0f) * 0.3f +
                (blendshapes["mouthStretchLeft"] ?: 0f) * 0.3f

        val scores = mapOf(
            Expression.HAPPY to happy.coerceIn(0f, 1f),
            Expression.SAD to sad.coerceIn(0f, 1f),
            Expression.SURPRISED to surprised.coerceIn(0f, 1f),
            Expression.ANGRY to angry.coerceIn(0f, 1f),
            Expression.DISGUSTED to disgusted.coerceIn(0f, 1f),
            Expression.FEARFUL to fearful.coerceIn(0f, 1f)
        )

        val winner = scores.maxByOrNull { it.value }!!
        return if (winner.value >= activationThreshold) {
            ExpressionResult(winner.key, winner.value, scores)
        } else {
            ExpressionResult(Expression.NEUTRAL, 0f, scores)
        }
    }
}
