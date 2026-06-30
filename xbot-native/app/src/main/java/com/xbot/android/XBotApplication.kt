package com.xbot.android

import android.app.Application
import android.os.StrictMode

/**
 * XBot 原生 Android 应用入口。
 *
 * 职责:
 * - 初始化全局单例(检测引擎、语音助手等)
 * - 配置 StrictMode(开发阶段)
 * - 持有 Application 级别的 Context 供 DI 使用
 */
class XBotApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 开发阶段启用 StrictMode 检测主线程 IO/网络
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }

    companion object {
        private const val TAG = "XBotApp"
    }
}
