package com.xbot.xbot.face;

import androidx.annotation.Nullable;

import com.xbot.xbot.core.AppTuning;
import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.FaceOverlay;

/**
 * Sustained gaze-at-robot detector for voice conversation auto-trigger.
 *
 * <p>Ported from Flutter {@code AppController._maybeGazeTrigger()}.
 */
public class GazeTriggerTracker {
    @Nullable private Long lookingSinceMs;
    @Nullable private Long lostSinceMs;
    @Nullable private Long lastTriggerMs;

    public interface TriggerCallback {
        void onGazeTrigger();
    }

    public void update(
            @Nullable FaceOverlay face,
            @Nullable BehaviorState behavior,
            boolean enabled,
            boolean voiceRunning,
            boolean voiceIdle,
            long nowMs,
            TriggerCallback callback) {
        if (!enabled || !voiceRunning || !voiceIdle) {
            resetLooking();
            return;
        }
        if (lastTriggerMs != null
                && nowMs - lastTriggerMs < AppTuning.GAZE_COOLDOWN_SECONDS * 1000L) {
            return;
        }

        boolean looking = face != null && isLookingAtRobot(face, behavior);
        if (looking) {
            if (lookingSinceMs == null) {
                lookingSinceMs = nowMs;
            }
            lostSinceMs = null;
            long heldSec = (nowMs - lookingSinceMs) / 1000L;
            if (heldSec >= AppTuning.GAZE_TRIGGER_SECONDS) {
                lookingSinceMs = null;
                lastTriggerMs = nowMs;
                if (callback != null) {
                    callback.onGazeTrigger();
                }
            }
        } else {
            if (lostSinceMs == null) {
                lostSinceMs = nowMs;
            }
            if (nowMs - lostSinceMs > AppTuning.GAZE_TOLERANCE_SECONDS * 1000L) {
                lookingSinceMs = null;
            }
        }
    }

    public void reset() {
        resetLooking();
        lastTriggerMs = null;
    }

    private void resetLooking() {
        lookingSinceMs = null;
        lostSinceMs = null;
    }

    private static boolean isLookingAtRobot(FaceOverlay face, @Nullable BehaviorState behavior) {
        double dx = face.gazeX - AppTuning.GAZE_CENTER_X;
        double dy = face.gazeY - AppTuning.GAZE_CENTER_Y;
        if (Math.sqrt(dx * dx + dy * dy) >= AppTuning.GAZE_TRIGGER_RADIUS) {
            return false;
        }
        if (behavior != BehaviorState.FOCUSED) {
            return false;
        }
        if (face.boundingBox == null) {
            return false;
        }
        double cx = face.boundingBox.centerX();
        return Math.abs(cx - 0.5) <= AppTuning.FACE_CENTER_TOLERANCE;
    }
}
