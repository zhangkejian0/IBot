package com.xbot.xbot.perception;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.MPImage;
import com.xbot.xbot.core.AppTuning;
import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.data.PersonRepository;
import com.xbot.xbot.face.BehaviorStateTracker;
import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.model.ExpressionResult;
import com.xbot.xbot.model.FaceOverlay;
import com.xbot.xbot.model.HandOverlay;
import com.xbot.xbot.model.IdentityMatch;
import com.xbot.xbot.model.PoseOverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX preview + image analysis pipeline with parallel ML inference, identity
 * slot tracking, and behavior aggregation.
 *
 * <p>Ported from Flutter {@code AppController._processFrame()} (without object
 * detection).
 */
public class PerceptionPipeline implements ImageAnalysis.Analyzer {
    private static final String TAG = "PerceptionPipeline";
    private static final Size ANALYSIS_TARGET = new Size(640, 480);

    private final Context appContext;
    private final DisplaySettings settings;
    private final PersonRepository personRepository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MlKitFaceEngine mlKitFaceEngine = new MlKitFaceEngine();
    private final MediaPipeFaceEngine mediaPipeFaceEngine;
    private final MediaPipePoseEngine mediaPipePoseEngine;
    private final MediaPipeHandEngine mediaPipeHandEngine;
    private final FaceRecognitionService faceRecognition;
    private final BehaviorStateTracker behaviorTracker = new BehaviorStateTracker();

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mlExecutor = Executors.newFixedThreadPool(3);

    private final MutableLiveData<DetectionResult> detectionResult =
            new MutableLiveData<>(new DetectionResult());
    private final MutableLiveData<BehaviorState> behaviorState =
            new MutableLiveData<>(BehaviorState.ABSENT);

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private volatile long lastSampleMs;
    private long lastIdentityRunMs;
    @Nullable private Bitmap lastFaceCrop;
    private ProcessCameraProvider cameraProvider;
    private Camera boundCamera;
    private PreviewView previewView;
    private LifecycleOwner lifecycleOwner;

    private final List<IdentitySlot> identitySlots = new ArrayList<>();

    public PerceptionPipeline(
            Context context,
            DisplaySettings settings,
            PersonRepository personRepository) {
        this.appContext = context.getApplicationContext();
        this.settings = settings;
        this.personRepository = personRepository;
        this.mediaPipeFaceEngine = new MediaPipeFaceEngine(appContext);
        this.mediaPipePoseEngine = new MediaPipePoseEngine(appContext);
        this.mediaPipeHandEngine = new MediaPipeHandEngine(appContext);
        this.faceRecognition = new FaceRecognitionService(appContext);
    }

    public LiveData<DetectionResult> getDetectionResult() {
        return detectionResult;
    }

    public LiveData<BehaviorState> getBehaviorState() {
        return behaviorState;
    }

    public BehaviorStateTracker getBehaviorTracker() {
        return behaviorTracker;
    }

    public void initialize(@Nullable Runnable onComplete) {
        analysisExecutor.execute(() -> {
            try {
                mlKitFaceEngine.initialize();
                mediaPipeFaceEngine.initialize();
                mediaPipePoseEngine.initialize();
                mediaPipeHandEngine.initialize();
                faceRecognition.initialize();
            } catch (Exception e) {
                Log.e(TAG, "Engine init failed", e);
            }
            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    public void bindCamera(@NonNull LifecycleOwner lifecycleOwner, @NonNull PreviewView previewView) {
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        ensureCameraProvider(this::bindUseCases);
    }

    /** Analysis-only binding for virtual-pet mode (no preview surface). */
    public void startAnalysis(@NonNull LifecycleOwner lifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = null;
        ensureCameraProvider(this::bindUseCases);
    }

    private void ensureCameraProvider(@NonNull Runnable whenReady) {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(appContext);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                whenReady.run();
            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed", e);
            }
        }, ContextCompat.getMainExecutor(appContext));
    }

