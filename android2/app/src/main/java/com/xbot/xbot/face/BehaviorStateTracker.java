package com.xbot.xbot.face;

import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.model.Expression;
import com.xbot.xbot.model.ExpressionResult;
import com.xbot.xbot.model.FaceOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporal behavior aggregator ported from Flutter {@code behavior_state_tracker.dart}.
 */
public class BehaviorStateTracker {
    private final long windowMs;
    private final long recentPresenceWindowMs;
    private final double movementThreshold;
    private final double gazeStableStd;
    private final double zoneChangeRatePerSec;
    private final double blinkClosedThreshold;
    private final long minJudgeSpanMs;

    private final List<Sample> samples = new ArrayList<>();
    private BehaviorState state = BehaviorState.ABSENT;
    private long stateSinceMs;
    private BehaviorState candidate;
    private long candidateSinceMs;

    public BehaviorStateTracker() {
        this(8000, 1500, 0.15, 0.12, 0.6, 0.55, 2500);
    }

    public BehaviorStateTracker(
            long windowMs,
            long recentPresenceWindowMs,
            double movementThreshold,
            double gazeStableStd,
            double zoneChangeRatePerSec,
            double blinkClosedThreshold,
            long minJudgeSpanMs) {
        this.windowMs = windowMs;
        this.recentPresenceWindowMs = recentPresenceWindowMs;
        this.movementThreshold = movementThreshold;
        this.gazeStableStd = gazeStableStd;
        this.zoneChangeRatePerSec = zoneChangeRatePerSec;
        this.blinkClosedThreshold = blinkClosedThreshold;
        this.minJudgeSpanMs = minJudgeSpanMs;
        long now = System.currentTimeMillis();
        this.stateSinceMs = now;
        this.candidateSinceMs = now;
    }

    public BehaviorState getState() {
        return state;
    }

    public BehaviorSnapshot update(DetectionResult result, long nowMs) {
        FaceOverlay face = result.getPrimaryFace();
        boolean hasFace = !result.faces.isEmpty() && face != null;

        double cx = 0;
        double cy = 0;
        double size = 1e-3;
        double gazeX = 0;
        double gazeY = 0;
        double blink = 0;
        Expression expr = Expression.NEUTRAL;
        double exprScore = 0;

        if (face != null && face.boundingBox != null) {
            cx = face.boundingBox.centerX();
            cy = face.boundingBox.centerY();
            size = Math.max(face.boundingBox.width(), 1e-3f);
            gazeX = face.gazeX;
            gazeY = face.gazeY;
            blink = face.eyeBlink;
            ExpressionResult expression = face.expression;
            if (expression != null) {
                expr = expression.expression;
                exprScore = expression.score;
            }
        }

        samples.add(new Sample(nowMs, hasFace, cx, cy, size, gazeX, gazeY, blink, expr, exprScore));

        while (!samples.isEmpty() && nowMs - samples.get(0).tMs > windowMs) {
            samples.remove(0);
        }

        BehaviorState desired = computeDesired(nowMs);
        BehaviorTransition transition = applyHysteresis(desired, nowMs);
        double[] dominant = dominantExpression();

        return new BehaviorSnapshot(
                state,
                nowMs - stateSinceMs,
                dominant[0] == 0 ? Expression.NEUTRAL : expressionFromOrdinal((int) dominant[0]),
                dominant[1],
                transition);
    }

    public void reset() {
        samples.clear();
        state = BehaviorState.ABSENT;
        stateSinceMs = System.currentTimeMillis();
        candidate = null;
    }

    private BehaviorState computeDesired(long nowMs) {
        if (samples.isEmpty()) {
            return BehaviorState.ABSENT;
        }

        List<Sample> recent = new ArrayList<>();
        for (Sample s : samples) {
            if (nowMs - s.tMs <= recentPresenceWindowMs) {
                recent.add(s);
            }
        }
        if (!recent.isEmpty()) {
            int faceCount = 0;
            for (Sample s : recent) {
                if (s.hasFace) {
                    faceCount++;
                }
            }
            double ratio = (double) faceCount / recent.size();
            if (ratio < 0.3) {
                return BehaviorState.ABSENT;
            }
        }

        List<Sample> faces = new ArrayList<>();
        for (Sample s : samples) {
            if (s.hasFace) {
                faces.add(s);
            }
        }
        if (faces.size() < 3) {
            return BehaviorState.PRESENT;
        }

        long spanMs = nowMs - faces.get(0).tMs;
        double meanBlink = mean(faces, Field.BLINK);
        if (meanBlink > blinkClosedThreshold) {
            return BehaviorState.DROWSY;
        }
        if (spanMs < minJudgeSpanMs) {
            return BehaviorState.PRESENT;
        }

        double meanSize = Math.max(mean(faces, Field.SIZE), 1e-3);
        double cxStd = std(faces, Field.CX);
        double cyStd = std(faces, Field.CY);
        double moveMetric = Math.sqrt(cxStd * cxStd + cyStd * cyStd) / meanSize;
        boolean moveStable = moveMetric < movementThreshold;

        double gazeStd = Math.sqrt(
                Math.pow(std(faces, Field.GAZE_X), 2)
                        + Math.pow(std(faces, Field.GAZE_Y), 2));
        boolean gazeStable = gazeStd < gazeStableStd;

        int zoneChanges = zoneChanges(faces);
        double seconds = spanMs / 1000.0;
        double zoneRate = seconds > 0 ? zoneChanges / seconds : 0;

        if (moveStable && gazeStable) {
            return BehaviorState.FOCUSED;
        }
        if (gazeStd > gazeStableStd * 1.8 || zoneRate > zoneChangeRatePerSec) {
            return BehaviorState.DISTRACTED;
        }
        return BehaviorState.PRESENT;
    }

