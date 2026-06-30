package com.xbot.xbot.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.xbot.xbot.base.BaseService;
import com.xbot.xbot.core.AppPhase;
import com.xbot.xbot.core.AppTuning;
import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.data.OwnerProfile;
import com.xbot.xbot.data.PersonEntity;
import com.xbot.xbot.data.PersonRepository;
import com.xbot.xbot.data.RoomConverters;
import com.xbot.xbot.data.PophieConfigStore;
import com.xbot.xbot.data.SettingsStore;
import com.xbot.xbot.face.BehaviorStateTracker;
import com.xbot.xbot.face.GazeTriggerTracker;
import com.xbot.xbot.face.GazeZoneDetector;
import com.xbot.xbot.logging.PersonaLogServer;
import com.xbot.xbot.logging.PersonaLogger;
import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.model.Expression;
import com.xbot.xbot.model.FaceOverlay;
import com.xbot.xbot.model.IdentityMatch;
import com.xbot.xbot.perception.PerceptionPipeline;
import com.xbot.xbot.voice.PophieClient;
import com.xbot.xbot.voice.VoiceAssistant;
import com.xbot.xbot.voice.VoiceState;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppViewModel extends AndroidViewModel {
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<AppPhase> phase = new MutableLiveData<>(AppPhase.LOADING);
    private final MutableLiveData<Double> loadingProgress = new MutableLiveData<>(0.0);
    private final MutableLiveData<String> loadingMessage = new MutableLiveData<>("准备中…");
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<DisplaySettings> displaySettingsLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> peopleRevision = new MutableLiveData<>(0);

    private final SettingsStore settingsStore;
    private final PophieConfigStore pophieConfigStore;
    private final PersonRepository personRepository;
    private final PerceptionPipeline perceptionPipeline;
    private final VoiceAssistant voiceAssistant;
    private final BaseService baseService;
    private final PersonaLogger personaLogger;
    private final GazeZoneDetector gazeZoneDetector = new GazeZoneDetector();
    private final GazeTriggerTracker gazeTriggerTracker = new GazeTriggerTracker();

    private PersonaLogServer logServer;
    private DisplaySettings displaySettings;
    private boolean initializing;
    private boolean initialized;
    @Nullable private String lastPerceptionSig;
    private long lastPerceptionLogMs;
    private final List<List<Double>> pendingEnrollmentEmbeddings = new ArrayList<>();

    public AppViewModel(@NonNull Application application) {
        super(application);
        settingsStore = new SettingsStore(application);
        pophieConfigStore = new PophieConfigStore(application);
        displaySettings = settingsStore.loadDisplaySettings();
        displaySettingsLiveData.setValue(displaySettings);
        personRepository = new PersonRepository(application);
        perceptionPipeline = new PerceptionPipeline(
                application, displaySettings, personRepository);
        voiceAssistant = new VoiceAssistant(application);
        baseService = new BaseService();
        personaLogger = new PersonaLogger(application);
        perceptionPipeline.setFrameListener(this::onPerceptionFrame);
    }

    public LiveData<AppPhase> getPhase() {
        return phase;
    }

    public LiveData<Double> getLoadingProgress() {
        return loadingProgress;
    }

    public LiveData<String> getLoadingMessage() {
        return loadingMessage;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    public LiveData<DisplaySettings> getDisplaySettings() {
        return displaySettingsLiveData;
    }

    public LiveData<Integer> getPeopleRevision() {
        return peopleRevision;
    }

    public LiveData<DetectionResult> getDetectionResult() {
        return perceptionPipeline.getDetectionResult();
    }

    public LiveData<BehaviorState> getBehaviorState() {
        return perceptionPipeline.getBehaviorState();
    }

    public DisplaySettings getSettings() {
        return displaySettings;
    }

    public PerceptionPipeline getPerceptionPipeline() {
        return perceptionPipeline;
    }

    public VoiceAssistant getVoiceAssistant() {
        return voiceAssistant;
    }

    public BaseService getBaseService() {
        return baseService;
    }

    public PersonaLogger getPersonaLogger() {
        return personaLogger;
    }

    public PersonRepository getPersonRepository() {
        return personRepository;
    }

    public PophieConfigStore getPophieConfigStore() {
        return pophieConfigStore;
    }

    public GazeZoneDetector getGazeZoneDetector() {
        return gazeZoneDetector;
    }

    public boolean isFrontCamera() {
        return displaySettings.useFrontCamera;
    }

    public OwnerProfile getOwnerProfile() {
        return settingsStore.getOwnerProfile();
    }

    public boolean isOwnerRegistered() {
        return settingsStore.isOwnerRegistered();
    }

    public String getRobotDisplayName() {
        OwnerProfile p = settingsStore.getOwnerProfile();
        return p != null && p.robotName != null ? p.robotName : "狗蛋";
    }

    public String getPophieBaseUrl() {
        return pophieConfigStore.getBaseUrl();
    }

    public void setPophieBaseUrl(String url) {
        pophieConfigStore.setBaseUrl(url);
    }

    public void initialize() {
        if (initializing || initialized) {
            return;
        }
        initializing = true;
        phase.setValue(AppPhase.LOADING);

        initExecutor.execute(() -> {
            try {
                postLoading(0.05, "初始化服务…");
                personaLogger.enabled = displaySettings.personaLogEnabled;
                CountDownLatch loggerReady = new CountDownLatch(1);
                personaLogger.initialize(loggerReady::countDown);
                loggerReady.await(10, TimeUnit.SECONDS);

                postLoading(0.2, "加载配置…");
                pophieConfigStore.load();
                voiceAssistant.initialize(displaySettings);
                voiceAssistant.setPerceptionProvider(() -> buildPerception());
                voiceAssistant.getPophie().setConfig(pophieConfigStore.getConfig());
                voiceAssistant.setOnSessionPersist(sessionId -> pophieConfigStore.setSessionId(sessionId));
                voiceAssistant.setOnInteraction(this::logVoiceInteraction);
                voiceAssistant.setUserIdProvider(() -> {
                    OwnerProfile owner = settingsStore.getOwnerProfile();
                    return owner != null ? owner.nickname : null;
                });

                postLoading(0.4, "启动感知管线…");
                CountDownLatch enginesReady = new CountDownLatch(1);
                perceptionPipeline.initialize(enginesReady::countDown);
                enginesReady.await(60, TimeUnit.SECONDS);

                postLoading(0.7, "加载人物库…");
                CountDownLatch peopleLoaded = new CountDownLatch(1);
                personRepository.load(() -> {
                    bumpPeopleRevision();
                    peopleLoaded.countDown();
                });
                peopleLoaded.await(30, TimeUnit.SECONDS);

                postLoading(0.85, "读取主人档案…");
                settingsStore.loadOwnerProfile();

                if (displaySettings.personaLogServerEnabled) {
                    startLogServer();
                }

                postLoading(1.0, "准备就绪");
                Thread.sleep(AppTuning.READY_BUFFER_MS);

                initialized = true;
                AppPhase next = settingsStore.isOwnerRegistered()
                        ? AppPhase.READY
                        : AppPhase.ONBOARDING;
                getApplication().getMainExecutor().execute(() -> {
                    phase.setValue(next);
                    if (next == AppPhase.READY && displaySettings.voiceEnabled) {
                        voiceAssistant.start();
                    }
                });
            } catch (Exception e) {
                getApplication().getMainExecutor().execute(() -> {
                    phase.setValue(AppPhase.ERROR);
                    errorMessage.setValue("初始化失败：" + e.getMessage());
                });
            } finally {
                initializing = false;
            }
        });
    }

    public void onPermissionsDenied() {
        phase.setValue(AppPhase.PERMISSION_DENIED);
        errorMessage.setValue("未获得摄像头或麦克风权限，请在系统设置中开启后重试。");
    }

    public void updateSettings(@NonNull Runnable mutator) {
        mutator.run();
        settingsStore.saveDisplaySettings(displaySettings);
        displaySettingsLiveData.postValue(displaySettings);
        voiceAssistant.onSettingsChanged(displaySettings);
        perceptionPipeline.onSettingsChanged(displaySettings);
        personaLogger.setEnabled(displaySettings.personaLogEnabled);
        if (displaySettings.personaLogServerEnabled) {
            startLogServer();
        } else {
            stopLogServer();
        }
        if (displaySettings.voiceEnabled && voiceAssistant.isAvailable()) {
            voiceAssistant.start();
        } else {
            voiceAssistant.stop();
        }
    }

    public void completeOnboarding(@NonNull OwnerProfile profile) throws IOException {
        settingsStore.saveOwnerProfile(profile);
        phase.setValue(AppPhase.READY);
        if (displaySettings.voiceEnabled && voiceAssistant.isAvailable()) {
            voiceAssistant.start();
        }
    }

    public void resetOwnerAndRestartOnboarding() {
        OwnerProfile owner = settingsStore.getOwnerProfile();
        String personId = owner != null ? owner.personId : null;
        settingsStore.clearOwnerProfile();
        settingsStore.loadOwnerProfile();
        Runnable finish = () -> getApplication().getMainExecutor().execute(() ->
                phase.setValue(AppPhase.ONBOARDING));
        if (personId != null) {
            personRepository.delete(personId, () -> {
                bumpPeopleRevision();
                finish.run();
            });
        } else {
            finish.run();
        }
    }

    public boolean isFaceRecognitionAvailable() {
        return perceptionPipeline.getFaceRecognition().isAvailable();
    }

    @Nullable
    public String getFaceRecognitionStatus() {
        return perceptionPipeline.getFaceRecognition().getStatusMessage();
    }

    @Nullable
    public IdentityMatch findExistingIdentity(List<Double> embedding) {
        return perceptionPipeline.getFaceRecognition().identify(
                embedding, personRepository.getPeople());
    }

    public void savePerson(@NonNull PersonEntity person, @Nullable Runnable onComplete) {
        personRepository.upsert(person, () -> {
            bumpPeopleRevision();
            if (onComplete != null) {
                getApplication().getMainExecutor().execute(onComplete);
            }
        });
    }

    public void deletePerson(@NonNull String id, @Nullable Runnable onComplete) {
        personRepository.delete(id, () -> {
            bumpPeopleRevision();
            if (onComplete != null) {
                getApplication().getMainExecutor().execute(onComplete);
            }
        });
    }

    public String saveAvatar(@NonNull String personId, @NonNull Bitmap bitmap) throws IOException {
        File file = new File(personRepository.avatarsDir(), personId + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return file.getAbsolutePath();
    }

    public static final class FaceCapture {
        public final List<Double> embedding;
        public final Bitmap thumb;

        FaceCapture(List<Double> embedding, Bitmap thumb) {
            this.embedding = embedding;
            this.thumb = thumb;
        }
    }

    public interface FaceCaptureListener {
        void onComplete(@Nullable FaceCapture capture, @Nullable String error);
    }

    public void captureFaceSample(long timeoutMs, @NonNull FaceCaptureListener listener) {
        perceptionPipeline.captureFaceSample(timeoutMs, (capture, error) -> {
            if (capture == null) {
                listener.onComplete(null, error);
                return;
            }
            listener.onComplete(new FaceCapture(capture.embedding, capture.thumb), null);
        });
    }

    private void bumpPeopleRevision() {
        Integer current = peopleRevision.getValue();
        peopleRevision.postValue(current != null ? current + 1 : 1);
    }

    public interface EnrollmentListener {
        void onComplete(boolean success, @Nullable String message);
    }

    public void captureFaceEnrollmentSample(@NonNull EnrollmentListener listener) {
        perceptionPipeline.captureEnrollmentSample((success, embedding, message) -> {
            if (!success || embedding == null) {
                getApplication().getMainExecutor().execute(() ->
                        listener.onComplete(false, message));
                return;
            }
            pendingEnrollmentEmbeddings.add(embedding);
            getApplication().getMainExecutor().execute(() ->
                    listener.onComplete(true, null));
        });
    }

    public void finalizeFaceEnrollment(@Nullable Runnable onComplete) {
        OwnerProfile profile = settingsStore.getOwnerProfile();
        if (profile == null) {
            profile = new OwnerProfile();
        }
        final OwnerProfile owner = profile;
        String personId = owner.personId != null ? owner.personId : UUID.randomUUID().toString();
        String name = owner.nickname != null ? owner.nickname : "主人";
        List<List<Double>> embeddings = new ArrayList<>(pendingEnrollmentEmbeddings);
        pendingEnrollmentEmbeddings.clear();

        PersonEntity entity = new PersonEntity();
        entity.id = personId;
        entity.name = name;
        entity.relation = "owner";
        entity.embeddingsJson = RoomConverters.embeddingsToJson(embeddings);
        entity.createdAt = System.currentTimeMillis();
        final boolean registered = !embeddings.isEmpty();

        personRepository.upsert(entity, () -> {
            owner.personId = personId;
            owner.faceRegistered = registered;
            try {
                settingsStore.saveOwnerProfile(owner);
            } catch (IOException ignored) {
            }
            if (onComplete != null) {
                getApplication().getMainExecutor().execute(onComplete);
            }
        });
    }

    public void triggerVoiceFromDoubleTap() {
        VoiceAssistant voice = voiceAssistant;
        if (!voice.isAvailable()) {
            return;
        }
        if (!voice.isRunning()) {
            voice.start();
        }
        VoiceState state = voice.getState().getValue();
        if (voice.isRunning() && (state == null || state == VoiceState.IDLE)) {
            voice.onDoubleTap();
        }
    }

    private void onPerceptionFrame(
            DetectionResult result,
            BehaviorStateTracker.BehaviorSnapshot snapshot) {
        maybeLogPerception(result, snapshot);
        maybeLogBehavior(result, snapshot);
        maybeGazeTrigger(result, snapshot.state);
    }

    private void maybeLogPerception(
            DetectionResult result,
            BehaviorStateTracker.BehaviorSnapshot snapshot) {
        if (!displaySettings.personaLogEnabled || !personaLogger.enabled) {
            return;
        }
        FaceOverlay face = result.getPrimaryFace();
        String person = face != null && face.identity != null ? face.identity.person.name : null;
        String expression = face != null && face.expression != null
                ? face.expression.expression.getLabel()
                : null;
        String gesture = !result.hands.isEmpty() && result.hands.get(0).gesture != null
                ? result.hands.get(0).gesture
                : null;
        int faceCount = result.faces.size();
        if (faceCount == 0 && gesture == null) {
            return;
        }
        String sig = person + "|" + expression + "|" + gesture + "|" + faceCount;
        long now = System.currentTimeMillis();
        boolean changed = !sig.equals(lastPerceptionSig);
        boolean elapsedOk = now - lastPerceptionLogMs >= AppTuning.PERCEPTION_MIN_INTERVAL_MS;
        if (!changed || !elapsedOk) {
            return;
        }
        lastPerceptionSig = sig;
        lastPerceptionLogMs = now;
        personaLogger.log(new PersonaLogger.PersonaLogEntry(
                new Date(now),
                "perception",
                person,
                expression,
                gesture,
                null,
                null,
                null,
                null,
                snapshot.state.name().toLowerCase(),
                null));
    }

    private void maybeLogBehavior(
            DetectionResult result,
            BehaviorStateTracker.BehaviorSnapshot snapshot) {
        if (!displaySettings.personaLogEnabled || !personaLogger.enabled) {
            return;
        }
        BehaviorStateTracker.BehaviorTransition tr = snapshot.transition;
        if (tr == null) {
            return;
        }
        FaceOverlay face = result.getPrimaryFace();
        String person = face != null && face.identity != null ? face.identity.person.name : null;
        String note = tr.from.name().toLowerCase() + "→" + tr.to.name().toLowerCase()
                + "（上一状态持续 " + (tr.previousDurationMs / 1000) + " 秒）";
        personaLogger.log(new PersonaLogger.PersonaLogEntry(
                new Date(),
                "state",
                person,
                snapshot.dominantExpression.getLabel(),
                null,
                null,
                null,
                null,
                null,
                note,
                null));
    }

    private void logVoiceInteraction(
            String userText,
            String replyText,
            @Nullable String robotState) {
        if (!displaySettings.personaLogEnabled || !personaLogger.enabled) {
            return;
        }
        DetectionResult result = getDetectionResult().getValue();
        FaceOverlay face = result != null ? result.getPrimaryFace() : null;
        String person = face != null && face.identity != null ? face.identity.person.name : null;
        personaLogger.log(new PersonaLogger.PersonaLogEntry(
                new Date(),
                "voice",
                person,
                null,
                null,
                null,
                userText,
                replyText,
                robotState,
                null,
                null));
    }

    private void maybeGazeTrigger(DetectionResult result, BehaviorState behavior) {
        FaceOverlay face = result.getPrimaryFace();
        VoiceAssistant voice = voiceAssistant;
        VoiceState voiceState = voice.getState().getValue();
        boolean voiceIdle = voiceState == null || voiceState == VoiceState.IDLE;
        gazeTriggerTracker.update(
                face,
                behavior,
                displaySettings.gazeTriggerEnabled && displaySettings.voiceEnabled,
                voice.isRunning(),
                voiceIdle,
                System.currentTimeMillis(),
                voice::onGazeTrigger);
    }

    private PophieClient.PophiePerception buildPerception() {
        DetectionResult result = getDetectionResult().getValue();
        if (result == null) {
            return null;
        }
        FaceOverlay face = result.getPrimaryFace();
        PophieClient.PophiePerception p = new PophieClient.PophiePerception();
        if (face != null && face.expression != null) {
            p.facialExpression = mapExpression(face.expression.expression);
        }
        if (face != null && face.identity != null) {
            p.identity = face.identity.person.name;
        }
        if (!result.hands.isEmpty() && result.hands.get(0).gesture != null) {
            p.gestureType = result.hands.get(0).gesture;
        }
        return p;
    }

    private static String mapExpression(Expression e) {
        if (e == null) {
            return "neutral";
        }
        return e.getApiKey();
    }

    private void startLogServer() {
        try {
            if (logServer == null) {
                logServer = new PersonaLogServer(personaLogger);
            }
            if (!logServer.isAlive()) {
                logServer.startServer();
            }
        } catch (IOException e) {
            // non-fatal
        }
    }

    private void stopLogServer() {
        if (logServer != null) {
            logServer.stopServer();
        }
    }

    private void postLoading(double progress, String message) {
        getApplication().getMainExecutor().execute(() -> {
            loadingProgress.setValue(progress);
            loadingMessage.setValue(message);
        });
    }

    @Override
    protected void onCleared() {
        perceptionPipeline.setFrameListener(null);
        perceptionPipeline.dispose();
        voiceAssistant.release();
        baseService.release();
        stopLogServer();
        personaLogger.release();
        initExecutor.shutdownNow();
        super.onCleared();
    }
}
