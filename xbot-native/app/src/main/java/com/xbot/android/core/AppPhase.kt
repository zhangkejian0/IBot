package com.xbot.android.core

/**
 * 应用整体阶段。对应 Flutter 的 AppPhase enum。
 *
 * - [loading]:启动加载(权限/相机/模型)
 * - [onboarding]:首次激活向导(未注册主人)
 * - [ready]:主界面(已注册主人)
 * - [error]:初始化失败
 * - [permissionDenied]:未获得相机权限
 */
enum class AppPhase {
    LOADING,
    ONBOARDING,
    READY,
    ERROR,
    PERMISSION_DENIED
}
