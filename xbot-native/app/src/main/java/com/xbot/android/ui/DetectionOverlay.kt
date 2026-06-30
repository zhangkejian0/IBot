package com.xbot.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.HandOverlay
import com.xbot.android.model.ObjectOverlay

/**
 * 调试覆盖层：在 Canvas 上绘制人脸框/网格、手势骨架、物体框（归一化 0..1 → 画布）。
 *
 * 对应 Flutter overlay_painter.dart（精简版，仅核心可视化）。
 * 坐标归一化到 0..1，前置镜像由上层处理（这里按原始归一化坐标绘制）。
 */
@Composable
fun DetectionOverlay(controller: MainScreenController) {
    val result = controller.result
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 人脸框 + 关键点。
        for (face in result.faces) {
            drawFace(face, w, h)
        }
        // 手势骨架（21 点）。
        for (hand in result.hands) {
            drawHand(hand, w, h)
        }
        // 物体框 + 标签。
        for (obj in result.objects) {
            drawObject(obj, w, h)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFace(face: FaceOverlay, w: Float, h: Float) {
    val b = face.boundingBox
    // 人脸框（主脸含表情用绿色，其余灰）。
    val hasMesh = face.landmarks.isNotEmpty()
    val color = if (hasMesh) Color(0xFF66DD88) else Color(0xFF888888)
    drawRect(
        color = color,
        topLeft = Offset(b.left * w, b.top * h),
        size = Size(b.width() * w, b.height() * h),
        style = Stroke(width = 3f),
    )
    // 关键点（仅主脸有）。
    if (hasMesh) {
        for (p in face.landmarks) {
            drawCircle(
                color = Color(0xFFAAD4FF),
                radius = 1.5f,
                center = Offset(p.x * w, p.y * h),
            )
        }
    }
}

/** MediaPipe Hand 21 点骨架连线（标准连接拓扑）。 */
private val HAND_CONNECTIONS = listOf(
    // 拇指
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    // 食指
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    // 中指
    5 to 9, 9 to 10, 10 to 11, 11 to 12,
    // 无名指
    9 to 13, 13 to 14, 14 to 15, 15 to 16,
    // 小指
    13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHand(hand: HandOverlay, w: Float, h: Float) {
    val lms = hand.landmarks
    if (lms.isEmpty()) return
    val color = Color(0xFFFFAA55)
    // 骨架连线。
    for ((a, b) in HAND_CONNECTIONS) {
        if (a >= lms.size || b >= lms.size) continue
        val pa = lms[a]; val pb = lms[b]
        drawLine(
            color = color,
            start = Offset(pa.x * w, pa.y * h),
            end = Offset(pb.x * w, pb.y * h),
            strokeWidth = 3f,
        )
    }
    // 关键点。
    for (p in lms) {
        drawCircle(color = Color(0xFFFFD08A), radius = 3f, center = Offset(p.x * w, p.y * h))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObject(obj: ObjectOverlay, w: Float, h: Float) {
    val b = obj.boundingBox
    val color = if (obj.heldByHand) Color(0xFFFF8A5B) else Color(0xFF55CCBB)
    drawRect(
        color = color,
        topLeft = Offset(b.left * w, b.top * h),
        size = Size(b.width() * w, b.height() * h),
        style = Stroke(width = 3f),
    )
}
