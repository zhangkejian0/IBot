package com.xbot.xbot.util;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/** Small helpers for Android {@code AssetManager} access. */
public final class AssetUtils {
    private AssetUtils() {}

    public static boolean hasAsset(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
