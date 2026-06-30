package com.xbot.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xbot.android.core.AppPhase
import com.xbot.android.core.AppViewModel
import com.xbot.android.core.AppViewModelFactory
import com.xbot.android.ui.MainScreen
import com.xbot.android.ui.OnboardingScreen
import com.xbot.android.ui.rememberMainScreenController
import com.xbot.android.ui.XBotTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主 Activity：横屏全屏常亮 + 沉浸式。
 *
 * 根据 [AppViewModel.phase] 分流：
 * - LOADING：初始化中（短暂）
 * - ONBOARDING：首次激活向导
 * - READY：主界面（虚拟形象 + 视觉感知）
 */
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels {
        AppViewModelFactory(application as android.app.Application)
    }

    /** 阶段流（Compose 观察用）。 */
    private val phaseFlow = MutableStateFlow(AppPhase.LOADING)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果由 MainScreenController 重读感知 */ }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果由 MainScreenController 重读感知 */ }

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

        // 后台初始化（加载人物库/主人档案）。
        lifecycleScope.launch {
            appViewModel.initialize()
            phaseFlow.value = appViewModel.phase.get()
        }

        setContent {
            val phase by phaseFlow.asStateFlow().collectAsState()
            XBotTheme {
                when (phase) {
                    AppPhase.LOADING, AppPhase.ERROR, AppPhase.PERMISSION_DENIED -> {
                        com.xbot.android.ui.LoadingScreen()
                    }
                    AppPhase.ONBOARDING -> {
                        OnboardingScreen(
                            appViewModel = appViewModel,
                            onComplete = { phaseFlow.value = AppPhase.READY },
                        )
                    }
                    AppPhase.READY -> {
                        val controller = rememberMainScreenController(
                            lifecycleOwner = this,
                            hasCameraPermission = { checkCameraPermission() },
                            onRequestCamera = { requestCameraPermission() },
                            hasMicPermission = { checkMicPermission() },
                            onRequestMic = { requestMicPermission() },
                            peopleProvider = { appViewModel.personRepository.people },
                        )
                        MainScreen(controller)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 阶段可能在向导完成后变化，刷新一次。
        phaseFlow.value = appViewModel.phase.get()
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun checkMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
