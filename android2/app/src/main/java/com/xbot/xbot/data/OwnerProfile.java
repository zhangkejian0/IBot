package com.xbot.xbot.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Owner profile collected during first-run onboarding.
 *
 * <p>Face embeddings never leave the device; only {@link #faceRegistered} is sent upstream.
 */
public class OwnerProfile {
    public String nickname;
    public String robotName;
    public Gender gender;
    public String birthday;
    public boolean faceRegistered;
    public String personId;
    public boolean syncedToServer;
    public long createdAt;

    public OwnerProfile() {
        this.nickname = "主人";
        this.robotName = "狗蛋";
        this.faceRegistered = false;
        this.syncedToServer = false;
        this.createdAt = System.currentTimeMillis();
    }

    public OwnerProfile(
            String nickname,
            String robotName,
            Gender gender,
            String birthday,
            boolean faceRegistered,
            String personId,
            boolean syncedToServer,
            long createdAt) {
        this.nickname = nickname;
        this.robotName = robotName;
        this.gender = gender;
        this.birthday = birthday;
        this.faceRegistered = faceRegistered;
        this.personId = personId;
        this.syncedToServer = syncedToServer;
        this.createdAt = createdAt;
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("nickname", nickname);
        map.put("robotName", robotName);
        map.put("gender", gender != null ? gender.apiKey : null);
        map.put("birthday", birthday);
        map.put("faceRegistered", faceRegistered);
        map.put("personId", personId);
        map.put("syncedToServer", syncedToServer);
        map.put("createdAt", createdAt);
        return map;
    }

    /** Pophie upload payload (snake_case, no local-only fields). */
    public Map<String, Object> toPophieMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("nickname", nickname);
        map.put("robot_name", robotName);
        map.put("face_registered", faceRegistered);
        if (gender != null) {
            map.put("gender", gender.apiKey);
        }
        if (birthday != null && !birthday.isEmpty()) {
            map.put("birthday", birthday);
        }
        return map;
    }
}
