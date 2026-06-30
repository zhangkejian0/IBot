package com.xbot.android.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xbot.android.webview.FaceWebView
import kotlinx.coroutines.delay

/**
 * 主界面（阶段 1）。
 *
 * 两种互斥模式（由 controller.debugMode 控制，对应 Flutter 调试开关）：
 * - 虚拟宠物模式（默认）：全屏虚拟形象 WebView，注视跟随 + 注意力态驱动表情。
 * - 调试模式：摄像头识别覆盖层（人脸框/网格/手势骨架/物体/状态面板）。
 *
 * 识别在两种模式下都常驻运行（仅决定把结果画到屏幕还是驱动虚拟形象）。
 */
@Composable
fun MainScreen(controller: MainScreenController) {
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

        // —— 右上角调试浮层（两种模式都显示，对标 native-prototype）——
        DebugOverlay(controller)

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

        // 右上角：推理耗时 / 帧率。
        Text(
            text = "原生后台线程\n推理: ${"%.1f".format(controller.fps)} fps\n耗时: ${controller.inferMs} ms/帧",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(8.dp),
        )
    }
}
