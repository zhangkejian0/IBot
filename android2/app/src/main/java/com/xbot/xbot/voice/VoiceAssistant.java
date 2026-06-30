package com.xbot.xbot.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Voice assistant orchestrator: wake / double-tap / gaze triggers →
 * listen → chat → streaming TTS. Exposes FSM state via LiveData.
 */
public class VoiceAssistant implements WakeWordService.WakeListener {
    private static final String TAG = "VoiceAssistant";

    public final AudioCaptureService audio;
    public final WakeWordService wakeWord;
    public final PophieClient pophie;
    public final StreamingTtsPlayer streamingTts;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MutableLiveData<VoiceState> state = new MutableLiveData<>(VoiceState.IDLE);
    private final MutableLiveData<String> userText = new MutableLiveData<>("");
    private final MutableLiveData<String> replyText = new MutableLiveData<>("");
    private final AtomicBoolean conversationRunning = new AtomicBoolean(false);

    private volatile boolean available;
    private volatile boolean running;
    private volatile boolean wakeWordEnabled = true;
    private volatile boolean ttsEnabled = true;
    /** Default off: half-duplex during TTS (no barge-in). */
    public boolean bargeInEnabled = false;

    @Nullable private PophiePerceptionProvider perceptionProvider;
    @Nullable private UserIdProvider userIdProvider;
    @Nullable private SessionPersistCallback onSessionPersist;
    @Nullable private InteractionCallback onInteraction;
    @Nullable private PophieClient.SttStreamSession activeStt;

    public interface PophiePerceptionProvider {
        @Nullable PophieClient.PophiePerception get();
    }

    public interface UserIdProvider {
        @Nullable String get();
    }

    public interface SessionPersistCallback {
        void onSession(String sessionId);
    }

    public interface InteractionCallback {
        void onInteraction(String userText, String replyText, @Nullable String robotState);
    }

    public VoiceAssistant(Context context) {
        this.audio = new AudioCaptureService(context);
        this.wakeWord = new WakeWordService(context);
        this.pophie = new PophieClient();
        this.streamingTts = new StreamingTtsPlayer(audio);
        this.wakeWord.addListener(this);
    }

    public LiveData<VoiceState> getState() {
        return state;
    }

    public LiveData<String> getUserText() {
        return userText;
    }

    public LiveData<String> getReplyText() {
        return replyText;
    }

