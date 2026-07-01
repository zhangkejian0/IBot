package com.xbot.android.base

/**
 * 底座控制协议层（字节流 ↔ JSON 帧）。对应 Flutter base_protocol.dart。
 *
 * 经典蓝牙 SPP(RFCOMM) 是字节流，约定每帧为单行 UTF-8 JSON 文本，行尾 '\n' 作帧分隔。
 */
class BaseProtocol {

    private val rxBuf = ArrayList<Byte>()

    /** 把一条 JSON 指令编码为发送字节：JSON + '\n'。 */
    fun encode(cmd: String, params: Map<String, Any?>): ByteArray {
        val json = StringBuilder("{\"cmd\":\"$cmd\"")
        for ((k, v) in params) {
            val value = when (v) {
                is String -> "\"$v\""
                is Number, is Boolean -> v.toString()
                null -> "null"
                else -> "\"$v\""
            }
            json.append(",\"$k\":$value")
        }
        json.append("}\n")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** 喂入接收字节，返回已完整成帧（按 '\n' 切出）的 JSON 文本列表。 */
    fun feed(bytes: ByteArray): List<String> {
        rxBuf.addAll(bytes.toList())
        val frames = mutableListOf<String>()
        while (true) {
            val nl = rxBuf.indexOf(0x0A.toByte()) // '\n'
            if (nl < 0) break
            val raw = rxBuf.subList(0, nl).toByteArray()
            repeat(nl + 1) { rxBuf.removeAt(0) }
            val text = String(raw, Charsets.UTF_8).trim()
            if (text.isNotEmpty()) frames.add(text)
        }
        return frames
    }

    /** 重置接收缓冲。 */
    fun reset() = rxBuf.clear()
}
