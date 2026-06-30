package com.xbot.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xbot.android.ui.MainScreen
import com.xbot.android.ui.rememberMainScreenController

/**
 * 主 Activity：横屏全屏常亮 + 沉浸式。
 *
 * 阶段 0：请求相机权限 → 启动后台相机管线（FaceLandmarkEngine 推理）→
 * WebView(AssetLoader) 加载虚拟形象 → FaceBridge 注入 setGazeTarget/setState。
 *
 * 架构（对照 Flutter 的根本差异）：
 *   后台线程(camera-analysis):  CameraX ImageProxy → FrameAnalyzer → FaceLandmarkerEngine
 *                                                                     │ 主脸坐标 + blendshapes
 *                                                                     ▼
 *   主线程(Handler main):       FaceBridge.onFrame → JS 注入 → WebView 注视/表情
 */
class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限结果由 MainScreenController.observePermissionState 感知（重组时重读）。
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏 + 屏幕常亮。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            val controller = rememberMainScreenController(
                lifecycleOwner = this,
                hasCameraPermission = { checkCameraPermission() },
                onRequestCamera = { requestCameraPermission() },
            )
            MainScreen(controller)
        }
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
