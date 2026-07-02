package com.xbot.android.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xbot.android.core.AppViewModel
import com.xbot.android.webview.FaceWebView
import kotlinx.coroutines.delay

/**
 * 主界面（阶段 1-3）。
 *
 * 两种互斥模式（由 controller.debugMode 控制，对应 Flutter 调试开关）：
 * - 虚拟宠物模式（默认）：全屏虚拟形象 WebView，注视跟随 + 注意力态驱动表情。
 * - 调试模式：摄像头识别覆盖层（人脸框/网格/手势骨架/物体/状态面板）。
 *
 * 双击 → 触发语音助手（由 FaceWebView 内部 GestureDetector 直接捕获）。
 */
@Composable
fun MainScreen(
    controller: MainScreenController,
    appViewModel: AppViewModel,
    onResetOwner: () -> Unit,
    onRedownload: () -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            controller = controller,
            appViewModel = appViewModel,
            onClose = { showSettings = false },
            onResetOwner = {
                showSettings = false
                onResetOwner()
            },
            onRedownload = {
                showSettings = false
                onRedownload()
            },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        if (!controller.debugMode) {
            // —— 虚拟形象 WebView ——
            AndroidView(
                factory = { ctx ->
                    FaceWebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        controller.attachWebView(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // —— 调试模式：识别覆盖层 ——
            DetectionOverlay(controller)
        }

        // —— 聆听态暖色调跑马灯（苹果 Siri 风格，对标 Flutter _ListeningMarquee）——
        // 进入聆听（waking/listening）时沿屏幕一圈呈现旋转的暖色光晕，并随麦克风音量
        // 轻微呼吸。覆盖层无 pointerInput，不拦截双击（仍由 FaceWebView 捕获）。
        ListeningMarquee(controller)

        // —— 端侧实时字幕（聆听相位显示流式识别文字，离开聆听自动隐藏）——
        SubtitleOverlay(controller)

        // —— 右上角调试浮层（两种模式都显示，对标 native-prototype）——
        DebugOverlay(controller)

        // —— 左下角设置入口（半透明齿轮按钮）——
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "设置",
                tint = Color.White.copy(alpha = 0.85f),
            )
        }

        // 注：双击检测由 FaceWebView 内部 GestureDetector 直接捕获（绕过 Compose 平台视图
        // 吞触摸事件的问题），见 MainScreenController.attachWebView 设置的 webView.onDoubleTap。

        // —— 定时刷新帧率统计 ——
        LaunchedEffect(Unit) {
            while (true) {
                controller.tickStats()
                delay(500)
            }
        }
    }
}

/** 右上角调试浮层 + 左上角状态面板。 */
@Composable
private fun DebugOverlay(controller: MainScreenController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // 左上角：状态面板（识别结果概览 + 行为/活动态）。
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(8.dp)
                .align(Alignment.TopStart),
        ) {
            val r = controller.result
            Text(
                text = "人脸：${r.faces.size} 张" +
                    (r.face?.expression?.expression?.label?.let { "  $it" } ?: "") +
                    (r.face?.identity?.person?.name?.let { "（$it）" } ?: ""),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "手势：${r.hands.size} 只" +
                    (r.hands.firstOrNull()?.gesture?.label?.let { "  $it" } ?: ""),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "姿态：${r.poses.size} 人" +
                    (r.objects.firstOrNull()?.label?.let { "  持：${r.heldObject?.label}" } ?: ""),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "注意力：${controller.behaviorState.label}  活动：${controller.activity.label}",
                color = Color(0xFF80C0FF),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 右上角：推理耗时 / 帧率 / 麦克风音量。
        Text(
            text = "原生后台线程\n推理: ${"%.1f".format(controller.fps)} fps\n耗时: ${controller.inferMs} ms/帧\n音量: ${"%.0f".format(controller.voiceDb)} dB",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(8.dp),
        )
    }
}

/**
 * 端侧实时字幕（底部居中纯白文字，无背景）。
 *
 * 文字来源：端侧流式 ASR（[com.xbot.android.voice.StreamingAsrService]），仅聆听相位
 * 喂流；离开聆听（进入 THINKING/SPEAKING/IDLE）时 [MainScreenController.recognizedText]
 * 被清空，字幕自然消失。与云端 STT 解耦——字幕用端侧低延迟文本，对话用云端高准确文本。
 */
@Composable
private fun SubtitleOverlay(controller: MainScreenController) {
    val text = controller.recognizedText
    if (!controller.voiceListening || text.isBlank()) return
    // 与 DebugOverlay 同构：用 fillMaxSize 的 Box 包裹，Text 通过 align 定位到底部居中。
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = text,
            color = Color.White,
            style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp, start = 16.dp, end = 16.dp)  // 避让左下设置齿轮
                .widthIn(max = 360.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
