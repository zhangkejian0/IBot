package com.xbot.xbot.data;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xbot.xbot.voice.PophieConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Pophie backend config (mirrors Flutter {@code PophieConfigStore}). */
public class PophieConfigStore {
    public static final String DEFAULT_BASE_URL = "http://223.109.143.135:8000";

    private static final String FILE_NAME = "pophie_config.json";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Context appContext;
    private String baseUrl = DEFAULT_BASE_URL;
    private String robotId;
    private String sessionId;
    private boolean loaded;

    public PophieConfigStore(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getRobotId() {
        return robotId;
    }

    public PophieConfig getConfig() {
        ensureDefaults();
        return new PophieConfig(baseUrl, robotId, sessionId, "");
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        save();
    }

    public void load() {
        if (loaded) {
            return;
        }
        File file = configFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Map<String, Object> json = GSON.fromJson(reader, MAP_TYPE);
                if (json != null) {
                    Object url = json.get("baseUrl");
                    if (url instanceof String) {
                        baseUrl = (String) url;
                    }
                    Object id = json.get("robotId");
                    if (id instanceof String) {
                        robotId = (String) id;
                    }
                    Object sid = json.get("sessionId");
                    if (sid instanceof String) {
                        sessionId = (String) sid;
                    }
                }
            } catch (Exception ignored) {
                ensureDefaults();
            }
        } else {
            ensureDefaults();
            save();
        }
        loaded = true;
    }

    public void setBaseUrl(String url) {
        baseUrl = url != null && !url.trim().isEmpty() ? url.trim() : DEFAULT_BASE_URL;
        save();
    }

    private void ensureDefaults() {
        if (robotId == null || robotId.isEmpty()) {
            robotId = "robot-" + UUID.randomUUID();
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }

    private void save() {
        ensureDefaults();
        try (FileWriter writer = new FileWriter(configFile())) {
            Map<String, Object> json = new HashMap<>();
            json.put("baseUrl", baseUrl);
            json.put("robotId", robotId);
            if (sessionId != null) {
                json.put("sessionId", sessionId);
            }
            GSON.toJson(json, writer);
        } catch (Exception ignored) {
        }
    }

    private File configFile() {
        return new File(appContext.getFilesDir(), FILE_NAME);
    }
}
