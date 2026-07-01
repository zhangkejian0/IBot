package com.xbot.android.log

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.Locale

/**
 * 人物日志 HTTP 浏览服务。对应 Flutter persona_log_server.dart。
 *
 * 局域网内电脑端浏览器访问，查看按天的人物行为日志和网络交互日志。
 * - GET /              日志首页（列出日期 + 链接）
 * - GET /day?d=yyyy-MM-dd  某天人物日志
 * - GET /net           网络交互日志列表
 * - GET /net?d=yyyy-MM-dd  某天网络日志原文
 *
 * 端口自动选择（:0 → 系统分配空闲端口）。
 */
class PersonaLogServer(
    private val context: Context,
    private val personaLogger: PersonaLogger,
    private val networkLogger: NetworkLogger,
) : NanoHTTPD(0) {

    companion object { private const val TAG = "PersonaLogServer" }

    @Volatile private var serverUrl: String? = null

    /** 启动并返回可访问 URL。 */
    fun startAndGetUrl(): String {
        serverUrl?.let { return it }
        start()
        val url = "http://localhost:$listeningPort"
        serverUrl = url
        Log.i(TAG, "日志服务启动: $url")
        return url
    }

    fun stopServer() {
        stop()
        serverUrl = null
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val params = session.parameters
        return try {
            when {
                path == "/" -> newFixedLengthResponse(buildIndexPage())
                path == "/day" -> {
                    val day = params["d"]?.firstOrNull() ?: personaLogger.listDays().firstOrNull() ?: ""
                    val entries = if (day.isNotEmpty()) personaLogger.readDay(day) else emptyList()
                    newFixedLengthResponse(buildDayPage(day, entries))
                }
                path == "/net" -> {
                    val day = params["d"]?.firstOrNull() ?: networkLogger.listDays().firstOrNull() ?: ""
                    val raw = if (day.isNotEmpty()) networkLogger.readDay(day) else "（无记录）"
                    newFixedLengthResponse(buildNetPage(day, raw))
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse("Error: ${e.message}")
        }
    }

    private fun buildIndexPage(): String {
        val days = personaLogger.listDays()
        val links = days.joinToString("") { d -> """<li><a href="/day?d=$d">$d</a> | <a href="/net?d=$d">网络日志</a></li>""" }
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><title>XBot 日志</title>
            <style>body{font-family:sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px}a{color:#80c0ff}li{margin:8px 0}</style>
            </head><body><h1>XBot 人物日志</h1><ul>$links</ul></body></html>"""
    }

    private fun buildDayPage(day: String, entries: List<PersonaLogEntry>): String {
        val rows = entries.joinToString("") { e ->
            val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(e.timestamp))
            """<tr><td>$time</td><td>${e.type}</td><td>${e.person ?: ""}</td><td>${e.expression ?: ""}</td><td>${e.gesture ?: ""}</td><td>${e.objects?.joinToString(",") ?: ""}</td><td>${e.heldObject ?: ""}</td><td>${e.behaviorState ?: ""}</td><td>${e.userText ?: ""}</td><td>${e.replyText ?: ""}</td></tr>"""
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><title>$day 日志</title>
            <style>body{font-family:monospace;background:#1a1a2e;color:#e0e0e0;padding:20px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #333;padding:4px 8px;font-size:13px}th{background:#2a2a4e}</style>
            </head><body><h1>$day 人物日志</h1><p><a href="/">← 返回</a></p>
            <table><tr><th>时间</th><th>类型</th><th>人物</th><th>表情</th><th>手势</th><th>物体</th><th>手持</th><th>状态</th><th>用户说</th><th>机器人回复</th></tr>
            $rows</table></body></html>"""
    }

    private fun buildNetPage(day: String, raw: String): String {
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><title>$day 网络日志</title>
            <style>body{font-family:monospace;background:#1a1a2e;color:#e0e0e0;padding:20px}pre{white-space:pre-wrap;word-break:break-all;font-size:12px}</style>
            </head><body><h1>$day 网络交互日志</h1><p><a href="/">← 返回</a></p>
            <pre>${raw.replace("<", "&lt;").replace(">", "&gt;")}</pre></body></html>"""
    }
}

private const val TAG = "PersonaLogServer"
