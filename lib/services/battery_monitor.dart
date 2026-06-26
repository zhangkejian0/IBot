import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 整机累计耗电统计。
///
/// 开启时记录起始电量（电池剩余 µAh + 百分比），每分钟采样刷新，可读出
/// 「运行时长 / 消耗 mAh / 消耗百分比 / 瞬时功率」。数据来自原生 BatteryManager
/// （见 MainActivity.kt 的 `xbot/battery` 通道）。
///
/// 注意：这是**整机**耗电，并非本 App 单独耗电；**充电时数据无意义**
/// （读到的是充电而非放电）。
class BatteryMonitor extends ChangeNotifier {
  static const MethodChannel _channel = MethodChannel('xbot/battery');

  /// 采样间隔（每分钟刷新一次累计值）。
  static const Duration sampleInterval = Duration(minutes: 1);

  Timer? _timer;
  bool _running = false;
  bool get isRunning => _running;

  /// 通道是否可用（非 Android 或读取失败时为 false）。
  bool _supported = true;
  bool get supported => _supported;

  DateTime? _startTime;
  int? _startCharge; // µAh
  int? _startCapacity; // %
  int? _nowCharge; // µAh
  int? _nowCapacity; // %
  int _currentNow = 0; // µA
  int _voltage = 0; // mV
  bool _charging = false;
  bool get charging => _charging;

  /// 是否拿到了有效的 chargeCounter（部分机型不支持，则只能用百分比）。
  bool get hasChargeCounter => _startCharge != null;

  /// 已运行时长。
  Duration get elapsed => _startTime == null
      ? Duration.zero
      : DateTime.now().difference(_startTime!);

  /// 已消耗电量（mAh）。chargeCounter 不支持时返回 null。
  double? get consumedMah {
    if (_startCharge == null || _nowCharge == null) return null;
    return (_startCharge! - _nowCharge!) / 1000.0; // µAh → mAh
  }

  /// 已消耗百分比（整机）。
  int? get consumedPercent {
    if (_startCapacity == null || _nowCapacity == null) return null;
    return _startCapacity! - _nowCapacity!;
  }

  /// 平均电流（mA）= 消耗 mAh / 已运行小时数。
  double? get averageMa {
    final mah = consumedMah;
    final h = elapsed.inSeconds / 3600.0;
    if (mah == null || h <= 0) return null;
    return mah / h;
  }

  /// 瞬时功率（mW）= 电压(V) × 电流(A)。
  double get instantPowerMw => (_voltage * _currentNow.abs()) / 1e6;

  /// 开始统计：记录当前电量为起点并启动每分钟采样。
  Future<void> start() async {
    if (_running) return;
    _running = true;
    await _baseline();
    _timer = Timer.periodic(sampleInterval, (_) => _sample());
    notifyListeners();
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    _running = false;
    notifyListeners();
  }

  /// 以当前电量为新起点重新计量。
  Future<void> reset() async {
    await _baseline();
    notifyListeners();
  }

  Future<void> _baseline() async {
    _startTime = DateTime.now();
    _startCharge = null;
    _startCapacity = null;
    await _sample(initial: true);
  }

  Future<void> _sample({bool initial = false}) async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('read');
      if (res == null) {
        _supported = false;
        return;
      }
      final charge = (res['chargeCounter'] as num?)?.toInt() ?? 0;
      final capacity = (res['capacity'] as num?)?.toInt() ?? -1;
      _currentNow = (res['currentNow'] as num?)?.toInt() ?? 0;
      _voltage = (res['voltage'] as num?)?.toInt() ?? 0;
      _charging = res['charging'] == true;

      // chargeCounter 不支持时常返回极值/0/负；做合理性校验（0 < x < 100Ah）。
      final validCharge = charge > 0 && charge < 100000000;
      final validCap = capacity >= 0 && capacity <= 100;
      if (initial) {
        _startCharge = validCharge ? charge : null;
        _startCapacity = validCap ? capacity : null;
      }
      _nowCharge = validCharge ? charge : null;
      _nowCapacity = validCap ? capacity : null;
    } catch (e) {
      _supported = false;
      debugPrint('[Battery] read failed: $e');
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
