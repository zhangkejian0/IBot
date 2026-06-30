package com.xbot.xbot.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xbot.xbot.core.DisplaySettings;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Persists {@link DisplaySettings} (SharedPreferences) and {@link OwnerProfile}
 * (JSON file, same semantics as Flutter {@code OwnerProfileStore}).
 */
public class SettingsStore {
    private static final String PREFS_NAME = "xbot_settings";
    private static final String OWNER_FILE = "owner_profile.json";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Context appContext;
    private final SharedPreferences prefs;
    private OwnerProfile ownerProfile;
    private boolean ownerLoaded;

    public SettingsStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public DisplaySettings loadDisplaySettings() {
        DisplaySettings s = new DisplaySettings();
        s.faceEnabled = prefs.getBoolean("faceEnabled", true);
        s.handEnabled = prefs.getBoolean("handEnabled", true);
        s.identityEnabled = prefs.getBoolean("identityEnabled", true);
        s.poseEnabled = prefs.getBoolean("poseEnabled", true);
        s.debugMode = prefs.getBoolean("debugMode", false);
        s.showFaceMesh = prefs.getBoolean("showFaceMesh", true);
        s.showFaceBox = prefs.getBoolean("showFaceBox", true);
        s.showHandSkeleton = prefs.getBoolean("showHandSkeleton", true);
        s.showPoseSkeleton = prefs.getBoolean("showPoseSkeleton", true);
        s.showLandmarkIndices = prefs.getBoolean("showLandmarkIndices", false);
        s.showExpression = prefs.getBoolean("showExpression", true);
        s.showGesture = prefs.getBoolean("showGesture", true);
        s.showIdentity = prefs.getBoolean("showIdentity", true);
        s.mirrorFrontCamera = prefs.getBoolean("mirrorFrontCamera", true);
        s.useFrontCamera = prefs.getBoolean("useFrontCamera", true);
        s.baseControlEnabled = prefs.getBoolean("baseControlEnabled", false);
        s.baseControlMode = prefs.getString("baseControlMode", "manual");
        s.voiceEnabled = prefs.getBoolean("voiceEnabled", false);
        s.wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", true);
        s.ttsEnabled = prefs.getBoolean("ttsEnabled", true);
        s.streamingSttEnabled = prefs.getBoolean("streamingSttEnabled", true);
        s.gazeTriggerEnabled = prefs.getBoolean("gazeTriggerEnabled", true);
        s.wakeWord = prefs.getString("wakeWord", "你好");
        s.asrApiKey = prefs.getString("asrApiKey", null);
        s.llmApiKey = prefs.getString("llmApiKey", null);
        s.personaLogEnabled = prefs.getBoolean("personaLogEnabled", true);
        s.personaLogServerEnabled = prefs.getBoolean("personaLogServerEnabled", true);
        s.networkLogEnabled = prefs.getBoolean("networkLogEnabled", true);
        return s;
    }

    public void saveDisplaySettings(DisplaySettings s) {
        prefs.edit()
                .putBoolean("faceEnabled", s.faceEnabled)
                .putBoolean("handEnabled", s.handEnabled)
                .putBoolean("identityEnabled", s.identityEnabled)
                .putBoolean("poseEnabled", s.poseEnabled)
                .putBoolean("debugMode", s.debugMode)
                .putBoolean("showFaceMesh", s.showFaceMesh)
                .putBoolean("showFaceBox", s.showFaceBox)
                .putBoolean("showHandSkeleton", s.showHandSkeleton)
                .putBoolean("showPoseSkeleton", s.showPoseSkeleton)
                .putBoolean("showLandmarkIndices", s.showLandmarkIndices)
                .putBoolean("showExpression", s.showExpression)
                .putBoolean("showGesture", s.showGesture)
                .putBoolean("showIdentity", s.showIdentity)
                .putBoolean("mirrorFrontCamera", s.mirrorFrontCamera)
                .putBoolean("useFrontCamera", s.useFrontCamera)
                .putBoolean("baseControlEnabled", s.baseControlEnabled)
                .putString("baseControlMode", s.baseControlMode)
                .putBoolean("voiceEnabled", s.voiceEnabled)
                .putBoolean("wakeWordEnabled", s.wakeWordEnabled)
                .putBoolean("ttsEnabled", s.ttsEnabled)
                .putBoolean("streamingSttEnabled", s.streamingSttEnabled)
                .putBoolean("gazeTriggerEnabled", s.gazeTriggerEnabled)
                .putString("wakeWord", s.wakeWord)
                .putString("asrApiKey", s.asrApiKey)
                .putString("llmApiKey", s.llmApiKey)
                .putBoolean("personaLogEnabled", s.personaLogEnabled)
                .putBoolean("personaLogServerEnabled", s.personaLogServerEnabled)
                .putBoolean("networkLogEnabled", s.networkLogEnabled)
                .apply();
    }

    public OwnerProfile getOwnerProfile() {
        return ownerProfile;
    }

    public boolean isOwnerLoaded() {
        return ownerLoaded;
    }

    public boolean isOwnerRegistered() {
        return ownerProfile != null;
    }

    public void loadOwnerProfile() {
        if (ownerLoaded) {
            return;
        }
        File file = ownerFile();
        if (!file.exists()) {
            ownerProfile = null;
            ownerLoaded = true;
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            Map<String, Object> json = GSON.fromJson(reader, MAP_TYPE);
            ownerProfile = parseOwnerProfile(json);
        } catch (Exception e) {
            ownerProfile = null;
        }
        ownerLoaded = true;
    }

    public void saveOwnerProfile(OwnerProfile profile) throws IOException {
        ownerProfile = profile;
        ownerLoaded = true;
        try (FileWriter writer = new FileWriter(ownerFile())) {
            GSON.toJson(profile.toJsonMap(), writer);
        }
    }

    public void clearOwnerProfile() {
        ownerProfile = null;
        File file = ownerFile();
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private File ownerFile() {
        return new File(appContext.getFilesDir(), OWNER_FILE);
    }

    @SuppressWarnings("unchecked")
    private static OwnerProfile parseOwnerProfile(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        OwnerProfile p = new OwnerProfile();
        Object nickname = json.get("nickname");
        p.nickname = nickname instanceof String ? (String) nickname : "主人";
        Object robotName = json.get("robotName");
        p.robotName = robotName instanceof String ? (String) robotName : "狗蛋";
        Object gender = json.get("gender");
        p.gender = gender instanceof String ? Gender.fromApiKey((String) gender) : null;
        Object birthday = json.get("birthday");
        p.birthday = birthday instanceof String ? (String) birthday : null;
        Object faceRegistered = json.get("faceRegistered");
        p.faceRegistered = faceRegistered instanceof Boolean && (Boolean) faceRegistered;
        Object personId = json.get("personId");
        p.personId = personId instanceof String ? (String) personId : null;
        Object synced = json.get("syncedToServer");
        p.syncedToServer = synced instanceof Boolean && (Boolean) synced;
        Object createdAt = json.get("createdAt");
        if (createdAt instanceof Number) {
            p.createdAt = ((Number) createdAt).longValue();
        }
        return p;
    }
}
