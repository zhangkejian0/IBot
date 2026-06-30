package com.xbot.xbot.model;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.Collections;
import java.util.List;

/** Normalized hand overlay data (0..1 coordinates). */
public class HandOverlay {
    public final List<PointF> landmarks;
    public final RectF boundingBox;
    public final String handedness;
    public final String gesture;
    public final double gestureConfidence;

    public HandOverlay(
            List<PointF> landmarks,
            RectF boundingBox,
            String handedness,
            String gesture,
            double gestureConfidence) {
        this.landmarks = landmarks != null ? landmarks : Collections.emptyList();
        this.boundingBox = boundingBox;
        this.handedness = handedness;
        this.gesture = gesture;
        this.gestureConfidence = gestureConfidence;
    }
}
