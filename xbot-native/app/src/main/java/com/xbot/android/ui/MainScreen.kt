package com.xbot.android.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xbot.android.webview.FaceWebView
import kotlinx.coroutines.delay

/**
 * 阶段 0 主界面：全屏虚拟形象 WebView（注视跟随）+ 右上角调试浮层（推理耗时/帧率）。
 *
 * 对应 native-prototype 的 activity_main.xml（全屏 WebView + 右上角 TextView），
 * 但用 Compose 实现，并为后续阶段（设置入口、双击聆听、跑马灯等）留出结构。
 */
@Composable
fun MainScreen(controller: MainScreenController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
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

        // —— 右上角调试浮层（对标 native-prototype）——
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

/** 右上角调试浮层：推理耗时 / 帧率 / 人脸数 / 中心坐标。 */
@Composable
private fun DebugOverlay(controller: MainScreenController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text = "原生后台线程\n" +
                "推理: ${"%.1f".format(controller.fps)} fps\n" +
                "耗时: ${controller.inferMs} ms/帧\n" +
                "人脸: ${controller.faceCount}\n" +
                "中心: ${"%.2f".format(controller.faceCenterX)},${"%.2f".format(controller.faceCenterY)}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(8.dp),
        )
    }
}
