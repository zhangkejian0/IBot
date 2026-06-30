package com.xbot.xbot.model;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.Collections;
import java.util.List;

/** Normalized face overlay data (0..1 coordinates). */
public class FaceOverlay {
    public final List<PointF> landmarks;
    public final RectF boundingBox;
    public final ExpressionResult expression;
    public final IdentityMatch identity;
    public final double gazeX;
    public final double gazeY;
    public final double eyeBlink;
    public final double mouthOpenness;

    public FaceOverlay(
            List<PointF> landmarks,
            RectF boundingBox,
            ExpressionResult expression,
            IdentityMatch identity,
            double gazeX,
            double gazeY,
            double eyeBlink,
            double mouthOpenness) {
        this.landmarks = landmarks != null ? landmarks : Collections.emptyList();
        this.boundingBox = boundingBox;
        this.expression = expression != null ? expression : ExpressionResult.NEUTRAL;
        this.identity = identity;
        this.gazeX = gazeX;
        this.gazeY = gazeY;
        this.eyeBlink = eyeBlink;
        this.mouthOpenness = mouthOpenness;
    }

    public FaceOverlay(
            List<PointF> landmarks,
            RectF boundingBox,
            ExpressionResult expression) {
        this(landmarks, boundingBox, expression, null, 0, 0, 0, 0);
    }
}
