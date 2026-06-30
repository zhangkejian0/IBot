package com.xbot.xbot.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Family relationship to the robot owner (mirrors Flutter {@code FamilyRelation}). */
public enum FamilyRelation {
    OWNER("owner", "主人"),
    SPOUSE("spouse", "配偶"),
    FATHER("father", "父亲"),
    MOTHER("mother", "母亲"),
    SON("son", "儿子"),
    DAUGHTER("daughter", "女儿"),
    BROTHER("brother", "兄弟"),
    SISTER("sister", "姐妹"),
    GRANDFATHER("grandfather", "祖父"),
    GRANDMOTHER("grandmother", "祖母"),
    FRIEND("friend", "朋友"),
    OTHER("other", "其他");

    public final String key;
    public final String label;

    FamilyRelation(String key, String label) {
        this.key = key;
        this.label = label;
    }

    @NonNull
    public static FamilyRelation fromKey(@Nullable String key) {
        if (key == null) {
            return OTHER;
        }
        for (FamilyRelation r : values()) {
            if (r.key.equals(key)) {
                return r;
            }
        }
        return OTHER;
    }

    public static FamilyRelation[] selectableValues() {
        return values();
    }
}
