package com.xbot.xbot.model;

import java.util.Collections;
import java.util.List;

/** Aggregated per-frame detection output for overlays and downstream logic. */
public class DetectionResult {
    public final List<FaceOverlay> faces;
    public final List<HandOverlay> hands;
    public final List<PoseOverlay> poses;
    public final boolean mirror;

    public DetectionResult(
            List<FaceOverlay> faces,
            List<HandOverlay> hands,
            List<PoseOverlay> poses,
            boolean mirror) {
        this.faces = faces != null ? faces : Collections.emptyList();
        this.hands = hands != null ? hands : Collections.emptyList();
        this.poses = poses != null ? poses : Collections.emptyList();
        this.mirror = mirror;
    }

    public DetectionResult() {
        this(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);
    }

    public FaceOverlay getPrimaryFace() {
        return faces.isEmpty() ? null : faces.get(0);
    }
}
