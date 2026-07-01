package com.xbot.android.log

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 人物行为日志记录器（按天持久化）。对应 Flutter persona_logger.dart。
 *
 * 记录类型：perception（感知帧）/ state（注意力状态转移）/ activity（活动转移）/ conversation（对话）。
 * 按 JSONL 文件按天存储（logs/yyyy-MM-dd.jsonl），供后续分析人物陪伴行为。
 */
class PersonaLogger(private val context: Context) {

    companion object {
        private const val TAG = "PersonaLogger"
        private const val DIR = "logs"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    @Volatile
    var enabled: Boolean = true

    private fun dayFile(now: Long = System.currentTimeMillis()): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${dayFmt.format(Date(now))}.jsonl")
    }

    /** 写一条日志（JSONL 追加）。 */
    fun log(entry: PersonaLogEntry) {
        if (!enabled) return
        try {
            val line = json.encodeToString(PersonaLogEntry.serializer(), entry)
            dayFile(entry.timestamp).appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败: ${e.message}")
        }
    }

    /** 便捷方法：感知帧。 */
    fun logPerception(
        person: String?, expression: String?, gesture: String?,
        objects: List<String>?, heldObject: String?, faceCount: Int,
        behaviorState: String, scene: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        log(PersonaLogEntry(
            timestamp = now, type = "perception",
            person = person, expression = expression, gesture = gesture,
            objects = objects, heldObject = heldObject, faceCount = faceCount,
            behaviorState = behaviorState, scene = scene,
        ))
    }

    /** 便捷方法：状态转移。 */
    fun logStateTransition(toState: String, durationSec: Int, person: String?, now: Long = System.currentTimeMillis()) {
        log(PersonaLogEntry(
            timestamp = now, type = "state",
            person = person, behaviorState = toState, behaviorDurationSeconds = durationSec,
        ))
    }

    /** 便捷方法：对话。 */
    fun logConversation(userText: String, replyText: String, robotState: String?, person: String?, now: Long = System.currentTimeMillis()) {
        log(PersonaLogEntry(
            timestamp = now, type = "conversation",
            person = person, userText = userText, replyText = replyText, robotState = robotState,
        ))
    }

    /** 列出所有日志文件（供 PersonaLogServer 列表）。 */
    fun listDays(): List<String> {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending() ?: emptyList()
    }

    /** 读取某天的全部日志（供 PersonaLogServer 展示）。 */
    fun readDay(day: String): List<PersonaLogEntry> {
        val file = File(context.filesDir, "$DIR/$day.jsonl")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString(PersonaLogEntry.serializer(), it) }
        } catch (e: Exception) {
            Log.e(TAG, "读取日志失败: ${e.message}")
            emptyList()
        }
    }

    fun dispose() { /* 无需特殊处理 */ }
}

/** 一条人物日志记录。 */
@Serializable
data class PersonaLogEntry(
    val timestamp: Long,
    val type: String, // perception / state / activity / conversation
    val person: String? = null,
    val expression: String? = null,
    val gesture: String? = null,
    val objects: List<String>? = null,
    val heldObject: String? = null,
    val faceCount: Int = 0,
    val behaviorState: String? = null,
    val behaviorDurationSeconds: Int = 0,
    val scene: String? = null,
    val userText: String? = null,
    val replyText: String? = null,
    val robotState: String? = null,
)
