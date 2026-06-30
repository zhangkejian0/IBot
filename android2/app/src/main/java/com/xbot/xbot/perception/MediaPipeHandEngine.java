package com.xbot.xbot.perception;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.xbot.xbot.model.HandOverlay;
import com.xbot.xbot.util.AssetUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MediaPipe Hand Landmarker (21 keypoints per hand). Returns empty when
 * {@code hand_landmarker.task} is missing from assets.
 */
public class MediaPipeHandEngine {
    private static final String TAG = "MediaPipeHandEngine";
    private static final String MODEL_ASSET = "models/hand_landmarker.task";
    private static final int HAND_LANDMARK_COUNT = 21;

    private final Context appContext;
    private HandLandmarker landmarker;
    private boolean initialized;
    private boolean available;
    private long frameTimestampMs;

    public MediaPipeHandEngine(Context context) {
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
            Log.w(TAG, "Hand model missing at " + MODEL_ASSET + "; gesture recognition disabled");
            return;
        }
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(appContext, options);
            available = true;
            Log.i(TAG, "Hand landmarker initialized");
        } catch (Exception e) {
            available = false;
            landmarker = null;
            Log.w(TAG, "Hand init failed (model missing?): " + e.getMessage());
        }
        initialized = true;
    }

    public List<HandOverlay> process(@Nullable MPImage mpImage) {
        if (!available || landmarker == null || mpImage == null) {
            return Collections.emptyList();
        }

        frameTimestampMs += 33;
        ImageProcessingOptions processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(0)
                .build();

        HandLandmarkerResult result;
        try {
            result = landmarker.detectForVideo(mpImage, processingOptions, frameTimestampMs);
        } catch (Exception e) {
            Log.w(TAG, "detect error: " + e.getMessage());
            return Collections.emptyList();
        }

        if (result.landmarks().isEmpty()) {
            return Collections.emptyList();
        }

        List<HandOverlay> hands = new ArrayList<>();
        for (int handIndex = 0; handIndex < result.landmarks().size(); handIndex++) {
            List<NormalizedLandmark> landmarks = result.landmarks().get(handIndex);
            if (landmarks.isEmpty()) {
                continue;
            }

            List<PointF> ordered = new ArrayList<>(Collections.nCopies(HAND_LANDMARK_COUNT, new PointF(0, 0)));
            float minX = 1f;
            float minY = 1f;
            float maxX = 0f;
            float maxY = 0f;
            boolean any = false;

            for (int i = 0; i < landmarks.size() && i < HAND_LANDMARK_COUNT; i++) {
                NormalizedLandmark lm = landmarks.get(i);
                float x = clamp01(lm.x());
                float y = clamp01(lm.y());
                ordered.set(i, new PointF(x, y));
                any = true;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            if (!any) {
                continue;
            }

            String handedness = extractHandedness(result, handIndex);
            hands.add(new HandOverlay(
                    ordered,
                    new RectF(minX, minY, maxX, maxY),
                    handedness,
                    null,
                    0));
        }
        return hands;
    }

    public void dispose() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        initialized = false;
        available = false;
    }

    private static String extractHandedness(HandLandmarkerResult result, int handIndex) {
        if (result.handedness().size() <= handIndex) {
            return null;
        }
        List<Category> categories = result.handedness().get(handIndex);
        if (categories.isEmpty()) {
            return null;
        }
        return categories.get(0).categoryName();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
