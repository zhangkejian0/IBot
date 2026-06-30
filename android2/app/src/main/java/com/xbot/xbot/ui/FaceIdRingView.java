package com.xbot.xbot.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Face-ID style radial tick ring for face enrollment (mirrors Flutter {@code FaceScanRing}).
 */
public class FaceIdRingView extends View {
    private static final int TICK_COUNT = 72;

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @ColorInt private int activeColor = Color.parseColor("#0A84FF");
    @ColorInt private int baseColor = Color.parseColor("#38383A");
    private float progress;
    @Nullable private Float sweep;
    @Nullable private ValueAnimator scanAnimator;

    public FaceIdRingView(Context context) {
        super(context);
        init();
    }

    public FaceIdRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FaceIdRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    public void setRingColors(@ColorInt int active, @ColorInt int base) {
        activeColor = active;
        baseColor = base;
        invalidate();
    }

    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public void setSweep(@Nullable Float value) {
        sweep = value;
        invalidate();
    }

    public void startScanAnimation() {
        stopScanAnimation();
        scanAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanAnimator.setDuration(2000L);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        scanAnimator.start();
    }

    public void stopScanAnimation() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
            scanAnimator = null;
        }
        sweep = null;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopScanAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outerRadius = Math.min(cx, cy) - 2f;
        float baseLen = 7f;
        float activeLen = 14f;

        for (int i = 0; i < TICK_COUNT; i++) {
            float t = (float) i / TICK_COUNT;
            double angle = -Math.PI / 2 + 2 * Math.PI * t;
            float len = sweep != null ? activeLen : baseLen;
            float inner = outerRadius - len;
            float cosA = (float) Math.cos(angle);
            float sinA = (float) Math.sin(angle);
            float x1 = cx + cosA * inner;
            float y1 = cy + sinA * inner;
            float x2 = cx + cosA * outerRadius;
            float y2 = cy + sinA * outerRadius;

            int color = baseColor;
            float width = 2f;

            if (t < progress) {
                color = blend(baseColor, activeColor, 0.55f);
                width = 2.5f;
            }

            if (sweep != null) {
                float dist = Math.abs(sweep - t);
                float glow = Math.max(0f, Math.min(1f, 1f - dist * 6f));
                if (glow > 0f) {
                    color = blend(color, activeColor, glow);
                    width = 3f + glow * 1.5f;
                }
            }

            tickPaint.setColor(color);
            tickPaint.setStrokeWidth(width);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }
    }

    private static int blend(int from, int to, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * ratio);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.argb(a, r, g, b);
    }
}
