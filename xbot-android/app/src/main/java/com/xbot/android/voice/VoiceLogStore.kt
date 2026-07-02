package com.xbot.android.voice

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * 一条语音识别记录：识别到的文字 + 声纹判定的说话人。
 *
 * 由流式对话会话（云端 STT 的 final）触发记录：每识别到一句即追加一条，
 * [speakerName] / [isOwner] 反映声纹识别结果（命中主人昵称或其他人；未启用/未命中/未知 → null）。
 * 设置页「语音记录」据此查看历史识别内容并区分说话人。
 */
data class VoiceLogEntry(
    val timestamp: Long,
    /** 识别到的文字（云端 STT final）。 */
    val text: String,
    /** 声纹识别到的说话人姓名；null = 未启用/未命中/未知。 */
    val speakerName: String?,
    /** 说话人是否为已录入的「主人」([com.xbot.android.model.FamilyRelation.OWNER])。 */
    val isOwner: Boolean,
)

/**
 * 语音识别记录收集器（与 [ConversationLogger] 同构）。
 *
 * 以环形缓冲保存最近 [maxEntries] 条记录；[entries] 为 Compose 可观察快照列表，
 * 设置页订阅后实时刷新。记录同时输出到 logcat。
 *
 * 线程安全：基于 Compose SnapshotStateList，可从任意线程（WS/IO 回调）写入。
 */
class VoiceLogStore(private val maxEntries: Int = 500) {

    companion object { private const val TAG = "VoiceLog" }

    /** 当前所有记录（按时间顺序，最旧在前）。Compose 可观察。 */
    val entries = mutableStateListOf<VoiceLogEntry>()

    /** 追加一条记录并打印到 logcat。超出容量丢弃最早。 */
    fun log(text: String, speakerName: String?, isOwner: Boolean) {
        val e = VoiceLogEntry(System.currentTimeMillis(), text, speakerName, isOwner)
        synchronized(entries) {
            entries.add(e)
            while (entries.size > maxEntries) entries.removeAt(0)
        }
        val who = speakerName ?: "未知"
        Log.i(TAG, "[$who${if (isOwner) "/主人" else ""}] $text")
    }

    /** 清空所有记录。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
