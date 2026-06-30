package com.xbot.xbot.face;

import com.xbot.xbot.model.Expression;
import com.xbot.xbot.model.ExpressionResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * Rule-based expression classifier from ARKit 52 blendshape coefficients.
 *
 * <p>Ported from Flutter {@code lib/services/expression_classifier.dart}.
 */
public class ExpressionClassifier {
    private static final String MOUTH_SMILE_LEFT = "mouthSmileLeft";
    private static final String MOUTH_SMILE_RIGHT = "mouthSmileRight";
    private static final String CHEEK_SQUINT_LEFT = "cheekSquintLeft";
    private static final String CHEEK_SQUINT_RIGHT = "cheekSquintRight";
    private static final String MOUTH_FROWN_LEFT = "mouthFrownLeft";
    private static final String MOUTH_FROWN_RIGHT = "mouthFrownRight";
    private static final String BROW_INNER_UP = "browInnerUp";
    private static final String BROW_OUTER_UP_LEFT = "browOuterUpLeft";
    private static final String BROW_OUTER_UP_RIGHT = "browOuterUpRight";
    private static final String BROW_DOWN_LEFT = "browDownLeft";
    private static final String BROW_DOWN_RIGHT = "browDownRight";
    private static final String JAW_OPEN = "jawOpen";
    private static final String EYE_WIDE_LEFT = "eyeWideLeft";
    private static final String EYE_WIDE_RIGHT = "eyeWideRight";
    private static final String EYE_SQUINT_LEFT = "eyeSquintLeft";
    private static final String EYE_SQUINT_RIGHT = "eyeSquintRight";
    private static final String NOSE_SNEER_LEFT = "noseSneerLeft";
    private static final String NOSE_SNEER_RIGHT = "noseSneerRight";
    private static final String MOUTH_UPPER_UP_LEFT = "mouthUpperUpLeft";
    private static final String MOUTH_UPPER_UP_RIGHT = "mouthUpperUpRight";
    private static final String MOUTH_STRETCH_LEFT = "mouthStretchLeft";
    private static final String MOUTH_STRETCH_RIGHT = "mouthStretchRight";
    private static final String MOUTH_PRESS_LEFT = "mouthPressLeft";
    private static final String MOUTH_PRESS_RIGHT = "mouthPressRight";

    private final double activationThreshold;

    public ExpressionClassifier() {
        this(0.28);
    }

    public ExpressionClassifier(double activationThreshold) {
        this.activationThreshold = activationThreshold;
    }

    public ExpressionResult classify(Map<String, Double> blendshapes) {
        if (blendshapes == null || blendshapes.isEmpty()) {
            return ExpressionResult.NEUTRAL;
        }

        double smile = avg(blendshapes, MOUTH_SMILE_LEFT, MOUTH_SMILE_RIGHT);
        double cheekSquint = avg(blendshapes, CHEEK_SQUINT_LEFT, CHEEK_SQUINT_RIGHT);
        double frown = avg(blendshapes, MOUTH_FROWN_LEFT, MOUTH_FROWN_RIGHT);
        double browInnerUp = v(blendshapes, BROW_INNER_UP);
        double browOuterUp = avg(blendshapes, BROW_OUTER_UP_LEFT, BROW_OUTER_UP_RIGHT);
        double browDown = avg(blendshapes, BROW_DOWN_LEFT, BROW_DOWN_RIGHT);
        double jawOpen = v(blendshapes, JAW_OPEN);
        double eyeWide = avg(blendshapes, EYE_WIDE_LEFT, EYE_WIDE_RIGHT);
        double eyeSquint = avg(blendshapes, EYE_SQUINT_LEFT, EYE_SQUINT_RIGHT);
        double noseSneer = avg(blendshapes, NOSE_SNEER_LEFT, NOSE_SNEER_RIGHT);
        double upperLipUp = avg(blendshapes, MOUTH_UPPER_UP_LEFT, MOUTH_UPPER_UP_RIGHT);
        double mouthStretch = avg(blendshapes, MOUTH_STRETCH_LEFT, MOUTH_STRETCH_RIGHT);
        double mouthPress = avg(blendshapes, MOUTH_PRESS_LEFT, MOUTH_PRESS_RIGHT);

        Map<Expression, Double> scores = new EnumMap<>(Expression.class);
        scores.put(Expression.HAPPY, smile * 0.8 + cheekSquint * 0.2);
        scores.put(Expression.SAD, frown * 0.6 + browInnerUp * 0.4 - smile * 0.3);
        scores.put(Expression.SURPRISED,
                jawOpen * 0.45 + browOuterUp * 0.25 + browInnerUp * 0.15 + eyeWide * 0.15);
        scores.put(Expression.ANGRY,
                browDown * 0.6 + mouthPress * 0.2 + eyeSquint * 0.2 - jawOpen * 0.2);
        scores.put(Expression.DISGUSTED, noseSneer * 0.6 + upperLipUp * 0.4);
        scores.put(Expression.FEARFUL, eyeWide * 0.4 + browInnerUp * 0.3 + mouthStretch * 0.3);

        Expression best = Expression.NEUTRAL;
        double bestScore = 0;
        for (Map.Entry<Expression, Double> entry : scores.entrySet()) {
            double clamped = clamp(entry.getValue());
            if (clamped > bestScore) {
                bestScore = clamped;
                best = entry.getKey();
            }
        }

        Map<Expression, Double> fullScores = new EnumMap<>(Expression.class);
        fullScores.put(Expression.NEUTRAL, Math.max(0.0, 1.0 - bestScore));
        for (Map.Entry<Expression, Double> entry : scores.entrySet()) {
            fullScores.put(entry.getKey(), clamp(entry.getValue()));
        }

        if (bestScore < activationThreshold) {
            Double neutralScore = fullScores.get(Expression.NEUTRAL);
            return new ExpressionResult(
                    Expression.NEUTRAL,
                    neutralScore != null ? neutralScore : 1.0,
                    fullScores);
        }

        return new ExpressionResult(best, bestScore, fullScores);
    }

    private static double v(Map<String, Double> b, String key) {
        Double value = b.get(key);
        return clamp(value != null ? value : 0);
    }

    private static double avg(Map<String, Double> b, String a, String c) {
        return (v(b, a) + v(b, c)) / 2.0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
