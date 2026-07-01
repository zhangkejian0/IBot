package com.xbot.android.base

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * 底座控制服务：经典蓝牙 SPP(RFCOMM) 连接管理 + JSON 指令收发。
 * 对应 Flutter base_service.dart。
 *
 * 标准 SPP UUID 00001101-0000-1000-8000-00805F9B34AF。
 *
 * 对外暴露：
 * - [getBondedDevices] 系统已配对设备列表
 * - [connect] / [disconnect] 建立/断开 SPP 通道
 * - [send] 发送一条协议指令（move/home/stop/get_version…）
 * - [onFrameReceived] 接收回调（设备回的 ack/status/version…）
 */
class BaseService {

    companion object {
        private const val TAG = "BaseService"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34AF")
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class BaseStatus(
        val dof: Int = 2, val mode: String = "idle",
        val yaw: Double = 0.0, val pitch: Double = 0.0, val roll: Double = 0.0,
    )

    @Volatile var state: ConnectionState = ConnectionState.DISCONNECTED
        private set
    @Volatile var status: BaseStatus = BaseStatus()
        private set
    @Volatile var device: BluetoothDevice? = null
        private set

    val isConnected: Boolean get() = state == ConnectionState.CONNECTED

    /** 收到完整帧（JSON 文本）时的回调。 */
    var onFrameReceived: ((JSONObject) -> Unit)? = null

    private val protocol = BaseProtocol()
    private var socket: BluetoothSocket? = null
    private var readThread: Thread? = null
    @Volatile private var shouldRead = false

    /** 获取本机已配对的经典蓝牙设备列表。 */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(context: Context): List<BluetoothDevice> {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bm.adapter ?: return emptyList()
            adapter.bondedDevices.toList()
        } catch (e: Exception) {
            Log.e(TAG, "getBondedDevices: ${e.message}")
            emptyList()
        }
    }

    /** 连接到指定设备（建立 SPP/RFCOMM 通道）。 */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        if (state == ConnectionState.CONNECTING) return false
        this.device = device
        state = ConnectionState.CONNECTING
        return try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            protocol.reset()
            state = ConnectionState.CONNECTED
            startReading()
            Log.i(TAG, "已连接 ${device.name ?: device.address}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "连接失败: ${e.message}")
            state = ConnectionState.ERROR
            false
        }
    }

    /** 启动后台读取线程：字节流 → 协议切帧 → 回调。 */
    private fun startReading() {
        shouldRead = true
        readThread = Thread({
            val s = socket ?: return@Thread
            val input = try { s.inputStream } catch (_: Exception) { return@Thread }
            val buf = ByteArray(1024)
            while (shouldRead && s.isConnected) {
                try {
                    val n = input.read(buf)
                    if (n > 0) {
                        val frames = protocol.feed(buf.copyOf(n))
                        for (text in frames) {
                            try {
                                val obj = JSONObject(text)
                                handleFrame(obj)
                                onFrameReceived?.invoke(obj)
                            } catch (_: Exception) {
                                // 坏帧跳过。
                            }
                        }
                    }
                } catch (_: IOException) {
                    break // 连接断开。
                }
            }
            if (shouldRead) handleDisconnect("读取异常断开")
        }, "xbot-base-read").apply { isDaemon = true; start() }
    }

    /** 处理一帧：解析 status/version 更新本地状态。 */
    private fun handleFrame(obj: JSONObject) {
        val cmd = obj.optString("cmd")
        when (cmd) {
            "status" -> status = BaseStatus(
                dof = obj.optInt("dof", status.dof),
                mode = obj.optString("mode", status.mode),
                yaw = obj.optDouble("yaw", status.yaw),
                pitch = obj.optDouble("pitch", status.pitch),
                roll = obj.optDouble("roll", status.roll),
            )
        }
    }

    /** 发送一条协议指令。 */
    @SuppressLint("MissingPermission")
    fun send(cmd: String, params: Map<String, Any?> = emptyMap()): Boolean {
        val s = socket ?: return false
        if (!isConnected) return false
        val bytes = protocol.encode(cmd, params)
        return try {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "发送失败: ${e.message}")
            handleDisconnect("发送失败")
            false
        }
    }

    private fun handleDisconnect(reason: String) {
        shouldRead = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        state = ConnectionState.DISCONNECTED
        Log.i(TAG, "断开: $reason")
    }

    fun disconnect() {
        handleDisconnect("主动断开")
    }

    fun dispose() {
        disconnect()
    }
}
