package com.xbot.xbot.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.face.GazeZoneDetector;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.model.ExpressionResult;
import com.xbot.xbot.model.FaceOverlay;
import com.xbot.xbot.model.HandOverlay;
import com.xbot.xbot.model.IdentityMatch;
import com.xbot.xbot.model.PoseOverlay;
import com.xbot.xbot.R;

import java.util.ArrayList;
import java.util.List;

/** Canvas overlay for face boxes, landmarks, hands, and pose (no objects). */
public class DetectionOverlayView extends View {
    private static final int[][] HAND_CONNECTIONS = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {0, 9}, {9, 10}, {10, 11}, {11, 12},
            {0, 13}, {13, 14}, {14, 15}, {15, 16},
            {0, 17}, {17, 18}, {18, 19}, {19, 20},
            {5, 9}, {9, 13}, {13, 17}
    };

    private static final int[][] POSE_CONNECTIONS = {
            {11, 12}, {11, 13}, {13, 15}, {12, 14}, {14, 16},
            {11, 23}, {12, 24}, {23, 24}, {23, 25}, {25, 27},
            {24, 26}, {26, 28}, {0, 1}, {1, 2}, {2, 3}, {3, 7},
            {0, 4}, {4, 5}, {5, 6}, {6, 8}, {9, 10}
    };

    private DetectionResult result = new DetectionResult();
    private DisplaySettings settings = new DisplaySettings();
    @Nullable
    private GazeZoneDetector zoneDetector;

    private final Paint faceBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faceDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handJointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint poseBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint poseJointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DetectionOverlayView(android.content.Context context) {
        this(context, null);
    }

    public DetectionOverlayView(android.content.Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        faceBoxPaint.setStyle(Paint.Style.STROKE);
        faceBoxPaint.setStrokeWidth(4f);
        faceBoxPaint.setColor(getResources().getColor(R.color.accent_green, null));

        faceDotPaint.setStyle(Paint.Style.FILL);
        faceDotPaint.setColor(getResources().getColor(R.color.accent_teal, null));

        handBonePaint.setStyle(Paint.Style.STROKE);
        handBonePaint.setStrokeWidth(5f);
        handBonePaint.setStrokeCap(Paint.Cap.ROUND);
        handBonePaint.setColor(getResources().getColor(R.color.accent_orange, null));

        handJointPaint.setStyle(Paint.Style.FILL);
        handJointPaint.setColor(getResources().getColor(R.color.accent_yellow, null));

        poseBonePaint.setStyle(Paint.Style.STROKE);
        poseBonePaint.setStrokeWidth(5f);
        poseBonePaint.setStrokeCap(Paint.Cap.ROUND);
        poseBonePaint.setColor(getResources().getColor(R.color.accent_blue, null));

        poseJointPaint.setStyle(Paint.Style.FILL);
        poseJointPaint.setColor(getResources().getColor(R.color.accent_green, null));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setColor(Color.argb(77, 255, 255, 255));

        zoneHighlightPaint.setStyle(Paint.Style.FILL);
        zoneHighlightPaint.setColor(Color.argb(102, 255, 59, 48));

        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(36f);
        badgeTextPaint.setFakeBoldText(true);
        badgeBgPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(DetectionResult result, DisplaySettings settings, @Nullable GazeZoneDetector zoneDetector) {
        this.result = result != null ? result : new DetectionResult();
        this.settings = settings != null ? settings : new DisplaySettings();
        this.zoneDetector = zoneDetector;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (zoneDetector != null) {
            paintZoneGrid(canvas);
        }
        if (settings.poseEnabled && settings.showPoseSkeleton) {
            for (PoseOverlay pose : result.poses) {
                paintPose(canvas, pose);
            }
        }
        if (settings.faceEnabled) {
            for (FaceOverlay face : result.faces) {
                paintFace(canvas, face);
            }
        }
        if (settings.handEnabled) {
            for (HandOverlay hand : result.hands) {
                paintHand(canvas, hand);
            }
        }
    }

    private void paintZoneGrid(Canvas canvas) {
        int cols = GazeZoneDetector.COLS;
        int rows = GazeZoneDetector.ROWS;
        float cellW = getWidth() / (float) cols;
        float cellH = getHeight() / (float) rows;

        for (int i = 0; i <= cols; i++) {
            float x = i * cellW;
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }
        for (int i = 0; i <= rows; i++) {
            float y = i * cellH;
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }

        Integer col = zoneDetector.getCurrentCol();
        Integer row = zoneDetector.getCurrentRow();
        if (col != null && row != null) {
            RectF rect = new RectF(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH);
            canvas.drawRect(rect, zoneHighlightPaint);
        }
    }

    private void paintFace(Canvas canvas, FaceOverlay face) {
        if (settings.showFaceMesh && !face.landmarks.isEmpty()) {
            for (PointF p : face.landmarks) {
                canvas.drawCircle(mapX(p.x), mapY(p.y), 3f, faceDotPaint);
            }
        }
        if (face.boundingBox == null) {
            return;
        }
        RectF box = mapRect(face.boundingBox);
        if (settings.showFaceBox) {
            canvas.drawRoundRect(box, 16f, 16f, faceBoxPaint);
        }
        List<String> labels = new ArrayList<>();
        if (settings.showExpression && !face.landmarks.isEmpty() && face.expression != null) {
            ExpressionResult expr = face.expression;
            labels.add(expr.expression.getEmoji() + " " + expr.expression.getLabel()
                    + "  " + Math.round(expr.score * 100) + "%");
        }
        if (settings.showIdentity && face.identity != null) {
            IdentityMatch match = face.identity;
            labels.add("👤 " + match.person.name + "  " + Math.round(match.similarity * 100) + "%");
        }
        paintBadges(canvas, labels, box.left, box.top - 8, R.color.accent_blue);
    }

    private void paintHand(Canvas canvas, HandOverlay hand) {
        if (hand.landmarks.size() < 21) {
            return;
        }
        if (settings.showHandSkeleton) {
            for (int[] edge : HAND_CONNECTIONS) {
                PointF a = hand.landmarks.get(edge[0]);
                PointF b = hand.landmarks.get(edge[1]);
                canvas.drawLine(mapX(a.x), mapY(a.y), mapX(b.x), mapY(b.y), handBonePaint);
            }
            for (int i = 0; i < hand.landmarks.size(); i++) {
                PointF pt = hand.landmarks.get(i);
                canvas.drawCircle(mapX(pt.x), mapY(pt.y), 8f, handJointPaint);
            }
        }
        if (hand.boundingBox != null && settings.showGesture && hand.gesture != null) {
            paintBadges(canvas, List.of(hand.gesture + "  " + Math.round(hand.gestureConfidence * 100) + "%"),
                    mapRect(hand.boundingBox).left, mapRect(hand.boundingBox).top - 8, R.color.accent_orange);
        }
    }

    private void paintPose(Canvas canvas, PoseOverlay pose) {
        if (pose.landmarks.size() < 33) {
            return;
        }
        for (int[] edge : POSE_CONNECTIONS) {
            PointF a = pose.landmarks.get(edge[0]);
            PointF b = pose.landmarks.get(edge[1]);
            if (a.x == 0 && a.y == 0 || b.x == 0 && b.y == 0) {
                continue;
            }
            canvas.drawLine(mapX(a.x), mapY(a.y), mapX(b.x), mapY(b.y), poseBonePaint);
        }
        for (PointF pt : pose.landmarks) {
            if (pt.x == 0 && pt.y == 0) {
                continue;
            }
            canvas.drawCircle(mapX(pt.x), mapY(pt.y), 9f, poseJointPaint);
        }
    }

    private void paintBadges(Canvas canvas, List<String> labels, float x, float top, int colorRes) {
        if (labels.isEmpty()) {
            return;
        }
        badgeBgPaint.setColor(getResources().getColor(colorRes, null));
        float y = top;
        for (String label : labels) {
            float textW = badgeTextPaint.measureText(label);
            float pad = 12f;
            float h = 40f;
            y -= h;
            if (y < 0) {
                y = 0;
            }
            canvas.drawRoundRect(new RectF(x, y, x + textW + pad * 2, y + h), 12f, 12f, badgeBgPaint);
            canvas.drawText(label, x + pad, y + h - 12f, badgeTextPaint);
        }
    }

    private float mapX(float normalizedX) {
        float x = result.mirror ? (1f - normalizedX) : normalizedX;
        return x * getWidth();
    }

    private float mapY(float normalizedY) {
        return normalizedY * getHeight();
    }

    private RectF mapRect(RectF rect) {
        float left = mapX(rect.left);
        float right = mapX(rect.right);
        float top = mapY(rect.top);
        float bottom = mapY(rect.bottom);
        return new RectF(
                Math.min(left, right),
                Math.min(top, bottom),
                Math.max(left, right),
                Math.max(top, bottom));
    }
}
