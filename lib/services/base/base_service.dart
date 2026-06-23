import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

import 'base_protocol.dart';

/// 底座连接状态。
enum BaseConnectionState {
  /// 未连接。
  disconnected,
  /// 正在连接。
  connecting,
  /// 已连接，可收发。
  connected,
  /// 出错。
  error,
}

/// 底座状态信息（来自设备 status/version 上报）。
class BaseStatus {
  final int dof; // 自由度 2/3
  final String mode; // idle/track/manual/sleep/demo
  final double yaw;
  final double pitch;
  final double roll;

  const BaseStatus({
    this.dof = 2,
    this.mode = 'idle',
    this.yaw = 0,
    this.pitch = 0,
    this.roll = 0,
  });

  BaseStatus copyWith({
    int? dof,
    String? mode,
    double? yaw,
    double? pitch,
    double? roll,
  }) =>
      BaseStatus(
        dof: dof ?? this.dof,
        mode: mode ?? this.mode,
        yaw: yaw ?? this.yaw,
        pitch: pitch ?? this.pitch,
        roll: roll ?? this.roll,
      );
}

/// 底座控制服务：经典蓝牙 SPP(RFCOMM) 连接管理 + JSON 指令收发。
///
/// 架构位置：协议文档「适配器层」的具体实现，对接开发板 BlueZ SPP 服务端
/// （服务名 XBot-Base，UUID 00001101-0000-1000-8000-00805F9B34AF，channel 1）。
///
/// 对外暴露：
///   - [state] / [status] 连接与设备状态（ChangeNotifier，UI 订阅刷新）
///   - [getBondedDevices] 系统已配对设备列表
///   - [connect] / [disconnect] 建立/断开 SPP 通道
///   - [send] 发送一条协议指令
///   - [onFrameReceived] 接收回调（设备回的 ack/status/version/...）
///
/// 生命周期：由 [AppController] 持有，随底座总开关或调试页按需 connect。
class BaseService extends ChangeNotifier {
  BaseService();

  // 标准 SPP UUID（Serial Port Profile），与板子端一致。
  static const String sppUuid = '00001101-0000-1000-8000-00805F9B34FB';

  final BaseProtocol _protocol = BaseProtocol();

  // 线缆日志广播流（TX/RX/SYS/ERR 文本），调试页订阅用于日志列表。
  final StreamController<WireLog> _wireLogCtrl =
      StreamController<WireLog>.broadcast();
  Stream<WireLog> get onWireLogStream => _wireLogCtrl.stream;

  void _logWire(String direction, String message) {
    _wireLogCtrl.add(WireLog(DateTime.now(), direction, message));
  }

  BluetoothConnection? _connection;
  StreamSubscription? _inputSub;

  BaseConnectionState _state = BaseConnectionState.disconnected;
  BaseConnectionState get state => _state;

  BaseStatus _status = const BaseStatus();
  BaseStatus get status => _status;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  BluetoothDevice? _device;
  BluetoothDevice? get device => _device;

  bool get isConnected => _state == BaseConnectionState.connected;

  /// 收到完整帧（已 JSON 解析）时的回调。UI 用于把 ack/status 写入日志。
  void Function(Map<String, dynamic> frame)? onFrameReceived;

  void _setState(BaseConnectionState s, {String? error}) {
    _state = s;
    _errorMessage = error;
    notifyListeners();
  }

  /// 获取本机已配对的经典蓝牙设备列表。
  ///
  /// 注意：经典蓝牙需先在系统蓝牙设置里 PIN 配对，此处只能读到已配对设备。
  /// 若返回空，提示用户去系统设置配对。
  Future<List<BluetoothDevice>> getBondedDevices() async {
    try {
      // 确保蓝牙已开启
      final enabled = await FlutterBluetoothSerial.instance.isEnabled ?? false;
      if (!enabled) {
        // 尝试请求开启（部分 ROM 需用户确认）
        await FlutterBluetoothSerial.instance.requestEnable();
      }
      return await FlutterBluetoothSerial.instance.getBondedDevices();
    } catch (e) {
      debugPrint('[BaseService] getBondedDevices error: $e');
      return [];
    }
  }

