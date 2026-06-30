package com.xbot.android.model

import androidx.compose.ui.graphics.Color

/**
 * 7 种常见表情（Ekman 经典 6 种 + 中性）。对应 Flutter Expression 枚举。
 */
enum class Expression(val apiName: String, val label: String, val emoji: String) {
    NEUTRAL("neutral", "中性", "😐"),
    HAPPY("happy", "高兴", "😄"),
    SAD("sad", "伤心", "😢"),
    SURPRISED("surprise", "惊讶", "😲"),
    ANGRY("angry", "愤怒", "😠"),
    DISGUSTED("disgust", "厌恶", "🤢"),
    FEARFUL("fear", "恐惧", "😨");

    companion object {
        /** 后端 7 类 key → 枚举。 */
        fun fromApiName(key: String?): Expression =
            entries.firstOrNull { it.apiName == key } ?: NEUTRAL
    }
}

/** 表情识别结果：最可能的表情、其置信度、以及全部表情的打分（调试用）。 */
data class ExpressionResult(
    val expression: Expression,
    val score: Float,
    val scores: Map<Expression, Float>,
) {
    companion object {
        val NEUTRAL = ExpressionResult(
            expression = Expression.NEUTRAL,
            score = 1f,
            scores = mapOf(Expression.NEUTRAL to 1f),
        )
    }
}
