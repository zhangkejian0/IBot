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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.xbot.xbot.face.ExpressionClassifier;
import com.xbot.xbot.model.ExpressionResult;
import com.xbot.xbot.model.FaceOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MediaPipe Face Landmarker (478 points + blendshapes) with expression via
 * {@link ExpressionClassifier}.
 *
 * <p>Ported from Flutter {@code lib/services/face_engine.dart} face path.
 */
public class MediaPipeFaceEngine {
    private static final String TAG = "MediaPipeFaceEngine";
    private static final String MODEL_ASSET = "models/face_landmarker.task";

    private final Context appContext;
    private final ExpressionClassifier classifier;
    private FaceLandmarker landmarker;
    private boolean initialized;
    private long frameTimestampMs;

    public MediaPipeFaceEngine(Context context) {
        this(context, new ExpressionClassifier());
    }

    public MediaPipeFaceEngine(Context context, ExpressionClassifier classifier) {
        this.appContext = context.getApplicationContext();
        this.classifier = classifier;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void initialize() throws Exception {
        if (initialized) {
            return;
        }
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .build();
        landmarker = FaceLandmarker.createFromOptions(appContext, options);
        initialized = true;
        Log.i(TAG, "Face landmarker initialized");
    }

    @Nullable
    public FaceOverlay process(@Nullable MPImage mpImage) {
        if (!initialized || landmarker == null || mpImage == null) {
            return null;
        }

        frameTimestampMs += 33;
        ImageProcessingOptions processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(0)
                .build();

        FaceLandmarkerResult result;
        try {
            result = landmarker.detectForVideo(mpImage, processingOptions, frameTimestampMs);
        } catch (Exception e) {
            Log.w(TAG, "detect error: " + e.getMessage());
            return null;
        }

        if (result.faceLandmarks().isEmpty()) {
            return null;
        }

        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);
        if (landmarks.isEmpty()) {
            return null;
        }

        List<PointF> points = new ArrayList<>(landmarks.size());
        float minX = 1f;
        float minY = 1f;
        float maxX = 0f;
        float maxY = 0f;
        for (NormalizedLandmark lm : landmarks) {
            float x = clamp01(lm.x());
            float y = clamp01(lm.y());
            points.add(new PointF(x, y));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        Map<String, Double> blendshapes = extractBlendshapes(result);
        ExpressionResult expression = classifier.classify(blendshapes);

        double blinkL = value(blendshapes, "eyeBlinkLeft");
        double blinkR = value(blendshapes, "eyeBlinkRight");
        double eyeBlink = (blinkL + blinkR) / 2.0;
        double mouthOpenness = value(blendshapes, "jawOpen");
        double gazeX = horizontalGaze(blendshapes);
        double gazeY = verticalGaze(blendshapes);

        return new FaceOverlay(
                points,
                new RectF(minX, minY, maxX, maxY),
                expression,
                null,
                gazeX,
                gazeY,
                eyeBlink,
                mouthOpenness);
    }

    public void dispose() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        initialized = false;
    }

    private static Map<String, Double> extractBlendshapes(FaceLandmarkerResult result) {
        Map<String, Double> map = new HashMap<>();
        if (!result.faceBlendshapes().isPresent()) {
            return map;
        }
        List<List<Category>> faces = result.faceBlendshapes().get();
        if (faces.isEmpty()) {
            return map;
        }
        for (Category category : faces.get(0)) {
            map.put(category.categoryName(), (double) category.score());
        }
        return map;
    }

    /** Gaze X from eight eyeLook blendshapes; positive = right. */
    private static double horizontalGaze(Map<String, Double> blendshapes) {
        double inL = value(blendshapes, "eyeLookInLeft");
        double outL = value(blendshapes, "eyeLookOutLeft");
        double inR = value(blendshapes, "eyeLookInRight");
        double outR = value(blendshapes, "eyeLookOutRight");
        return clamp((outR + inL - outL - inR) / 2.0);
    }

    /** Gaze Y from eyeLook up/down blendshapes; positive = down. */
    private static double verticalGaze(Map<String, Double> blendshapes) {
        double down = (value(blendshapes, "eyeLookDownLeft") + value(blendshapes, "eyeLookDownRight")) / 2.0;
        double up = (value(blendshapes, "eyeLookUpLeft") + value(blendshapes, "eyeLookUpRight")) / 2.0;
        return clamp(down - up);
    }

    private static double value(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return clamp(v != null ? v : 0);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
