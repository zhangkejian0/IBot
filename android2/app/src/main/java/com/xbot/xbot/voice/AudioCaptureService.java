package com.xbot.xbot.voice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microphone capture: 16 kHz mono PCM16 via {@link AudioRecord}.
 */
public class AudioCaptureService {
    private static final String TAG = "AudioCapture";
    public static final int SAMPLE_RATE = 16000;

    private final android.content.Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Float> level = new MutableLiveData<>(0f);
    private final CopyOnWriteArrayList<AudioChunkListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile boolean externalLevel;
    @Nullable private AudioRecord recorder;
    @Nullable private Thread captureThread;

    public AudioCaptureService(android.content.Context context) {
        this.appContext = context.getApplicationContext();
    }

    public LiveData<Float> getLevel() {
        return level;
    }

    public void postLevel(float value) {
        level.postValue(value);
    }

    public void setExternalLevel(boolean externalLevel) {
        this.externalLevel = externalLevel;
    }

    public boolean isRunning() {
        return running;
    }

    public interface AudioChunkListener {
        void onPcmChunk(byte[] pcm16);
    }

    public void addListener(AudioChunkListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AudioChunkListener listener) {
        listeners.remove(listener);
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (running || !hasPermission()) {
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(TAG, "invalid min buffer size");
            return;
        }
        int bufferSize = Math.max(minBuf, SAMPLE_RATE / 10 * 2);
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized");
                releaseRecorder();
                return;
            }
            recorder.startRecording();
            running = true;
            captureThread = new Thread(() -> captureLoop(bufferSize), "AudioCapture");
            captureThread.start();
            Log.d(TAG, "started 16kHz/mono/pcm16");
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "start failed", e);
            releaseRecorder();
        }
    }

    public void stop() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        releaseRecorder();
        level.postValue(0f);
        Log.d(TAG, "stopped");
    }

    private void captureLoop(int bufferSize) {
        byte[] buf = new byte[bufferSize];
        AudioRecord local = recorder;
        while (running && local != null) {
            int read = local.read(buf, 0, buf.length);
            if (read > 0) {
                byte[] chunk = new byte[read];
                System.arraycopy(buf, 0, chunk, 0, read);
                if (!externalLevel) {
                    float lvl = rmsLevel(chunk);
                    Float prev = level.getValue();
                    float smoothed = (prev != null ? prev : 0f) * 0.6f + lvl * 0.4f;
                    level.postValue(smoothed);
                }
                for (AudioChunkListener listener : listeners) {
                    listener.onPcmChunk(chunk);
                }
            }
        }
    }

    /**
     * Capture one utterance with simple energy VAD.
     *
     * @return PCM16 bytes or null if no speech detected.
     */
    @Nullable
    public byte[] captureUtterance(
            long maxDurationMs,
            long silenceTimeoutMs,
            long onsetTimeoutMs,
            double speechThreshold,
            double silenceThreshold,
            int speechOnsetFrames) throws InterruptedException {
        if (!running) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<byte[]> preRoll = new ArrayList<>();
        final boolean[] speechStarted = {false};
        final int[] onsetConsec = {0};
        long startAt = System.currentTimeMillis();
        final long[] lastVoiceAt = {0L};
        final Object lock = new Object();
        final AtomicBoolean done = new AtomicBoolean(false);
        final byte[][] result = new byte[1][];

        AudioChunkListener vadListener = chunk -> {
            synchronized (lock) {
                if (done.get()) {
                    return;
                }
                float lvl = rmsLevel(chunk);
                if (!speechStarted[0]) {
                    if (lvl >= speechThreshold) {
                        onsetConsec[0]++;
                        preRoll.add(chunk);
                        if (onsetConsec[0] >= speechOnsetFrames) {
                            speechStarted[0] = true;
                            for (byte[] b : preRoll) {
                                buffer.write(b, 0, b.length);
                            }
                            preRoll.clear();
                            lastVoiceAt[0] = System.currentTimeMillis();
                        }
                    } else {
                        onsetConsec[0] = 0;
                        preRoll.clear();
                    }
                } else {
                    if (lvl >= silenceThreshold) {
                        lastVoiceAt[0] = System.currentTimeMillis();
                    }
                    buffer.write(chunk, 0, chunk.length);
                }
            }
        };
        addListener(vadListener);
        try {
            while (!done.get()) {
                Thread.sleep(100);
                long now = System.currentTimeMillis();
                synchronized (lock) {
                    if (now - startAt >= maxDurationMs) {
                        result[0] = speechStarted[0] ? buffer.toByteArray() : null;
                        done.set(true);
                        break;
                    }
                    if (!speechStarted[0]) {
                        if (now - startAt >= onsetTimeoutMs) {
                            done.set(true);
                            break;
                        }
                    } else if (lastVoiceAt[0] > 0 && now - lastVoiceAt[0] >= silenceTimeoutMs) {
                        result[0] = buffer.toByteArray();
                        done.set(true);
                    }
                }
            }
            return result[0];
        } finally {
            removeListener(vadListener);
        }
    }

    public static float rmsLevel(byte[] bytes) {
        int frameCount = bytes.length / 2;
        if (frameCount == 0) {
            return 0f;
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double sumSq = 0;
        for (int i = 0; i < frameCount; i++) {
            int s = Math.abs(data.getShort());
            sumSq += (double) s * s;
        }
        double rms = Math.sqrt(sumSq / frameCount);
        return (float) Math.min(1.0, rms / 6000.0);
    }

    public void release() {
        stop();
        io.shutdownNow();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            recorder = null;
        }
    }
}
