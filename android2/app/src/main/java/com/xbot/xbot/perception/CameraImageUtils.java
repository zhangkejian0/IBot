package com.xbot.xbot.perception;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * YUV / rotation helpers for CameraX frames.
 *
 * <p>Ported from Flutter {@code lib/services/camera_image_utils.dart}. Android
 * preview is not horizontally mirrored like iOS, so overlay coordinates may need
 * a front-camera flip via {@link #shouldFlipFrontCameraHorizontal()}.
 */
public final class CameraImageUtils {
    private CameraImageUtils() {}

    /** Upright RGBA bytes plus dimensions after YUV conversion and rotation. */
    public static final class UprightRgba {
        public final byte[] bytes;
        public final int width;
        public final int height;

        public UprightRgba(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Rotation (degrees) to apply so detection coordinates match the locked
     * landscape preview. Matches Flutter {@code detectionRotationDegrees}.
     */
    public static int detectionRotationDegrees(
            int width,
            int height,
            int sensorOrientation,
            boolean isFrontCamera,
            int deviceRotationDegrees) {
        int rotation = isFrontCamera
                ? (sensorOrientation + deviceRotationDegrees) % 360
                : (sensorOrientation - deviceRotationDegrees + 360) % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        return rotation;
    }

    public static int detectionRotationDegrees(ImageProxy imageProxy, boolean isFrontCamera) {
        return imageProxy.getImageInfo().getRotationDegrees();
    }

    /** Android camera stream is not mirrored; flip overlay X for front camera. */
    public static boolean shouldFlipFrontCameraHorizontal() {
        return true;
    }

    /**
     * Build {@link ImageProcessingOptions}-compatible rotation for MediaPipe /
     * ML Kit from a CameraX frame.
     */
    public static int rotationDegreesForFrame(ImageProxy imageProxy) {
        return imageProxy.getImageInfo().getRotationDegrees();
    }

    /** Convert {@link ImageProxy} YUV_420_888 to NV21 for ML Kit / legacy paths. */
    public static byte[] imageProxyToNv21(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) {
            return new byte[0];
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return new byte[0];
        }

        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        copyPlane(planes[0].getBuffer(), planes[0].getRowStride(), width, height, nv21, 0, 1);
        copyChromaNv21(planes[1], planes[2], width, height, nv21, ySize);
        return nv21;
    }

    /**
     * Convert a camera frame to an upright {@link Bitmap} (ARGB_8888, rotation applied).
     */
    public static Bitmap toUprightBitmap(ImageProxy imageProxy, int rotationDegrees) {
        Bitmap bitmap = yuvToBitmap(imageProxy);
        if (bitmap == null) {
            return null;
        }
        Bitmap upright = rotateBitmap(bitmap, rotationDegrees);
        if (upright != bitmap) {
            bitmap.recycle();
        }
        return upright;
    }

    /**
     * Convert a camera frame to upright RGBA bytes (rotation applied). Returns
     * null when conversion fails.
     */
    public static UprightRgba toUprightRgba(ImageProxy imageProxy, int rotationDegrees) {
        Bitmap upright = toUprightBitmap(imageProxy, rotationDegrees);
        if (upright == null) {
            return null;
        }
        int w = upright.getWidth();
        int h = upright.getHeight();
        int[] pixels = new int[w * h];
        upright.getPixels(pixels, 0, w, 0, 0, w, h);
        upright.recycle();

        byte[] rgba = new byte[w * h * 4];
        int idx = 0;
        for (int pixel : pixels) {
            rgba[idx++] = (byte) ((pixel >> 16) & 0xFF);
            rgba[idx++] = (byte) ((pixel >> 8) & 0xFF);
            rgba[idx++] = (byte) (pixel & 0xFF);
            rgba[idx++] = (byte) ((pixel >> 24) & 0xFF);
        }
        return new UprightRgba(rgba, w, h);
    }

    public static Bitmap uprightBitmapFromRgba(byte[] rgba, int width, int height) {
        int[] pixels = new int[width * height];
        int src = 0;
        for (int i = 0; i < pixels.length; i++) {
            int r = rgba[src++] & 0xFF;
            int g = rgba[src++] & 0xFF;
            int b = rgba[src++] & 0xFF;
            int a = rgba[src++] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /** Crop a normalized face box from an upright bitmap with outward padding. */
    public static Bitmap cropNormalized(Bitmap upright, RectF box, float paddingRatio) {
        if (upright == null || box == null) {
            return null;
        }
        int w = upright.getWidth();
        int h = upright.getHeight();
        float padW = box.width() * paddingRatio;
        float padH = box.height() * paddingRatio;
        int left = clamp((int) ((box.left - padW) * w), 0, w - 1);
        int top = clamp((int) ((box.top - padH) * h), 0, h - 1);
        int right = clamp((int) ((box.right + padW) * w), 0, w);
        int bottom = clamp((int) ((box.bottom + padH) * h), 0, h);
        int cropW = Math.max(1, right - left);
        int cropH = Math.max(1, bottom - top);
        return Bitmap.createBitmap(upright, left, top, cropW, cropH);
    }

    public static PointF center(RectF rect) {
        return new PointF(rect.centerX(), rect.centerY());
    }

    public static float distance(PointF a, PointF b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float interW = right - left;
        float interH = bottom - top;
        if (interW <= 0 || interH <= 0) {
            return 0;
        }
        float inter = interW * interH;
        float union = a.width() * a.height() + b.width() * b.height() - inter;
        return union <= 0 ? 0 : inter / union;
    }

    public static RectF flipHorizontal(RectF rect) {
        return new RectF(1f - rect.right, rect.top, 1f - rect.left, rect.bottom);
    }

    public static PointF flipHorizontal(PointF point) {
        return new PointF(1f - point.x, point.y);
    }

    private static Bitmap yuvToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        int[] argb = new int[width * height];
        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();

        int yPos = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * yRowStride;
            int uvRow = (y >> 1) * uvRowStride;
            for (int x = 0; x < width; x++) {
                int yIndex = yRow + x;
                int uvIndex = uvRow + (x >> 1) * uvPixelStride;
                int yp = yBuffer.get(yIndex) & 0xFF;
                int u = (uBuffer.get(uvIndex) & 0xFF) - 128;
                int v = (vBuffer.get(uvIndex) & 0xFF) - 128;
                int r = clamp((int) (yp + 1.402f * v), 0, 255);
                int g = clamp((int) (yp - 0.344136f * u - 0.714136f * v), 0, 255);
                int b = clamp((int) (yp + 1.772f * u), 0, 255);
                argb[yPos++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap rotateBitmap(Bitmap source, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static void copyPlane(
            ByteBuffer buffer,
            int rowStride,
            int width,
            int height,
            byte[] out,
            int offset,
            int pixelStride) {
        buffer.rewind();
        int pos = offset;
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x++) {
                out[pos] = buffer.get(rowStart + x * pixelStride);
                pos++;
            }
        }
    }

    private static void copyChromaNv21(
            Image.Plane uPlane,
            Image.Plane vPlane,
            int width,
            int height,
            byte[] out,
            int offset) {
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();
        int pos = offset;
        for (int y = 0; y < height / 2; y++) {
            int rowStart = y * uvRowStride;
            for (int x = 0; x < width / 2; x++) {
                int index = rowStart + x * uvPixelStride;
                out[pos++] = vBuffer.get(index);
                out[pos++] = uBuffer.get(index);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
