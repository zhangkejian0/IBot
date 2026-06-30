package com.xbot.xbot.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams PCM16 chunks to {@link AudioTrack} for low-latency TTS playback.
 */
public class StreamingTtsPlayer {
    private static final String TAG = "StreamingTts";

    private final AudioCaptureService audio;
    private final ExecutorService playback = Executors.newSingleThreadExecutor();
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean feedingDone = new AtomicBoolean(false);

    @Nullable private AudioTrack track;
    private int sampleRate = 22050;
    private double lastLevel;

    public StreamingTtsPlayer(AudioCaptureService audio) {
        this.audio = audio;
    }

    public boolean isActive() {
        return active.get();
    }

    public void start(int sampleRate) {
        if (active.get()) {
            return;
        }
        this.sampleRate = sampleRate;
        queue.clear();
        feedingDone.set(false);
        audio.setExternalLevel(true);

        int minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuf, sampleRate / 5 * 2);

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        active.set(true);
        playback.execute(this::drainLoop);
    }

    public void feedChunk(byte[] pcm16) {
        if (!active.get() || pcm16.length == 0) {
            return;
        }
        queue.offer(pcm16);
    }

    public void markFeedingDone() {
        feedingDone.set(true);
    }

    public boolean waitForDone(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (active.get() && System.currentTimeMillis() < deadline) {
            if (feedingDone.get() && queue.isEmpty()) {
                Thread.sleep(50);
                if (queue.isEmpty()) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return !active.get();
    }

    public void release() {
        if (!active.compareAndSet(true, false)) {
            resetLevel();
            return;
        }
        queue.clear();
        AudioTrack local = track;
        track = null;
        if (local != null) {
            try {
                local.stop();
            } catch (IllegalStateException ignored) {
            }
            local.release();
        }
        resetLevel();
    }

    private void drainLoop() {
        while (active.get()) {
            try {
                byte[] chunk = queue.poll(100, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (feedingDone.get() && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                AudioTrack local = track;
                if (local == null) {
                    break;
                }
                updateLevel(chunk);
                local.write(chunk, 0, chunk.length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.d(TAG, "drain loop finished");
    }

    private void updateLevel(byte[] chunk) {
        float raw = AudioCaptureService.rmsLevel(chunk);
        lastLevel = lastLevel * 0.6 + raw * 0.4;
        audio.postLevel((float) lastLevel);
    }

    private void resetLevel() {
        audio.setExternalLevel(false);
        audio.postLevel(0f);
        lastLevel = 0;
    }

    public void dispose() {
        release();
        playback.shutdownNow();
    }
}
