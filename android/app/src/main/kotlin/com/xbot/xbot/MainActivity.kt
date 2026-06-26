package com.xbot.xbot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val batteryChannel = "xbot/battery"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, batteryChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "read" -> result.success(readBattery())
                    else -> result.notImplemented()
                }
            }
    }

    // 读取整机电池指标供 Flutter 侧做累计耗电统计。
    // chargeCounter（µAh）= 电池当前剩余电量，随时间下降；其差值即消耗量。
    private fun readBattery(): Map<String, Any> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeCounter =
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh
        val capacity =
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) // %
        val currentNow =
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) // µA（OEM 单位可能不同）

        // 电压与充电状态来自 sticky broadcast。
        val intent: Intent? =
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1 // mV
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return mapOf(
            "chargeCounter" to chargeCounter, // µAh（不支持时可能为极值/0）
            "capacity" to capacity,           // %
            "currentNow" to currentNow,       // µA
            "voltage" to voltage,             // mV
            "charging" to charging
        )
    }
}
