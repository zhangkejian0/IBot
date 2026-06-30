package com.xbot.android.core

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xbot.android.behavior.ActivitySnapshot
import com.xbot.android.behavior.ActivityStateTracker
import com.xbot.android.behavior.BehaviorSnapshot
import com.xbot.android.behavior.BehaviorState
import com.xbot.android.behavior.BehaviorStateTracker
import com.xbot.android.camera.CameraManager
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.OwnerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用核心 ViewModel:替代 Flutter 的 AppController。
 *
 * 职责:
 * - 持有所有检测引擎和摄像头管理器
 * - 管理应用阶段(AppPhase)
 * - 汇聚检测结果 → 行为状态 → 表情状态
 * - 驱动 WebView 表情页
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AppViewModel"
    }

    // —— 应用阶段 ——
    private val _phase = MutableStateFlow(AppPhase.LOADING)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0.05f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _loadingMessage = MutableStateFlow("准备中…")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // —— 检测结果 ——
    private val _detectionResult = MutableStateFlow(DetectionResult())
    val detectionResult: StateFlow<DetectionResult> = _detectionResult.asStateFlow()

    private val _inferMs = MutableStateFlow(0L)
    val inferMs: StateFlow<Long> = _inferMs.asStateFlow()

    // —— 行为状态 ——
    private val behaviorTracker = BehaviorStateTracker()
    private val activityTracker = ActivityStateTracker()

    private val _behavior = MutableStateFlow(BehaviorSnapshot.INITIAL)
    val behavior: StateFlow<BehaviorSnapshot> = _behavior.asStateFlow()

    private val _activity = MutableStateFlow(ActivitySnapshot.INITIAL)
    val activity: StateFlow<ActivitySnapshot> = _activity.asStateFlow()

    // —— 主人档案 ——
    private val _ownerProfile = MutableStateFlow<OwnerProfile?>(null)
    val ownerProfile: StateFlow<OwnerProfile?> = _ownerProfile.asStateFlow()

    // —— 调试模式 ——
    // true:显示摄像头预览+检测覆盖层(调试用)
    // false:显示 WebView 表情页(正常模式)
    private val _debugMode = MutableStateFlow(false)
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    // —— 相机管理器 ——
    var cameraManager: CameraManager? = null
        private set

    /**
     * 初始化:权限 → 相机 → 模型。
     * 对应 Flutter 的 AppController.initialize()。
     * @param lifecycleOwner Activity 本身(CameraX 绑定需要)
     */
    fun initialize(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        // 防止重复初始化
        if (_phase.value != AppPhase.LOADING) return
        viewModelScope.launch {
            try {
                _loadingProgress.value = 0.2f
                _loadingMessage.value = "初始化摄像头…"

                val context = getApplication<Application>()

                _loadingProgress.value = 0.5f
                _loadingMessage.value = "加载人脸表情模型…"

                // 创建并启动相机管线(内部初始化所有检测引擎)
                cameraManager = CameraManager(context, lifecycleOwner)
                cameraManager?.start(
                    onResult = { result, inferMs ->
                        _detectionResult.value = result
                        _inferMs.value = inferMs
                        val now = System.currentTimeMillis()
                        _behavior.value = behaviorTracker.update(result, now)
                        _activity.value = activityTracker.update(result, now)
                    },
                    onInitialized = {
                        Log.i(TAG, "相机管线已启动")
                    }
                )

                _loadingProgress.value = 0.8f
                _loadingMessage.value = "读取已录入人物…"

                // TODO:从 Room 加载人物库

                _loadingProgress.value = 1.0f
                _loadingMessage.value = "准备就绪"

                _phase.value = AppPhase.READY

                Log.i(TAG, "初始化完成")
            } catch (e: Exception) {
                _phase.value = AppPhase.ERROR
                _errorMessage.value = "初始化失败: ${e.message}"
                Log.e(TAG, "初始化失败", e)
            }
        }
    }

    /**
     * 启动相机管线。在 Activity 的 onCreate 中调用。
     * @param lifecycleOwner Activity 本身
     */
    fun startCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        val context = getApplication<Application>()
        cameraManager = CameraManager(context, lifecycleOwner)
        cameraManager?.start(
            onResult = { result, inferMs ->
                // 此回调在后台线程触发,更新 StateFlow(线程安全)
                _detectionResult.value = result
                _inferMs.value = inferMs

                // 行为状态聚合
                val now = System.currentTimeMillis()
                _behavior.value = behaviorTracker.update(result, now)
                _activity.value = activityTracker.update(result, now)
            },
            onInitialized = {
                Log.i(TAG, "相机管线已启动")
            }
        )
    }

    /**
     * 根据行为状态返回表情页状态字符串。
     * 对应 Flutter 的 _pushAll 中的状态映射。
     */
    fun getFaceState(): String {
        return when (_behavior.value.state) {
            BehaviorState.DROWSY -> "sleepy"
            BehaviorState.FOCUSED -> "gazing"
            else -> "idle"
        }
    }

    /** 权限被拒绝时调用 */
    fun onPermissionDenied() {
        _phase.value = AppPhase.PERMISSION_DENIED
        _errorMessage.value = "未获得摄像头权限，请在系统设置中开启后重试。"
    }

    /** 切换调试模式 */
    fun toggleDebugMode() {
        _debugMode.value = !_debugMode.value
        Log.i(TAG, "调试模式: ${_debugMode.value}")
    }

    fun dispose() {
        cameraManager?.stop()
        behaviorTracker.reset()
        activityTracker.reset()
    }

    override fun onCleared() {
        super.onCleared()
        dispose()
    }
}
