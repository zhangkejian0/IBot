package com.xbot.xbot.model;

/** Temporal behavior state aggregated from per-frame observations. */
public enum BehaviorState {
    ABSENT,
    PRESENT,
    FOCUSED,
    DISTRACTED,
    DROWSY;

    public String getLabel() {
        switch (this) {
            case ABSENT: return "离开";
            case PRESENT: return "在场";
            case FOCUSED: return "专注";
            case DISTRACTED: return "走神";
            case DROWSY: return "困倦";
            default: return name();
        }
    }

    public String getApiKey() {
        switch (this) {
            case ABSENT: return "absent";
            case PRESENT: return "present";
            case FOCUSED: return "focused";
            case DISTRACTED: return "distracted";
            case DROWSY: return "drowsy";
            default: return name().toLowerCase();
        }
    }
}
