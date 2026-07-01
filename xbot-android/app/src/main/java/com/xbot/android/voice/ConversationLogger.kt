package com.xbot.android.voice

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * 一条对话交互记录。对应 Flutter conversation_logger.dart 的 ConversationEntry。
 *
 * 记录一轮对话各阶段的关键信息，供设置页「交互日志」查看，便于排查
 * 「说了话没反应」「表情卡住」「连接失败」等问题。
 */
data class ConversationEntry(
    val timestamp: Long,
    /** 阶段标识：wake / listen / think / speak / end / error 等。 */
    val stage: String,
    /** 可读详情（识别文本、LLM 回复、字节数、错误信息等）。 */
    val message: String,
    /** 是否为错误记录（UI 红色标记）。 */
    val error: Boolean = false,
)

/**
 * 对话交互日志收集器。对应 Flutter ConversationLogger。
 *
 * 以环形缓冲保存最近 [maxEntries] 条记录；[entries] 为 Compose 可观察的
 * 快照列表，设置页订阅后实时刷新。记录同时输出到 logcat。
 *
 * 线程安全：基于 Compose SnapshotStateList，可从任意线程（WS/IO 回调）写入。
 */
class ConversationLogger(private val maxEntries: Int = 200) {

    companion object { private const val TAG = "ConvLog" }

    /** 当前所有记录（按时间顺序，最旧在前）。Compose 可观察。 */
    val entries = mutableStateListOf<ConversationEntry>()

    /** 追加一条记录并打印到 logcat。超出容量丢弃最早。 */
    fun log(stage: String, message: String, error: Boolean = false) {
        val e = ConversationEntry(System.currentTimeMillis(), stage, message, error)
        synchronized(entries) {
            entries.add(e)
            while (entries.size > maxEntries) entries.removeAt(0)
        }
        if (error) Log.e(TAG, "$stage: $message") else Log.i(TAG, "$stage: $message")
    }

    /** 清空所有记录。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
