package com.xbot.android.model

import androidx.compose.ui.graphics.Color

/**
 * 7 种表情枚举(Ekman's 6 + neutral)。
 * 直接翻译自 Flutter 的 Expression enum。
 */
enum class Expression(
    val label: String,
    val emoji: String,
    val color: Color
) {
    NEUTRAL("平静", "😐", Color(0xFF9E9E9E)),
    HAPPY("高兴", "😊", Color(0xFF4CAF50)),
    SAD("难过", "😢", Color(0xFF2196F3)),
    SURPRISED("惊讶", "😮", Color(0xFFFF9800)),
    ANGRY("生气", "😠", Color(0xFFF44336)),
    DISGUSTED("厌恶", "🤢", Color(0xFF9C27B0)),
    FEARFUL("恐惧", "😨", Color(0xFF607D8B));

    companion object {
        val DEFAULT = NEUTRAL
    }
}

/**
 * 表情识别结果:赢的表情 + 置信度 + 所有 7 类分数。
 * 直接翻译自 Flutter 的 ExpressionResult。
 */
data class ExpressionResult(
    val expression: Expression = Expression.NEUTRAL,
    val score: Float = 0f,
    val scores: Map<Expression, Float> = emptyMap()
) {
    companion object {
        val NEUTRAL = ExpressionResult()
    }
}
