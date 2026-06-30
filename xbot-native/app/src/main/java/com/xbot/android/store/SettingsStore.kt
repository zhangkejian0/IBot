package com.xbot.android.store

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xbot.android.core.AppSettings
import com.xbot.android.voice.PophieConfig
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 应用设置的本地持久化仓库。对应 Flutter 的 settings 持久化。
 *
 * - settings.json 存在 = 已保存过；否则用默认值。
 * - [settings] 为 Compose 可观察状态，设置页订阅后实时刷新。
 * - 首次加载时若 robotId 为空则生成并落盘（保持设备身份稳定）。
 */
class SettingsStore(private val context: Context) {

    companion object {
        private const val TAG = "SettingsStore"
        private const val FILE_NAME = "settings.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /** 当前设置（Compose 可观察）。 */
    var settings by mutableStateOf(AppSettings())
        private set

    private fun file(): File = File(context.filesDir, FILE_NAME)

    fun load() {
        try {
            val f = file()
            var s = if (f.exists()) {
                json.decodeFromString(AppSettings.serializer(), f.readText())
            } else {
                AppSettings()
            }
            // robotId 首次为空 → 生成并持久化。
            if (s.robotId.isBlank()) {
                s = s.copy(robotId = PophieConfig.newRobotId())
                writeToDisk(s)
            }
            settings = s
        } catch (e: Exception) {
            Log.e(TAG, "读取 settings.json 失败: ${e.message}")
            settings = AppSettings(robotId = PophieConfig.newRobotId())
        }
    }

    /** 更新设置（函数式拷贝）并持久化。 */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        settings = next
        writeToDisk(next)
    }

    /** 仅持久化 sessionId（服务端回传后调用，不触发其它逻辑）。 */
    fun persistSessionId(sessionId: String?) {
        if (settings.sessionId == sessionId) return
        update { it.copy(sessionId = sessionId) }
    }

    private fun writeToDisk(s: AppSettings) {
        try {
            file().writeText(json.encodeToString(AppSettings.serializer(), s))
        } catch (e: Exception) {
            Log.e(TAG, "写入 settings.json 失败: ${e.message}")
        }
    }

    /** 由当前设置构造 Pophie 配置（注入语音助手）。 */
    fun toPophieConfig(): PophieConfig = PophieConfig(
        baseUrl = settings.baseUrl,
        robotId = settings.robotId.ifBlank { PophieConfig.newRobotId() },
        sessionId = settings.sessionId,
        voiceId = settings.voiceId,
    )
}