    public void unbindCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        boundCamera = null;
    }

    public void dispose() {
        unbindCamera();
        analysisExecutor.execute(() -> {
            mlKitFaceEngine.dispose();
            mediaPipeFaceEngine.dispose();
            mediaPipePoseEngine.dispose();
            mediaPipeHandEngine.dispose();
            faceRecognition.dispose();
        });
        analysisExecutor.shutdown();
        mlExecutor.shutdown();
        identitySlots.clear();
        if (lastFaceCrop != null && !lastFaceCrop.isRecycled()) {
            lastFaceCrop.recycle();
        }
        lastFaceCrop = null;
    }

    public void onSettingsChanged(DisplaySettings newSettings) {
        if (cameraProvider != null && lifecycleOwner != null) {
            bindUseCases();
        }
    }

    public interface FrameListener {
        void onFrame(DetectionResult result, BehaviorStateTracker.BehaviorSnapshot snapshot);
    }

    @Nullable private FrameListener frameListener;

    public void setFrameListener(@Nullable FrameListener listener) {
        this.frameListener = listener;
    }

    public interface EnrollmentCallback {
        void onResult(boolean success, @Nullable List<Double> embedding, @Nullable String message);
    }

    public static final class FaceCapture {
        public final List<Double> embedding;
        public final Bitmap thumb;

        public FaceCapture(List<Double> embedding, Bitmap thumb) {
            this.embedding = embedding;
            this.thumb = thumb;
        }
    }

    public interface FaceCaptureCallback {
        void onResult(@Nullable FaceCapture capture, @Nullable String error);
    }

    public FaceRecognitionService getFaceRecognition() {
        return faceRecognition;
    }

    /** Capture embedding from the most recent primary face crop (for enrollment UI). */
    public void captureEnrollmentSample(@NonNull EnrollmentCallback callback) {
        captureFaceSample(0L, (capture, error) -> {
            if (capture == null || capture.embedding == null) {
                callback.onResult(false, null, error != null ? error : "未检测到人脸，请正对摄像头");
            } else {
                callback.onResult(true, capture.embedding, null);
            }
        });
    }

    /**
     * Wait up to {@code timeoutMs} for a face crop, then embed. Mirrors Flutter
     * {@code captureFaceSample(timeout: ...)}.
     */
    public void captureFaceSample(long timeoutMs, @NonNull FaceCaptureCallback callback) {
        analysisExecutor.execute(() -> {
            if (!faceRecognition.isAvailable()) {
                mainHandler.post(() -> callback.onResult(null, "身份识别模型未加载"));
                return;
            }
            long deadline = timeoutMs > 0
                    ? System.currentTimeMillis() + timeoutMs
                    : System.currentTimeMillis();
            if (timeoutMs <= 0) {
                deadline = System.currentTimeMillis();
            }
            while (System.currentTimeMillis() <= deadline) {
                Bitmap crop = lastFaceCrop;
                if (crop != null && !crop.isRecycled()) {
                    Bitmap thumb = crop.copy(Bitmap.Config.ARGB_8888, false);
                    if (thumb != null) {
                        Bitmap forEmbed = thumb.copy(Bitmap.Config.ARGB_8888, false);
                        List<Double> embedding = faceRecognition.embed(forEmbed);
                        if (embedding != null && !embedding.isEmpty()) {
                            mainHandler.post(() -> callback.onResult(
                                    new FaceCapture(embedding, thumb), null));
                            return;
                        }
                        thumb.recycle();
                    }
                }
                if (timeoutMs <= 0) {
                    break;
                }
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            mainHandler.post(() -> callback.onResult(null, "未检测到人脸，请正对摄像头"));
        });
    }

    private void bindUseCases() {
        if (cameraProvider == null || lifecycleOwner == null) {
            return;
        }
        cameraProvider.unbindAll();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_TARGET)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this);

        CameraSelector selector = settings.useFrontCamera
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        if (previewView != null) {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, analysis);
        } else {
            boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, analysis);
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        if (now - lastSampleMs < AppTuning.SAMPLE_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        lastSampleMs = now;

        mlExecutor.execute(() -> {
            try {
                int rotation = CameraImageUtils.rotationDegreesForFrame(imageProxy);
                boolean needsUpright = settings.faceEnabled
                        || (settings.identityEnabled && faceRecognition.isAvailable());
                processFrame(imageProxy, rotation, needsUpright, now);
            } catch (Exception e) {
                Log.e(TAG, "processFrame error", e);
            } finally {
                imageProxy.close();
                processing.set(false);
            }
        });
    }

    private void processFrame(
            ImageProxy imageProxy,
            int rotationDegrees,
            boolean needsUpright,
            long nowMs) {
        boolean needsMediaPipe = settings.faceEnabled
                || (settings.handEnabled && mediaPipeHandEngine.isAvailable())
                || (settings.poseEnabled && mediaPipePoseEngine.isAvailable());
        boolean needsBitmap = needsMediaPipe || needsUpright;

        Bitmap uprightBitmap = null;
        MPImage mpImage = null;
        if (needsBitmap) {
            uprightBitmap = CameraImageUtils.toUprightBitmap(imageProxy, rotationDegrees);
            if (uprightBitmap != null && needsMediaPipe) {
                mpImage = MediaPipeImageUtils.fromBitmap(uprightBitmap);
            }
        }

        try {
            FaceOverlay mediaPipeFace = settings.faceEnabled
                    ? mediaPipeFaceEngine.process(mpImage)
                    : null;
            List<HandOverlay> hands = settings.handEnabled
                    ? mediaPipeHandEngine.process(mpImage)
                    : Collections.emptyList();
            List<PoseOverlay> poses = settings.poseEnabled
                    ? mediaPipePoseEngine.process(mpImage)
                    : Collections.emptyList();
            List<RectF> mlKitBoxes = (settings.faceEnabled && uprightBitmap != null)
                    ? mlKitFaceEngine.processBitmap(uprightBitmap)
                    : Collections.emptyList();

            List<FaceOverlay> faces = mergeFaces(mlKitBoxes, mediaPipeFace);

            if (!faces.isEmpty() && uprightBitmap != null && faces.get(0).boundingBox != null) {
                Bitmap crop = CameraImageUtils.cropNormalized(
                        uprightBitmap, faces.get(0).boundingBox, 0.2f);
                if (crop != null) {
                    if (lastFaceCrop != null && !lastFaceCrop.isRecycled()) {
                        lastFaceCrop.recycle();
                    }
                    lastFaceCrop = crop.copy(Bitmap.Config.ARGB_8888, false);
                    crop.recycle();
                }
            }

            boolean identityDue = settings.identityEnabled
                    && faceRecognition.isAvailable()
                    && (nowMs - lastIdentityRunMs) >= AppTuning.IDENTITY_INTERVAL_MS;

            if (!faces.isEmpty() && identityDue && uprightBitmap != null) {
                lastIdentityRunMs = nowMs;
                Map<RectF, IdentityMatch> frameIdentities = new HashMap<>();
                for (FaceOverlay face : faces) {
                    Bitmap crop = CameraImageUtils.cropNormalized(uprightBitmap, face.boundingBox, 0.2f);
                    List<Double> embedding = crop != null ? faceRecognition.embed(crop) : null;
                    IdentityMatch match = embedding != null
                            ? faceRecognition.identify(embedding, personRepository.getPeople())
                            : null;
                    frameIdentities.put(face.boundingBox, match);
                    if (crop != null) {
                        crop.recycle();
                    }
                }
                assignSlots(frameIdentities, nowMs);
            }

            List<IdentitySlot> slotForFace = matchSlotsToBoxes(faces, nowMs);
            List<FaceOverlay> resultFaces = new ArrayList<>(faces.size());
            for (int i = 0; i < faces.size(); i++) {
                FaceOverlay face = faces.get(i);
                IdentitySlot slot = i < slotForFace.size() ? slotForFace.get(i) : null;
                IdentityMatch identity = slot != null ? slot.identity : null;
                if (identity == null) {
                    resultFaces.add(face);
                } else {
                    resultFaces.add(new FaceOverlay(
                            face.landmarks,
                            face.boundingBox,
                            face.expression,
                            identity,
                            face.gazeX,
                            face.gazeY,
                            face.eyeBlink,
                            face.mouthOpenness));
                }
            }

            boolean mirror = settings.useFrontCamera
                    && settings.mirrorFrontCamera
                    && CameraImageUtils.shouldFlipFrontCameraHorizontal();

            DetectionResult result = new DetectionResult(resultFaces, hands, poses, mirror);
            BehaviorStateTracker.BehaviorSnapshot snapshot =
                    behaviorTracker.update(result, nowMs);

            mainHandler.post(() -> {
                detectionResult.setValue(result);
                behaviorState.setValue(snapshot.state);
                if (frameListener != null) {
                    frameListener.onFrame(result, snapshot);
                }
            });
        } finally {
            if (mpImage != null) {
                mpImage.close();
            }
            if (uprightBitmap != null && !uprightBitmap.isRecycled()) {
                uprightBitmap.recycle();
            }
        }
    }

    private static List<FaceOverlay> mergeFaces(List<RectF> mlKitBoxes, @Nullable FaceOverlay mediaPipeFace) {
        List<FaceOverlay> faces = new ArrayList<>();
        if (!mlKitBoxes.isEmpty()) {
            for (RectF box : mlKitBoxes) {
                faces.add(new FaceOverlay(
                        Collections.emptyList(),
                        new RectF(box),
                        ExpressionResult.NEUTRAL));
            }
            if (mediaPipeFace != null) {
                int bestIdx = -1;
                float bestIou = 0f;
                for (int i = 0; i < faces.size(); i++) {
                    float iou = CameraImageUtils.iou(faces.get(i).boundingBox, mediaPipeFace.boundingBox);
                    if (iou > bestIou) {
                        bestIou = iou;
                        bestIdx = i;
                    }
                }
                if (bestIdx >= 0) {
                    FaceOverlay target = faces.get(bestIdx);
                    faces.set(bestIdx, new FaceOverlay(
                            mediaPipeFace.landmarks,
                            mediaPipeFace.boundingBox,
                            mediaPipeFace.expression,
                            target.identity,
                            mediaPipeFace.gazeX,
                            mediaPipeFace.gazeY,
                            mediaPipeFace.eyeBlink,
                            mediaPipeFace.mouthOpenness));
                }
            }
        } else if (mediaPipeFace != null) {
            faces.add(mediaPipeFace);
        }
        return faces;
    }

    private void assignSlots(Map<RectF, IdentityMatch> frameIdentities, long nowMs) {
        identitySlots.removeIf(slot -> nowMs - slot.lastSeenMs > AppTuning.IDENTITY_TTL_MS);

        List<SlotPair> pairs = new ArrayList<>();
        for (Map.Entry<RectF, IdentityMatch> entry : frameIdentities.entrySet()) {
            PointF center = CameraImageUtils.center(entry.getKey());
            for (IdentitySlot slot : identitySlots) {
                float dist = CameraImageUtils.distance(slot.center, center);
                if (dist < AppTuning.SLOT_MATCH_DISTANCE) {
                    pairs.add(new SlotPair(dist, entry.getKey(), slot));
                }
            }
        }
        pairs.sort((a, b) -> Float.compare(a.distance, b.distance));

        Set<RectF> usedBoxes = new HashSet<>();
        Set<IdentitySlot> usedSlots = new HashSet<>();
        for (SlotPair pair : pairs) {
            if (usedBoxes.contains(pair.box) || usedSlots.contains(pair.slot)) {
                continue;
            }
            usedBoxes.add(pair.box);
            usedSlots.add(pair.slot);
            pair.slot.center = CameraImageUtils.center(pair.box);
            pair.slot.lastSeenMs = nowMs;
            IdentityMatch match = frameIdentities.get(pair.box);
            if (match != null) {
                pair.slot.identity = match;
            }
        }

        for (Map.Entry<RectF, IdentityMatch> entry : frameIdentities.entrySet()) {
            if (usedBoxes.contains(entry.getKey())) {
                continue;
            }
            IdentitySlot slot = new IdentitySlot(CameraImageUtils.center(entry.getKey()), nowMs);
            if (entry.getValue() != null) {
                slot.identity = entry.getValue();
            }
            identitySlots.add(slot);
        }
    }

    private List<IdentitySlot> matchSlotsToBoxes(List<FaceOverlay> faces, long nowMs) {
        List<IdentitySlot> result = new ArrayList<>(Collections.nCopies(faces.size(), null));
        List<SlotPairIndexed> pairs = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            PointF center = CameraImageUtils.center(faces.get(i).boundingBox);
            for (IdentitySlot slot : identitySlots) {
                if (nowMs - slot.lastSeenMs > AppTuning.IDENTITY_TTL_MS) {
                    continue;
                }
                float dist = CameraImageUtils.distance(slot.center, center);
                if (dist < AppTuning.SLOT_MATCH_DISTANCE) {
                    pairs.add(new SlotPairIndexed(dist, i, slot));
                }
            }
        }
        pairs.sort((a, b) -> Float.compare(a.distance, b.distance));
        Set<IdentitySlot> usedSlots = new HashSet<>();
        for (SlotPairIndexed pair : pairs) {
            if (result.get(pair.faceIndex) != null || usedSlots.contains(pair.slot)) {
                continue;
            }
            result.set(pair.faceIndex, pair.slot);
            usedSlots.add(pair.slot);
        }
        return result;
    }

    private static final class IdentitySlot {
        PointF center;
        long lastSeenMs;
        IdentityMatch identity;

        IdentitySlot(PointF center, long lastSeenMs) {
            this.center = center;
            this.lastSeenMs = lastSeenMs;
        }
    }

    private static final class SlotPair {
        final float distance;
        final RectF box;
        final IdentitySlot slot;

        SlotPair(float distance, RectF box, IdentitySlot slot) {
            this.distance = distance;
            this.box = box;
            this.slot = slot;
        }
    }

    private static final class SlotPairIndexed {
        final float distance;
        final int faceIndex;
        final IdentitySlot slot;

        SlotPairIndexed(float distance, int faceIndex, IdentitySlot slot) {
            this.distance = distance;
            this.faceIndex = faceIndex;
            this.slot = slot;
        }
    }
}