  /// 连接到指定设备（建立 SPP/RFCOMM 通道）。
  Future<bool> connect(BluetoothDevice device) async {
    if (_state == BaseConnectionState.connecting) return false;
    _device = device;
    _setState(BaseConnectionState.connecting);

    try {
      final connection = await BluetoothConnection.toAddress(device.address);
      _connection = connection;
      _protocol.reset();

      // 订阅输入流：字节 → 协议切帧 → 回调
      _inputSub = connection.input?.listen(
        (data) {
          final frames = _protocol.feed(data);
          for (final frame in frames) {
            _handleFrame(frame);
            onFrameReceived?.call(frame);
            _logWire('RX', jsonEncode(frame));
          }
        },
        onError: (e) {
          debugPrint('[BaseService] input stream error: $e');
          _handleDisconnect('接收错误: $e');
        },
        onDone: () {
          // 对端关闭连接
          _handleDisconnect('设备断开');
        },
      );

      _setState(BaseConnectionState.connected);
      _logWire('SYS', '已连接 ${device.name ?? device.address}');
      return true;
    } catch (e) {
      _handleDisconnect('连接失败: $e');
      return false;
    }
  }

  /// 处理一帧：解析 ack/status/version/error，更新本地状态。
  void _handleFrame(Map<String, dynamic> frame) {
    final cmd = frame['cmd'] as String?;
    switch (cmd) {
      case 'status':
        _status = BaseStatus(
          dof: (frame['dof'] as num?)?.toInt() ?? _status.dof,
          mode: frame['mode'] as String? ?? _status.mode,
          yaw: (frame['yaw'] as num?)?.toDouble() ?? _status.yaw,
          pitch: (frame['pitch'] as num?)?.toDouble() ?? _status.pitch,
          roll: (frame['roll'] as num?)?.toDouble() ?? _status.roll,
        );
        notifyListeners();
        break;
      case 'version':
        final dof = (frame['dof'] as num?)?.toInt();
        if (dof != null) {
          _status = _status.copyWith(dof: dof);
          notifyListeners();
        }
        break;
      case 'move':
      case 'ack':
      case 'angle':
      case 'heartbeat':
      case 'error':
        // 这些由 onFrameReceived 回调交给 UI，不在此更新本地状态
        break;
    }
  }

  /// 发送一条协议指令。
  ///
  /// [cmd] 指令名（move/home/stop/get_version/...），[params] 额外字段。
  Future<bool> send(String cmd, Map<String, dynamic> params) async {
    if (!isConnected || _connection == null) {
      _logWire('SYS', '未连接，发送失败');
      return false;
    }
    final bytes = _protocol.encode(cmd, params);
    final text = utf8.decode(bytes).trimRight(); // 去掉 \n 仅用于日志显示
    try {
      _connection!.output.add(bytes);
      await _connection!.output.allSent;
      _logWire('TX', text);
      return true;
    } catch (e) {
      _logWire('ERR', '发送失败: $e');
      _handleDisconnect('发送失败: $e');
      return false;
    }
  }

  void _handleDisconnect(String reason) {
    _inputSub?.cancel();
    _inputSub = null;
    _connection?.dispose();
    _connection = null;
    _setState(BaseConnectionState.disconnected, error: reason);
    _logWire('SYS', reason);
  }

  /// 主动断开。
  Future<void> disconnect() async {
    _handleDisconnect('主动断开');
  }

  @override
  void dispose() {
    _handleDisconnect('dispose');
    _wireLogCtrl.close();
    super.dispose();
  }
}

/// 线缆日志条目（方向 + 文本），用于调试页日志列表。
class WireLog {
  final DateTime time;
  final String direction; // TX / RX / SYS / ERR
  final String message;
  const WireLog(this.time, this.direction, this.message);
}
