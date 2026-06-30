package com.xbot.xbot.model;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.Collections;
import java.util.List;

/** Normalized pose overlay data (33 keypoints, 0..1 coordinates). */
public class PoseOverlay {
    public final List<PointF> landmarks;
    public final List<Double> visibilities;
    public final RectF boundingBox;

    public PoseOverlay(List<PointF> landmarks, RectF boundingBox, List<Double> visibilities) {
        this.landmarks = landmarks != null ? landmarks : Collections.emptyList();
        this.boundingBox = boundingBox;
        this.visibilities = visibilities != null ? visibilities : Collections.emptyList();
    }

    public PoseOverlay(List<PointF> landmarks, RectF boundingBox) {
        this(landmarks, boundingBox, Collections.emptyList());
    }
}
