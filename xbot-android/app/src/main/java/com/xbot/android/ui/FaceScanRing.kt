package com.xbot.android.ui

import androidx.camera.core.ImageProxy
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 人脸录入结果态（驱动扫描环遮罩与配色）。对应 Flutter FaceScanState。 */
enum class FaceScanState { IDLE, COLLECTING, SUCCESS, FAILED, DUPLICATE }

private val RingAccent = Color(0xFF0A84FF) // iOS systemBlue
private val RingBase = Color(0xFF38383A)
private val RingGreen = Color(0xFF30D158)
private val RingRed = Color(0xFFFF453A)
private val PreviewCard = Color(0xFF1C1C1E)

/**
 * Face-ID 风格的人脸扫描环（圆形摄像头取景 + 放射状刻度环）。对应 Flutter FaceScanRing。
 *
 * - 圆形相机预览（TextureView 兼容模式裁剪为圆）。
 * - 72 根放射刻度：采集进度染色 + 录入时流动高光环绕。
 * - 结果态：成功绿色对勾遮罩 / 失败·重复红色叹号遮罩。
 *
 * @param progress 采集进度 0..1
 * @param scanning 是否正在录入（驱动高光环绕动画 + 加长刻度）
 * @param cameraActive 相机是否就绪（false 显示加载圈）
 */
@Composable
fun FaceScanRing(
    state: FaceScanState,
    progress: Float,
    scanning: Boolean,
    cameraActive: Boolean,
    analysisExecutor: Executor,
    analyzer: (ImageProxy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringColor = when (state) {
        FaceScanState.SUCCESS -> RingGreen
        FaceScanState.FAILED, FaceScanState.DUPLICATE -> RingRed
        else -> RingAccent
    }

    val transition = rememberInfiniteTransition(label = "faceScanSweep")
    val anim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val sweep: Float? = if (scanning) anim else null

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).coerceIn(180f, 360f).dp
        val ringPadding = 22.dp
        val previewSide = side - ringPadding * 2

        Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
            // 放射刻度环。
            Canvas(modifier = Modifier.size(side)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f - 2.dp.toPx()
                val baseLen = 7.dp.toPx()
                val activeLen = 14.dp.toPx()
                val tickCount = 72
                for (i in 0 until tickCount) {
                    val t = i / tickCount.toFloat()
                    val angle = -PI / 2 + 2 * PI * t
                    val len = if (sweep != null) activeLen else baseLen
                    val inner = outer - len
                    val ca = cos(angle).toFloat()
                    val sa = sin(angle).toFloat()
                    val p1 = Offset(center.x + ca * inner, center.y + sa * inner)
                    val p2 = Offset(center.x + ca * outer, center.y + sa * outer)
                    var color = RingBase
                    var width = 2.dp.toPx()
                    if (t < progress) {
                        color = lerp(RingBase, ringColor, 0.55f)
                        width = 2.5.dp.toPx()
                    }
                    if (sweep != null) {
                        val dist = kotlin.math.abs(sweep - t)
                        val glow = (1f - dist * 6f).coerceIn(0f, 1f)
                        if (glow > 0f) {
                            color = lerp(color, ringColor, glow)
                            width = (3f + glow * 1.5f).dp.toPx()
                        }
                    }
                    drawLine(color = color, start = p1, end = p2, strokeWidth = width, cap = StrokeCap.Round)
                }
            }

            // 圆形相机预览。
            Box(
                modifier = Modifier
                    .size(previewSide)
                    .clip(CircleShape)
                    .background(PreviewCard),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraActive) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        useFront = true,
                        active = true,
                        compatibleMode = true,
                        analysisExecutor = analysisExecutor,
                        analyzer = analyzer,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }

                // 结果遮罩。
                when (state) {
                    FaceScanState.SUCCESS -> ResultOverlay(RingGreen, Icons.Filled.Check)
                    FaceScanState.FAILED, FaceScanState.DUPLICATE ->
                        ResultOverlay(RingRed, Icons.Filled.PriorityHigh)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ResultOverlay(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(72.dp),
        )
    }
}
