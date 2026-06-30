package com.xbot.xbot.voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wake-word detection: sherpa-onnx KWS when model assets are present,
 * otherwise energy/VAD stub on the PCM stream.
 */
public class WakeWordService {
    private static final String TAG = "WakeWord";
    private static final String MODEL_ASSET_DIR =
            "models/voice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";
    private static final String[] REQUIRED_MODEL_FILES = {
            "tokens.txt",
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    };

    private final Context appContext;
    private final CopyOnWriteArrayList<WakeListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean available;
    private volatile boolean sherpaModelPresent;
    private volatile boolean running;
    private volatile String keyword = "你好";
    @Nullable private String loadError;

    private final AtomicInteger energyConsecutive = new AtomicInteger(0);
    private final AtomicBoolean cooldown = new AtomicBoolean(false);
    private static final double ENERGY_THRESHOLD = 0.35;
    private static final int ENERGY_FRAMES = 8;

    public interface WakeListener {
        void onWake(String keyword);
    }

    public WakeWordService(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isRunning() {
        return running;
    }

    public String getKeyword() {
        return keyword;
    }

    @Nullable
    public String getLoadError() {
        return loadError;
    }

    public boolean isSherpaModelPresent() {
        return sherpaModelPresent;
    }

    public void addListener(WakeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(WakeListener listener) {
        listeners.remove(listener);
    }

    /** Probe assets and mark available/unavailable. */
    public void initialize() {
        sherpaModelPresent = checkSherpaAssets();
        if (sherpaModelPresent) {
            // JNI integration is deferred; model on disk marks capability for future binding.
            available = true;
            loadError = null;
            Log.i(TAG, "sherpa model assets found — KWS marked available (stub until JNI wired)");
        } else {
            available = true;
            loadError = null;
            Log.i(TAG, "sherpa model absent — using energy/VAD wake stub");
        }
    }

    public void markAvailable() {
        available = true;
    }

    public void markUnavailable(@Nullable String reason) {
        available = false;
        loadError = reason;
        stop();
    }

    public void setKeyword(String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            this.keyword = keyword;
        }
    }

    @Nullable private AudioCaptureService.AudioChunkListener pcmListener;

    public void start(AudioCaptureService audio) {
        if (!available || running) {
            return;
        }
        running = true;
        pcmListener = this::onPcm;
        audio.addListener(pcmListener);
        Log.d(TAG, "listening keyword=" + keyword + " sherpa=" + sherpaModelPresent);
    }

    public void stop() {
        running = false;
        energyConsecutive.set(0);
    }

    public void detach(AudioCaptureService audio) {
        stop();
        if (pcmListener != null) {
            audio.removeListener(pcmListener);
            pcmListener = null;
        }
    }

    private void onPcm(byte[] pcm) {
        if (!running || cooldown.get()) {
            return;
        }
        if (sherpaModelPresent) {
            // Placeholder until sherpa-onnx JNI is integrated.
            detectEnergyStub(pcm);
        } else {
            detectEnergyStub(pcm);
        }
    }

    private void detectEnergyStub(byte[] pcm) {
        float level = AudioCaptureService.rmsLevel(pcm);
        if (level >= ENERGY_THRESHOLD) {
            int n = energyConsecutive.incrementAndGet();
            if (n >= ENERGY_FRAMES) {
                fireWake();
                energyConsecutive.set(0);
            }
        } else {
            energyConsecutive.set(0);
        }
    }

    private void fireWake() {
        if (cooldown.compareAndSet(false, true)) {
            Log.i(TAG, "wake detected (stub): " + keyword);
            for (WakeListener listener : listeners) {
                listener.onWake(keyword);
            }
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    cooldown.set(false);
                }
            }, "WakeCooldown").start();
        }
    }

    private boolean checkSherpaAssets() {
        AssetManager assets = appContext.getAssets();
        for (String name : REQUIRED_MODEL_FILES) {
            String path = MODEL_ASSET_DIR + "/" + name;
            try {
                assets.open(path).close();
            } catch (IOException e) {
                Log.d(TAG, "missing asset: " + path);
                return false;
            }
        }
        return true;
    }
}
