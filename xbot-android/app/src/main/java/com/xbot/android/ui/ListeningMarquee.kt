package com.xbot.android.ui

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 聆听态「暖色调跑马灯」边框（苹果 Siri 风格）。对标 Flutter camera_screen.dart
 * 的 _ListeningMarquee / _MarqueePainter。
 *
 * 进入聆听（[MainScreenController.voiceListening]=true，即 WAKING/LISTENING）时，
 * 沿全屏一圈绘制一条会旋转的暖色 SweepGradient 描边 + 高斯模糊光晕，并随麦克风
 * 音量（[MainScreenController.voiceLevel]）做轻微的亮度/粗细呼吸。其余态柔和淡出。
 *
 * 注意：本覆盖层不设置任何 pointerInput，故不拦截手势（双击仍由下方 WebView 捕获）。
 */
@Composable
fun ListeningMarquee(controller: MainScreenController) {
    val listening = controller.voiceListening

    // 出现/消失整体不透明度：柔和淡入淡出而非生硬切换。
    val opacity by animateFloatAsState(
        targetValue = if (listening) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "marqueeFade",
    )

    // 持续旋转相位 0..1（恒定动画，隐藏时不绘制，开销可忽略）。
    val transition = rememberInfiniteTransition(label = "marqueeRotate")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marqueeRotation",
    )

    // 麦克风实时音量（聆听相位），驱动「呼吸」。仅在聆听时轮询，避免无谓刷新。
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listening) {
        if (!listening) {
            level = 0f
            return@LaunchedEffect
        }
        while (true) {
            level = controller.voiceLevel.coerceIn(0f, 1f)
            delay(50)
        }
    }

    if (opacity <= 0.001f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val cx = w / 2f
        val cy = h / 2f
        // 音量越大，光晕越亮、越粗，形成 Siri 式呼吸感。
        val pulse = 0.78f + 0.22f * level
        val radius = 38.dp.toPx()

        // 暖色调跑马灯配色：蜜橘→珊瑚→玫瑰→暖橘→浅琥珀→蜜橘，首尾相接保证旋转无缝。
        val shader = SweepGradient(cx, cy, WARM_COLORS, null).apply {
            setLocalMatrix(Matrix().apply { postRotate(rotation * 360f, cx, cy) })
        }

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // 多层描边：外层宽而模糊（光晕）→ 内层细而清晰（亮边），叠出发光质感。
            fun stroke(widthDp: Float, blurDp: Float, alpha: Float) {
                val width = widthDp.dp.toPx()
                val blur = blurDp.dp.toPx()
                val inset = width / 2f
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = width
                // shader 提供颜色，paint.alpha 作为整体透明度调制（含淡入淡出）。
                paint.shader = shader
                paint.alpha = (alpha * opacity * 255f).coerceIn(0f, 255f).toInt()
                if (blur > 0f) {
                    paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                val rect = RectF(inset, inset, w - inset, h - inset)
                nativeCanvas.drawRoundRect(rect, radius, radius, paint)
            }

            stroke(34f * pulse, 26f * pulse, 0.55f) // 外层弥散光晕
            stroke(16f * pulse, 10f, 0.85f)         // 中层
            stroke(5f, 1.5f, 1.0f)                  // 内层亮边
        }
    }
}

/** 暖色跑马灯配色（ARGB）：蜜橘→珊瑚→玫瑰→暖橘→浅琥珀→蜜橘。 */
private val WARM_COLORS = intArrayOf(
    0xFFFFC078.toInt(),
    0xFFFF8A5B.toInt(),
    0xFFFF6F91.toInt(),
    0xFFFFB26B.toInt(),
    0xFFFFD79A.toInt(),
    0xFFFFC078.toInt(),
)
