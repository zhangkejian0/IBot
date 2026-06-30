package com.xbot.xbot.data;

/** Owner gender collected during onboarding. */
public enum Gender {
    MALE("male", "男"),
    FEMALE("female", "女"),
    OTHER("other", "其他");

    public final String apiKey;
    public final String label;

    Gender(String apiKey, String label) {
        this.apiKey = apiKey;
        this.label = label;
    }

    public static Gender fromApiKey(String name) {
        if (name == null) {
            return null;
        }
        for (Gender g : values()) {
            if (g.apiKey.equals(name)) {
                return g;
            }
        }
        return null;
    }
}
