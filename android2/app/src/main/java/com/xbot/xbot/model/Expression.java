package com.xbot.xbot.model;

/** Seven common expressions (Ekman six + neutral). */
public enum Expression {
    NEUTRAL,
    HAPPY,
    SAD,
    SURPRISED,
    ANGRY,
    DISGUSTED,
    FEARFUL;

    public String getLabel() {
        switch (this) {
            case NEUTRAL: return "中性";
            case HAPPY: return "高兴";
            case SAD: return "伤心";
            case SURPRISED: return "惊讶";
            case ANGRY: return "愤怒";
            case DISGUSTED: return "厌恶";
            case FEARFUL: return "恐惧";
            default: return name();
        }
    }

    public String getApiKey() {
        return name().toLowerCase();
    }

    public String getEmoji() {
        switch (this) {
            case NEUTRAL: return "😐";
            case HAPPY: return "😄";
            case SAD: return "😢";
            case SURPRISED: return "😲";
            case ANGRY: return "😠";
            case DISGUSTED: return "🤢";
            case FEARFUL: return "😨";
            default: return "";
        }
    }
}
