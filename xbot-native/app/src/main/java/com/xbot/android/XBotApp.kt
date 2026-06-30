package com.xbot.android

import android.app.Application

/**
 * 应用入口。阶段 0 仅做最小初始化；阶段 3 起在此持有全局组件（如语音助手配置）。
 */
class XBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: XBotApp
            private set
    }
}
