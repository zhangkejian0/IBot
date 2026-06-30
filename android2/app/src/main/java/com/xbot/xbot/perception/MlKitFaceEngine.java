package com.xbot.xbot.perception;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ML Kit multi-face detection. Returns normalized {@link RectF} boxes sorted by
 * area (largest / primary face first).
 *
 * <p>Ported from Flutter {@code lib/services/mlkit_face_engine.dart}.
 */
public class MlKitFaceEngine {
    private static final String TAG = "MlKitFaceEngine";

    private final int maxFaces;
    private FaceDetector detector;
    private boolean initialized;

    public MlKitFaceEngine() {
        this(3);
    }

    public MlKitFaceEngine(int maxFaces) {
        this.maxFaces = maxFaces;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        detector = FaceDetection.getClient(options);
        initialized = true;
    }

    /**
     * Detect faces on an upright bitmap. Boxes are normalized to 0..1.
     */
    public List<RectF> processBitmap(Bitmap uprightBitmap) {
        if (!initialized || detector == null || uprightBitmap == null || uprightBitmap.isRecycled()) {
            return Collections.emptyList();
        }
        try {
            InputImage input = InputImage.fromBitmap(uprightBitmap, 0);
            return processInput(input, uprightBitmap.getWidth(), uprightBitmap.getHeight());
        } catch (Exception e) {
            Log.w(TAG, "detect error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Detect faces on upright RGBA bytes (rotation already applied). Boxes are
     * normalized to 0..1 relative to the upright image.
     */
    public List<RectF> processRgba(byte[] rgba, int width, int height) {
        if (!initialized || detector == null || width <= 0 || height <= 0 || rgba == null) {
            return Collections.emptyList();
        }
        try {
            InputImage input = InputImage.fromBitmap(
                    CameraImageUtils.uprightBitmapFromRgba(rgba, width, height), 0);
            return processInput(input, width, height);
        } catch (Exception e) {
            Log.w(TAG, "detect error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RectF> processInput(InputImage input, int width, int height) {
        try {
            List<Face> faces = com.google.android.gms.tasks.Tasks.await(detector.process(input));
            List<ScoredBox> boxes = new ArrayList<>();
            for (Face face : faces) {
                Rect b = face.getBoundingBox();
                float nx = clamp01((float) b.left / width);
                float ny = clamp01((float) b.top / height);
                float nx2 = clamp01((float) b.right / width);
                float ny2 = clamp01((float) b.bottom / height);
                float area = (nx2 - nx) * (ny2 - ny);
                if (area <= 0) {
                    continue;
                }
                boxes.add(new ScoredBox(new RectF(nx, ny, nx2, ny2), area));
            }
            boxes.sort((a, b) -> Float.compare(b.area, a.area));
            List<RectF> result = new ArrayList<>();
            int limit = Math.min(maxFaces, boxes.size());
            for (int i = 0; i < limit; i++) {
                result.add(boxes.get(i).rect);
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "process error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public void dispose() {
        if (detector != null) {
            detector.close();
            detector = null;
        }
        initialized = false;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class ScoredBox {
        final RectF rect;
        final float area;

        ScoredBox(RectF rect, float area) {
            this.rect = rect;
            this.area = area;
        }
    }
}