    public LiveData<Float> getLevel() {
        return audio.getLevel();
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isRunning() {
        return running;
    }

    public void setWakeWordEnabled(boolean enabled) {
        this.wakeWordEnabled = enabled;
    }

    public void setTtsEnabled(boolean enabled) {
        this.ttsEnabled = enabled;
    }

    public void setPerceptionProvider(@Nullable PophiePerceptionProvider provider) {
        this.perceptionProvider = provider;
    }

    public void setUserIdProvider(@Nullable UserIdProvider provider) {
        this.userIdProvider = provider;
    }

    public void setOnSessionPersist(@Nullable SessionPersistCallback callback) {
        this.onSessionPersist = callback;
    }

    public void setOnInteraction(@Nullable InteractionCallback callback) {
        this.onInteraction = callback;
    }

    public void markAvailable() {
        available = true;
    }

    public void markUnavailable(@Nullable String reason) {
        available = false;
        if (running) {
            stop();
        }
        Log.w(TAG, "unavailable: " + reason);
    }

    public void initialize(com.xbot.xbot.core.DisplaySettings settings) {
        wakeWordEnabled = settings.wakeWordEnabled;
        ttsEnabled = settings.ttsEnabled;
        wakeWord.initialize();
    }

    public void onSettingsChanged(com.xbot.xbot.core.DisplaySettings settings) {
        wakeWordEnabled = settings.wakeWordEnabled;
        ttsEnabled = settings.ttsEnabled;
        if (running) {
            if (wakeWordEnabled) {
                wakeWord.start(audio);
            } else {
                wakeWord.detach(audio);
            }
        }
    }

    public PophieClient getPophie() {
        return pophie;
    }

    public void initialize() {
        wakeWord.initialize();
    }

    public void start() {
        if (running || !available) {
            return;
        }
        running = true;
        setState(VoiceState.IDLE);
        audio.start();
        if (wakeWordEnabled) {
            wakeWord.start(audio);
        }
        Log.d(TAG, "started wakeWord=" + wakeWordEnabled);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        wakeWord.detach(audio);
        if (activeStt != null) {
            activeStt.close(true);
            activeStt = null;
        }
        streamingTts.release();
        audio.stop();
        postUserText("");
        postReplyText("");
        setState(VoiceState.IDLE);
        Log.d(TAG, "stopped");
    }

    /** Double-tap trigger (same as manual trigger). */
    public void onDoubleTap() {
        triggerConversation("double-tap");
    }

    /** Gaze trigger hook from perception layer. */
    public void onGazeTrigger() {
        triggerConversation("gaze");
    }

    public void triggerConversation(String source) {
        if (!running || getStateValue() != VoiceState.IDLE) {
            return;
        }
        Log.d(TAG, "trigger from " + source);
        onWake(wakeWord.getKeyword());
    }

    @Override
    public void onWake(String keyword) {
        if (!running || !conversationRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                runConversation();
            } finally {
                conversationRunning.set(false);
                if (running && wakeWordEnabled) {
                    wakeWord.start(audio);
                }
            }
        });
    }

    private void runConversation() {
        try {
            wakeWord.detach(audio);
            setState(VoiceState.LISTENING);
            postUserText("");
            postReplyText("");

            byte[] pcm;
            try {
                pcm = audio.captureUtterance(
                        12_000, 500, 10_000, 0.15, 0.08, 3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] wav = null;
            if (pcm != null && pcm.length > 0) {
                wav = PophieClient.pcm16ToWav(pcm);
            }

            PophieClient.PophiePerception perception =
                    perceptionProvider != null ? perceptionProvider.get() : null;
            String userId = userIdProvider != null ? userIdProvider.get() : null;
            boolean hasPerception = perception != null && !perception.isEmpty();
            if (wav == null && !hasPerception) {
                Log.d(TAG, "no speech and no perception — skip");
                return;
            }

            setState(VoiceState.THINKING);
            PophieClient.PophieChatResult result = pophie.chat(
                    null,
                    wav,
                    perception,
                    userId,
                    true);

            if (result.sessionId != null) {
                pophie.setSessionId(result.sessionId);
                if (onSessionPersist != null) {
                    main.post(() -> onSessionPersist.onSession(result.sessionId));
                }
            }

            String stt = result.sttText != null && !result.sttText.isEmpty()
                    ? result.sttText
                    : "";
            postUserText(stt);
            postReplyText(result.text);

            if (result.isSilent()) {
                return;
            }

            if (onInteraction != null) {
                main.post(() -> onInteraction.onInteraction(stt, result.text, result.robotState));
            }

            if (ttsEnabled && result.text != null && !result.text.trim().isEmpty()) {
                playStreamingTts(result.text, result.voice);
            }
        } catch (IOException e) {
            Log.e(TAG, "conversation failed", e);
        } finally {
            setState(VoiceState.IDLE);
        }
    }

    private void playStreamingTts(String text, @Nullable java.util.Map<String, Object> voice) {
        setState(VoiceState.SPEAKING);
        if (!bargeInEnabled) {
            audio.stop();
        }
        final int[] sampleRate = {22050};
        final java.util.concurrent.CountDownLatch metaLatch = new java.util.concurrent.CountDownLatch(1);
        try {
            pophie.ttsStream(text, voice, null, new PophieClient.TtsStreamCallbacks() {
                @Override
                public void onMeta(String format, int rate) {
                    sampleRate[0] = rate;
                    streamingTts.start(rate);
                    metaLatch.countDown();
                }

                @Override
                public void onChunk(byte[] pcm) {
                    streamingTts.feedChunk(pcm);
                }

                @Override
                public void onDone(@Nullable Integer firstPacketMs) {
                    streamingTts.markFeedingDone();
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "tts stream error: " + message);
                }
            });
            metaLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            streamingTts.waitForDone(30_000);
        } catch (Exception e) {
            Log.e(TAG, "playStreamingTts failed", e);
        } finally {
            streamingTts.release();
            if (!bargeInEnabled && running) {
                audio.start();
            }
        }
    }

    private VoiceState getStateValue() {
        VoiceState s = state.getValue();
        return s != null ? s : VoiceState.IDLE;
    }

    private void setState(VoiceState s) {
        main.post(() -> state.setValue(s));
    }

    private void postUserText(String text) {
        main.post(() -> userText.setValue(text));
    }

    private void postReplyText(String text) {
        main.post(() -> replyText.setValue(text));
    }

    public void release() {
        stop();
        wakeWord.removeListener(this);
        streamingTts.dispose();
        audio.release();
        pophie.shutdown();
        worker.shutdownNow();
    }
}
