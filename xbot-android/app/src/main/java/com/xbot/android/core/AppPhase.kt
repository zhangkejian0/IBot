package com.xbot.android.core

/**
 * 应用整体阶段（对应 Flutter AppPhase）。
 *
 * - loading：初始化中
 * - downloading：首次启动下载重型模型资源（游戏式分发）
 * - onboarding：首次激活向导
 * - ready：主界面
 * - error / permissionDenied：错误页
 */
enum class AppPhase {
    LOADING,
    DOWNLOADING,
    ONBOARDING,
    READY,
    ERROR,
    PERMISSION_DENIED
}