    private BehaviorTransition applyHysteresis(BehaviorState desired, long nowMs) {
        if (desired == state) {
            candidate = null;
            return null;
        }
        if (candidate != desired) {
            candidate = desired;
            candidateSinceMs = nowMs;
            return null;
        }
        if (nowMs - candidateSinceMs >= dwellFor(desired)) {
            BehaviorState from = state;
            long prevDuration = nowMs - stateSinceMs;
            state = desired;
            stateSinceMs = nowMs;
            candidate = null;
            return new BehaviorTransition(from, desired, prevDuration);
        }
        return null;
    }

    private long dwellFor(BehaviorState target) {
        switch (target) {
            case FOCUSED:
                return 3000;
            case DISTRACTED:
                return 2500;
            case DROWSY:
                return 2000;
            case ABSENT:
            case PRESENT:
            default:
                return 1500;
        }
    }

    private double[] dominantExpression() {
        List<Sample> faces = new ArrayList<>();
        for (Sample s : samples) {
            if (s.hasFace) {
                faces.add(s);
            }
        }
        if (faces.isEmpty()) {
            return new double[]{Expression.NEUTRAL.ordinal(), 1.0};
        }
        double[] weights = new double[Expression.values().length];
        double total = 0;
        for (Sample s : faces) {
            double w = 0.2 + s.exprScore;
            weights[s.expr.ordinal()] += w;
            total += w;
        }
        int bestIdx = Expression.NEUTRAL.ordinal();
        double bestW = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > bestW) {
                bestW = weights[i];
                bestIdx = i;
            }
        }
        return new double[]{bestIdx, total > 0 ? bestW / total : 1.0};
    }

    private static Expression expressionFromOrdinal(int ordinal) {
        Expression[] values = Expression.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return Expression.NEUTRAL;
        }
        return values[ordinal];
    }

    private static int zoneChanges(List<Sample> faces) {
        int changes = 0;
        Integer prev = null;
        for (Sample s : faces) {
            int col = Math.max(0, Math.min(2, (int) Math.floor(Math.max(0.0, Math.min(1.0, s.cx)) * 3)));
            int row = Math.max(0, Math.min(2, (int) Math.floor(Math.max(0.0, Math.min(1.0, s.cy)) * 3)));
            int zone = row * 3 + col;
            if (prev != null && zone != prev) {
                changes++;
            }
            prev = zone;
        }
        return changes;
    }

    private enum Field { CX, CY, SIZE, GAZE_X, GAZE_Y, BLINK }

    private static double mean(List<Sample> samples, Field field) {
        if (samples.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (Sample s : samples) {
            sum += fieldValue(s, field);
        }
        return sum / samples.size();
    }

    private static double std(List<Sample> samples, Field field) {
        if (samples.size() < 2) {
            return 0;
        }
        double m = mean(samples, field);
        double acc = 0;
        for (Sample s : samples) {
            double d = fieldValue(s, field) - m;
            acc += d * d;
        }
        return Math.sqrt(acc / samples.size());
    }

    private static double fieldValue(Sample s, Field field) {
        switch (field) {
            case CX: return s.cx;
            case CY: return s.cy;
            case SIZE: return s.size;
            case GAZE_X: return s.gazeX;
            case GAZE_Y: return s.gazeY;
            case BLINK: return s.blink;
            default: return 0;
        }
    }

    private static final class Sample {
        final long tMs;
        final boolean hasFace;
        final double cx;
        final double cy;
        final double size;
        final double gazeX;
        final double gazeY;
        final double blink;
        final Expression expr;
        final double exprScore;

        Sample(
                long tMs,
                boolean hasFace,
                double cx,
                double cy,
                double size,
                double gazeX,
                double gazeY,
                double blink,
                Expression expr,
                double exprScore) {
            this.tMs = tMs;
            this.hasFace = hasFace;
            this.cx = cx;
            this.cy = cy;
            this.size = size;
            this.gazeX = gazeX;
            this.gazeY = gazeY;
            this.blink = blink;
            this.expr = expr;
            this.exprScore = exprScore;
        }
    }

    /** Confirmed behavior state transition. */
    public static class BehaviorTransition {
        public final BehaviorState from;
        public final BehaviorState to;
        public final long previousDurationMs;

        public BehaviorTransition(BehaviorState from, BehaviorState to, long previousDurationMs) {
            this.from = from;
            this.to = to;
            this.previousDurationMs = previousDurationMs;
        }
    }

    /** Aggregated behavior output for a single frame. */
    public static class BehaviorSnapshot {
        public static final BehaviorSnapshot INITIAL = new BehaviorSnapshot(
                BehaviorState.ABSENT, 0, Expression.NEUTRAL, 1.0, null);

        public final BehaviorState state;
        public final long durationMs;
        public final Expression dominantExpression;
        public final double dominantExpressionRatio;
        public final BehaviorTransition transition;

        public BehaviorSnapshot(
                BehaviorState state,
                long durationMs,
                Expression dominantExpression,
                double dominantExpressionRatio,
                BehaviorTransition transition) {
            this.state = state;
            this.durationMs = durationMs;
            this.dominantExpression = dominantExpression;
            this.dominantExpressionRatio = dominantExpressionRatio;
            this.transition = transition;
        }
    }
}
