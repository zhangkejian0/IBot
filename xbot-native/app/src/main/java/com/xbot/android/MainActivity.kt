package com.xbot.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xbot.android.core.AppPhase
import com.xbot.android.core.AppViewModel
import com.xbot.android.ui.loading.LoadingScreen
import com.xbot.android.ui.main.CameraScreen

/**
 * 主 Activity:横屏全屏,沉浸模式。
 *
 * 职责:
 * - 配置横屏全屏/沉浸模式/唤醒锁(对应 Flutter 的 main.dart + app.dart)
 * - 请求相机+麦克风权限
 * - 根据 AppPhase 切换界面(Loading/Onboarding/CameraScreen)
 * - 持有 AppViewModel
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 权限请求回调 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted) {
            // 相机权限已获得,启动初始化
            viewModel.initialize(this)
        } else {
            viewModel.onPermissionDenied()
        }

        // 麦克风权限失败不阻断主流程(语音助手不可用,但核心功能正常)
        if (!micGranted) {
            android.util.Log.w("MainActivity", "麦克风权限未获得,语音助手不可用")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 横屏全屏 + 沉浸模式
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 防止息屏(陪伴机器人长时间运行)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 请求权限(相机+麦克风),获得后自动启动初始化
        requestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val phase by viewModel.phase.collectAsState()
                    val loadingProgress by viewModel.loadingProgress.collectAsState()
                    val loadingMessage by viewModel.loadingMessage.collectAsState()

                    when (phase) {
                        AppPhase.LOADING, AppPhase.ERROR, AppPhase.PERMISSION_DENIED -> {
                            LoadingScreen(
                                progress = loadingProgress,
                                message = loadingMessage
                            )
                        }
                        AppPhase.ONBOARDING -> {
                            // TODO:OnboardingScreen()
                            LoadingScreen(progress = 1f, message = "首次激活向导(待实现)")
                        }
                        AppPhase.READY -> {
                            CameraScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    /** 请求相机+麦克风权限。已授权则直接启动初始化。 */
    private fun requestPermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            // 相机权限已有,直接初始化
            viewModel.initialize(this)
        } else {
            // 请求相机+麦克风(麦克风失败不阻断)
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.dispose()
    }
}
