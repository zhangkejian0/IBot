package com.xbot.xbot.model;

import java.util.Collections;
import java.util.Map;

/** Expression classification output with per-class scores. */
public class ExpressionResult {
    public final Expression expression;
    public final double score;
    public final Map<Expression, Double> scores;

    public ExpressionResult(Expression expression, double score, Map<Expression, Double> scores) {
        this.expression = expression;
        this.score = score;
        this.scores = scores;
    }

    public static final ExpressionResult NEUTRAL = new ExpressionResult(
            Expression.NEUTRAL,
            1.0,
            Collections.singletonMap(Expression.NEUTRAL, 1.0));
}
