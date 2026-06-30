package com.xbot.xbot.perception;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;

/** Helpers for feeding upright bitmaps into MediaPipe Tasks. */
public final class MediaPipeImageUtils {
    private MediaPipeImageUtils() {}

    @Nullable
    public static MPImage fromBitmap(@Nullable Bitmap uprightBitmap) {
        if (uprightBitmap == null || uprightBitmap.isRecycled()) {
            return null;
        }
        return new BitmapImageBuilder(uprightBitmap).build();
    }
}
