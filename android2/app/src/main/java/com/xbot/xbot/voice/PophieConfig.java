package com.xbot.xbot.voice;

import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Pophie backend configuration (see docs/API对接文档.md §1).
 */
public class PophieConfig {
    public static final String DEFAULT_BASE_URL = "http://223.109.143.135:8000";

    public String baseUrl;
    public String robotId;
    @Nullable public String sessionId;
    public String voiceId;

    public PophieConfig() {
        this(DEFAULT_BASE_URL, newRobotId(), null, "");
    }

    public PophieConfig(String baseUrl, String robotId, @Nullable String sessionId, String voiceId) {
        this.baseUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : DEFAULT_BASE_URL;
        this.robotId = robotId != null && !robotId.isEmpty() ? robotId : newRobotId();
        this.sessionId = sessionId;
        this.voiceId = voiceId != null ? voiceId : "";
    }

    public boolean isValid() {
        return baseUrl != null && !baseUrl.trim().isEmpty();
    }

    public PophieConfig copyWith(
            @Nullable String baseUrl,
            @Nullable String robotId,
            @Nullable String sessionId,
            @Nullable String voiceId) {
        return new PophieConfig(
                baseUrl != null ? baseUrl : this.baseUrl,
                robotId != null ? robotId : this.robotId,
                sessionId != null ? sessionId : this.sessionId,
                voiceId != null ? voiceId : this.voiceId);
    }

    public static String newRobotId() {
        return "robot-" + UUID.randomUUID();
    }
}
