package com.xbot.xbot.voice;

import com.xbot.xbot.model.FaceState;

/** Voice assistant FSM phase (mirrors Flutter {@code VoiceState}). */
public enum VoiceState {
    IDLE,
    WAKING,
    LISTENING,
    THINKING,
    SPEAKING;

    public boolean isActive() {
        return this != IDLE;
    }

    /** Maps to virtual pet {@code window.__face.setState} wire name. */
    public String getFaceStateWireName() {
        switch (this) {
            case IDLE:
                return FaceState.IDLE.toWireName();
            case WAKING:
            case LISTENING:
                return FaceState.LISTENING.toWireName();
            case THINKING:
                return FaceState.THINKING.toWireName();
            case SPEAKING:
                return FaceState.HAPPY.toWireName();
            default:
                return FaceState.IDLE.toWireName();
        }
    }
}
