package com.xbot.android.log

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络交互日志记录器。对应 Flutter network_logger.dart。
 *
 * 作为 OkHttp 拦截器，记录所有发往 Pophie 后端的请求/响应（按天 JSONL 文件）。
 * 供 PersonaLogServer 的 /net 路径展示，便于排查「语音交互到底提交了哪些信息」。
 */
class NetworkLogger(private val context: Context) : Interceptor {

    companion object {
        private const val TAG = "NetworkLogger"
        private const val DIR = "logs/net"
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    @Volatile
    var enabled: Boolean = true

    private fun dayFile(now: Long = System.currentTimeMillis()): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${dayFmt.format(Date(now))}.jsonl")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val reqSummary = """{"ts":${System.currentTimeMillis()},"method":"${req.method}","url":"${req.url}","reqBody":${truncateBody(req.body?.toString())}}"""
        val resp = try {
            chain.proceed(req)
        } catch (e: Exception) {
            if (enabled) {
                dayFile().appendText("""$reqSummary,"error":"${e.message}"}""" + "\n")
            }
            throw e
        }
        if (enabled) {
            try {
                val respCode = resp.code
                val respBody = truncateBody(resp.peekBody(2048L).string())
                dayFile().appendText("""$reqSummary,"respCode":$respCode,"respBody":$respBody}""" + "\n")
            } catch (_: Exception) {}
        }
        return resp
    }

    private fun truncateBody(body: String?, maxLen: Int = 2000): String {
        if (body == null) return "\"\""
        val truncated = if (body.length > maxLen) body.substring(0, maxLen) + "…" else body
        // 转义换行和引号（避免破坏 JSONL）。
        return "\"${truncated.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }

    /** 读取某天网络日志原文（JSONL）。 */
    fun readDay(day: String): String {
        val file = File(context.filesDir, "$DIR/$day.jsonl")
        return if (file.exists()) file.readText() else "（无记录）"
    }

    fun listDays(): List<String> {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending() ?: emptyList()
    }
}
