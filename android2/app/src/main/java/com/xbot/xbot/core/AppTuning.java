package com.xbot.xbot.core;

/**
 * Central tuning constants ported from Flutter {@code lib/core/app_tuning.dart}.
 *
 * <p>Android uses a faster sample interval (125 ms) than Flutter (300 ms) because
 * native CameraX + MediaPipe avoid the Hybrid Composition main-thread contention
 * that motivated Flutter's sparse sampling.
 */
public final class AppTuning {
    private AppTuning() {}

    // Gaze-triggered conversation
    public static final double GAZE_CENTER_X = -0.27;
    public static final double GAZE_CENTER_Y = 0.31;
    public static final double GAZE_TRIGGER_RADIUS = 0.10;
    public static final int GAZE_TRIGGER_SECONDS = 8;
    public static final int GAZE_COOLDOWN_SECONDS = 60;
    public static final int GAZE_TOLERANCE_SECONDS = 1;
    public static final double FACE_CENTER_TOLERANCE = 0.22;

    // Identity recognition & sampling throttling
    public static final long IDENTITY_INTERVAL_MS = 1200;
    public static final long SAMPLE_INTERVAL_MS = 125;
    public static final long IDENTITY_TTL_MS = 3000;
    public static final double SLOT_MATCH_DISTANCE = 0.15;

    // Startup & logging
    public static final long READY_BUFFER_MS = 2000;
    public static final long PERCEPTION_MIN_INTERVAL_MS = 2000;
}
