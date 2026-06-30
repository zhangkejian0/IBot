package com.xbot.android.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xbot.android.core.AppViewModel
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.HandOverlay

@Composable
fun CameraScreen(viewModel: AppViewModel) {
    val detectionResult by viewModel.detectionResult.collectAsState()
    val inferMs by viewModel.inferMs.collectAsState()
    val behavior by viewModel.behavior.collectAsState()
    val debugMode by viewModel.debugMode.collectAsState()

    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var bridge by remember { mutableStateOf<FaceWebViewBridge?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (debugMode) {
            // 调试模式:摄像头预览(底层) + 检测覆盖层(叠加)
            val previewView = viewModel.cameraManager?.previewView
            if (previewView != null) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            }
            DetectionOverlayCanvas(
                detectionResult = detectionResult,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val wv = createFaceWebView(ctx) { pageLoaded = true }
                    webView = wv
                    bridge = FaceWebViewBridge(wv)
                    wv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { /* TODO:触发语音助手 */ }
                    )
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = { viewModel.toggleDebugMode() },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "切换调试模式",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            DebugOverlay(
                detectionResult = detectionResult,
                inferMs = inferMs,
                behavior = behavior,
                debugMode = debugMode,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    LaunchedEffect(detectionResult, pageLoaded, debugMode) {
        if (debugMode || !pageLoaded || bridge == null) return@LaunchedEffect
        val face = detectionResult.face
        if (face != null) {
            bridge?.pushGazeTarget(
                faceCenterX = face.boundingBox.centerX(),
                faceCenterY = face.boundingBox.centerY(),
                isFrontCamera = true
            )
        } else {
            bridge?.resetGaze()
        }
    }

    LaunchedEffect(behavior.state, pageLoaded, debugMode) {
        if (debugMode || !pageLoaded || bridge == null) return@LaunchedEffect
        bridge?.pushState(viewModel.getFaceState())
    }
}

@Composable
fun DetectionOverlayCanvas(
    detectionResult: DetectionResult,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        for (face in detectionResult.faces) {
            drawFace(face, w, h)
        }
        for (hand in detectionResult.hands) {
            drawHand(hand, w, h)
        }
        for (obj in detectionResult.objects) {
            val box = obj.boundingBox
            drawRoundRect(
                color = if (obj.heldByHand) Color(0xFFFF9800) else Color(0xFF00BCD4),
                topLeft = Offset(box.left * w, box.top * h),
                size = Size(box.width() * w, box.height() * h),
                cornerRadius = CornerRadius(8f),
                style = Stroke(width = 2f)
            )
        }
    }
}

private fun DrawScope.drawFace(face: FaceOverlay, w: Float, h: Float) {
    val box = face.boundingBox

    drawRoundRect(
        color = Color(0xFF4CAF50),
        topLeft = Offset(box.left * w, box.top * h),
        size = Size(box.width() * w, box.height() * h),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = 2f)
    )

    for (i in face.landmarks.indices step 10) {
        val lm = face.landmarks[i]
        drawCircle(
            color = Color(0xFF00BCD4),
            radius = 2f,
            center = Offset(lm.x * w, lm.y * h)
        )
    }

    if (face.identity != null) {
        drawContext.canvas.nativeCanvas.drawText(
            "${face.identity!!.person.name} (${(face.identity!!.similarity * 100).toInt()}%)",
            box.left * w,
            box.top * h - 30f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                isAntiAlias = true
            }
        )
    }

    val expr = face.expression.expression
    drawContext.canvas.nativeCanvas.drawText(
        "${expr.emoji} ${expr.label} ${(face.expression.score * 100).toInt()}%",
        box.left * w,
        box.bottom * h + 40f,
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
        }
    )
}

private fun DrawScope.drawHand(hand: HandOverlay, w: Float, h: Float) {
    val box = hand.boundingBox

    drawRoundRect(
        color = Color(0xFFFF9800),
        topLeft = Offset(box.left * w, box.top * h),
        size = Size(box.width() * w, box.height() * h),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = 2f)
    )

    val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )
    for ((a, b) in connections) {
        if (a < hand.landmarks.size && b < hand.landmarks.size) {
            val la = hand.landmarks[a]
            val lb = hand.landmarks[b]
            drawLine(
                color = Color(0xFFFFEB3B),
                start = Offset(la.x * w, la.y * h),
                end = Offset(lb.x * w, lb.y * h),
                strokeWidth = 2f
            )
        }
    }

    for (lm in hand.landmarks) {
        drawCircle(
            color = Color(0xFFFFEB3B),
            radius = 3f,
            center = Offset(lm.x * w, lm.y * h)
        )
    }

    if (hand.gesture != null) {
        drawContext.canvas.nativeCanvas.drawText(
            "${hand.gesture!!.emoji} ${hand.gesture!!.label}",
            box.left * w,
            box.top * h - 20f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = 28f
                isAntiAlias = true
            }
        )
    }
}

@Composable
fun DebugOverlay(
    detectionResult: DetectionResult,
    inferMs: Long,
    behavior: com.xbot.android.behavior.BehaviorSnapshot,
    debugMode: Boolean,
    modifier: Modifier = Modifier
) {
    val face = detectionResult.face
    val faceCount = detectionResult.faces.size
    val handCount = detectionResult.hands.size

    val faceText = if (faceCount > 0) {
        val expr = face?.expression?.expression
        "人脸: $faceCount ${expr?.emoji ?: ""}${expr?.label ?: ""}"
    } else {
        "未检测到人脸"
    }

    val identityText = if (face?.identity != null) {
        "身份: ${face.identity!!.person.name}"
    } else {
        "身份: 未识别"
    }

    val behaviorText = "状态: ${behavior.state.label}"
    val modeText = if (debugMode) "调试模式" else "正常模式"

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        Column {
            Text(text = modeText, color = Color(0xFFFFEB3B), fontSize = 11.sp)
            Text(text = "推理: $inferMs ms", color = Color.White, fontSize = 12.sp)
            Text(text = faceText, color = Color.White, fontSize = 12.sp)
            Text(text = identityText, color = Color.White, fontSize = 12.sp)
            Text(text = "手部: $handCount", color = Color.White, fontSize = 12.sp)
            Text(text = behaviorText, color = Color.White, fontSize = 12.sp)
        }
    }
}
