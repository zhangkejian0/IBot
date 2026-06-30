package com.xbot.xbot.model;

/**
 * Virtual pet FSM state (subset aligned with {@code assets/html/src/face/types.ts}).
 */
public enum FaceState {
    IDLE,
    GAZING,
    LISTENING,
    THINKING,
    HAPPY,
    CONFUSED,
    ANGRY,
    SLEEPY,
    SLEEPING,
    WAKING;

    /** Wire-format string for {@code window.__face.setState}. */
    public String toWireName() {
        switch (this) {
            case IDLE: return "idle";
            case GAZING: return "gazing";
            case LISTENING: return "listening";
            case THINKING: return "thinking";
            case HAPPY: return "happy";
            case CONFUSED: return "confused";
            case ANGRY: return "angry";
            case SLEEPY: return "sleepy";
            case SLEEPING: return "sleeping";
            case WAKING: return "waking";
            default: return name().toLowerCase();
        }
    }
}
