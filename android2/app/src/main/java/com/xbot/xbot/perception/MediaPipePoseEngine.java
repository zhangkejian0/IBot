package com.xbot.xbot.perception;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.xbot.xbot.model.PoseOverlay;
import com.xbot.xbot.util.AssetUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MediaPipe Pose Landmarker (33 keypoints). Gracefully disabled when
 * {@code pose_landmarker_lite.task} is missing from assets.
 */
public class MediaPipePoseEngine {
    private static final String TAG = "MediaPipePoseEngine";
    private static final String MODEL_ASSET = "models/pose_landmarker_lite.task";
    private static final int POSE_LANDMARK_COUNT = 33;

    private final Context appContext;
    private PoseLandmarker landmarker;
    private boolean initialized;
    private boolean available;
    private long frameTimestampMs;

    public MediaPipePoseEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        if (!AssetUtils.hasAsset(appContext, MODEL_ASSET)) {
            available = false;
            landmarker = null;
            initialized = true;
            Log.w(TAG, "Pose model missing at " + MODEL_ASSET + "; pose recognition disabled");
            return;
        }
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();
            PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build();
            landmarker = PoseLandmarker.createFromOptions(appContext, options);
            available = true;
            Log.i(TAG, "Pose landmarker initialized");
        } catch (Exception e) {
            available = false;
            landmarker = null;
            Log.w(TAG, "Pose init failed (model missing?): " + e.getMessage());
        }
        initialized = true;
    }

    public List<PoseOverlay> process(@Nullable MPImage mpImage) {
        if (!available || landmarker == null || mpImage == null) {
            return Collections.emptyList();
        }

        frameTimestampMs += 33;
        ImageProcessingOptions processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(0)
                .build();

        PoseLandmarkerResult result;
        try {
            result = landmarker.detectForVideo(mpImage, processingOptions, frameTimestampMs);
        } catch (Exception e) {
            Log.w(TAG, "detect error: " + e.getMessage());
            return Collections.emptyList();
        }

        if (result.landmarks().isEmpty()) {
            return Collections.emptyList();
        }

        List<NormalizedLandmark> poseLandmarks = result.landmarks().get(0);
        if (poseLandmarks.isEmpty()) {
            return Collections.emptyList();
        }

        List<PointF> ordered = new ArrayList<>(Collections.nCopies(POSE_LANDMARK_COUNT, new PointF(0, 0)));
        List<Double> visibilities = new ArrayList<>(Collections.nCopies(POSE_LANDMARK_COUNT, 0.0));
        float minX = 1f;
        float minY = 1f;
        float maxX = 0f;
        float maxY = 0f;
        boolean any = false;

        for (int i = 0; i < poseLandmarks.size() && i < POSE_LANDMARK_COUNT; i++) {
            NormalizedLandmark lm = poseLandmarks.get(i);
            float x = clamp01(lm.x());
            float y = clamp01(lm.y());
            ordered.set(i, new PointF(x, y));
            float visibility = lm.visibility().orElse(0f);
            visibilities.set(i, (double) Math.max(0.0, Math.min(1.0, visibility)));
            any = true;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        if (!any) {
            return Collections.emptyList();
        }

        List<PoseOverlay> poses = new ArrayList<>(1);
        poses.add(new PoseOverlay(ordered, new RectF(minX, minY, maxX, maxY), visibilities));
        return poses;
    }

    public void dispose() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        initialized = false;
        available = false;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
