import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../services/base/base_service.dart';
import '../../theme/app_theme.dart';

/// 底座控制调试页面。
///
/// 与开发板经典蓝牙 SPP 服务端对接（服务名 XBot-Base，UUID 00001101-...）：
/// - 列出系统已配对设备，选择一个建立 SPP 连接
/// - 手动角度控制（Yaw/Pitch/Roll 滑杆）→ 真实发送 move 指令
/// - 测试命令按钮（模式/速度/查询状态版本）→ 真实发送
/// - 指令日志：TX(发送)/RX(接收)/SYS(系统) 实时记录
///
/// 收发数据源来自 [AppController.baseService]（真实蓝牙），不再是 Mock。
class BaseDebugScreen extends StatefulWidget {
  const BaseDebugScreen({super.key});

  @override
  State<BaseDebugScreen> createState() => _BaseDebugScreenState();
}

class _BaseDebugScreenState extends State<BaseDebugScreen> {
  // —— 手动控制目标值（本地，连接后才发送）——
  double _targetYaw = 0;
  double _targetPitch = 0;
  double _targetRoll = 0;

  // —— 指令日志 ——
  final List<_LogEntry> _logs = [];
  final ScrollController _logScrollCtrl = ScrollController();

  late BaseService _base;
  StreamSubscription? _wireSub;

  @override
  void initState() {
    super.initState();
    // initState 里不能直接 AppScope.of，dispose 后延迟绑定；
    // 这里在 build 首帧后绑定 wire 日志回调。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _base = AppScope.of(context).baseService;
      _wireSub = _base.onWireLogStream.listen(_onWireLog);
    });
  }
  @override
  void dispose() {
    _wireSub?.cancel();
    _logScrollCtrl.dispose();
    super.dispose();
  }

  void _onWireLog(WireLog log) {
    setState(() {
      _logs.add(_LogEntry(
        time: log.time,
        direction: log.direction,
        message: log.message,
      ));
      if (_logs.length > 100) _logs.removeAt(0);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScrollCtrl.hasClients) {
        _logScrollCtrl.jumpTo(_logScrollCtrl.position.maxScrollExtent);
      }
    });
  }

  void _clearLogs() => setState(() => _logs.clear());

  // ==================== 发送 ====================

  void _send(String cmd, Map<String, dynamic> params) =>
      _base.send(cmd, params);

  // ==================== UI ====================

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    _base = controller.baseService;
    final status = _base.status;

    return CupertinoPageScaffold(
      backgroundColor: AppTheme.background,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xF01C1C1E),
        middle: const Text('底座调试',
            style: TextStyle(color: AppTheme.label, decoration: TextDecoration.none)),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => _showInfo(context),
          child: const Icon(CupertinoIcons.info_circle,
              color: AppTheme.secondaryLabel),
        ),
      ),
      child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          return SafeArea(
            child: ListView(
              padding: const EdgeInsets.symmetric(vertical: 16),
              children: [
                _buildFaceDetectionSection(controller),
                const SizedBox(height: 16),
                _buildConnectionSection(),
                const SizedBox(height: 16),
                _buildManualControlSection(status),
                const SizedBox(height: 16),
                _buildTestCommandsSection(),
                const SizedBox(height: 16),
                _buildLogSection(),
              ],
            ),
          );
        },
      ),
    );
  }

  // —— 人脸检测位置（调试跟随映射）——

  Widget _buildFaceDetectionSection(AppController controller) {
    final result = controller.result;
    final face = result.face;
    final faceCount = result.faces.length;

    return _Section(
      title: '人脸检测',
      child: Column(
        children: [
          _InfoRow(
            label: '检测数量',
            value: '$faceCount 张',
            valueColor:
                faceCount > 0 ? AppTheme.accentGreen : AppTheme.secondaryLabel,
          ),
          if (face != null) ...[
            Container(height: 1, color: AppTheme.separator,
                margin: const EdgeInsets.symmetric(vertical: 8)),
            _InfoRow(label: '注视 X', value: face.gazeX.toStringAsFixed(3),
                valueColor: _gazeColor(face.gazeX)),
            _InfoRow(label: '注视 Y', value: face.gazeY.toStringAsFixed(3),
                valueColor: _gazeColor(face.gazeY)),
            Container(height: 1, color: AppTheme.separator,
                margin: const EdgeInsets.symmetric(vertical: 8)),
            _InfoRow(label: '映射 Yaw',
                value: '${(face.gazeX * 90).toStringAsFixed(1)}°',
                valueColor: AppTheme.accentTeal),
            _InfoRow(label: '映射 Pitch',
                value: '${(face.gazeY * 30).toStringAsFixed(1)}°',
                valueColor: AppTheme.accentTeal),
          ] else ...[
            const SizedBox(height: 8),
            const Center(
              child: Text('未检测到人脸',
                  style: TextStyle(
                      color: AppTheme.tertiaryLabel,
                      fontSize: 13,
                      decoration: TextDecoration.none)),
            ),
          ],
        ],
      ),
    );
  }

  Color _gazeColor(double value) {
    if (value.abs() < 0.1) return AppTheme.secondaryLabel;
    return value > 0 ? AppTheme.accentGreen : AppTheme.accent;
  }

  // —— 连接状态 ——

  Widget _buildConnectionSection() {
    final connected = _base.isConnected;
    final stateText = _connectionStateText(_base.state);
    final status = _base.status;

    return _Section(
      title: '连接状态',
      child: Column(
        children: [
          _InfoRow(
            label: '设备',
            value: connected
                ? (_base.device?.name ?? _base.device?.address ?? '已连接')
                : '未连接',
            valueColor:
                connected ? AppTheme.accentGreen : AppTheme.secondaryLabel,
          ),
          _InfoRow(label: '状态', value: stateText),
          if (connected) ...[
            _InfoRow(label: '自由度', value: '${status.dof} DOF'),
            _InfoRow(label: '模式', value: status.mode.toUpperCase()),
            _InfoRow(
              label: '当前角度',
              value: 'Y:${status.yaw.toStringAsFixed(1)}° '
                  'P:${status.pitch.toStringAsFixed(1)}° '
                  'R:${status.roll.toStringAsFixed(1)}°',
            ),
          ],
          if (_base.errorMessage != null && !connected)
            Padding(
              padding: const EdgeInsets.only(top: 4, bottom: 4),
              child: Text(_base.errorMessage!,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      color: AppTheme.accentRed,
                      fontSize: 12,
                      decoration: TextDecoration.none)),
            ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: CupertinoButton.filled(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  onPressed: connected ? null : _showDevicePicker,
                  child: const Text('选择设备连接',
                      style: TextStyle(decoration: TextDecoration.none)),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: CupertinoButton(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  color: AppTheme.accentRed,
                  onPressed: connected ? () => _base.disconnect() : null,
                  child: const Text('断开',
                      style: TextStyle(decoration: TextDecoration.none)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _connectionStateText(BaseConnectionState s) {
    switch (s) {
      case BaseConnectionState.disconnected:
        return '未连接';
      case BaseConnectionState.connecting:
        return '连接中…';
      case BaseConnectionState.connected:
        return '已连接';
      case BaseConnectionState.error:
        return '错误';
    }
  }

  /// 弹出系统已配对设备列表，选择一个连接。
  Future<void> _showDevicePicker() async {
    _addLocalLog('SYS', '正在获取已配对设备…');
    final devices = await _base.getBondedDevices();
    if (!mounted) return;
    if (devices.isEmpty) {
      _addLocalLog('ERR', '没有已配对设备。请先在系统蓝牙设置里配对底座(XBot-Base)。');
      showCupertinoDialog(
        context: context,
        builder: (_) => CupertinoAlertDialog(
          title: const Text('未发现已配对设备',
              style: TextStyle(decoration: TextDecoration.none)),
          content: const Text(
              '经典蓝牙需要先在系统蓝牙设置里配对底座。\n\n'
              '1. 打开系统「设置 → 蓝牙」\n'
              '2. 找到名为 XBot-Base 的设备\n'
              '3. 点击配对（PIN 默认 1234，SSP 直接确认）\n'
              '4. 配对完成后回到此处选择连接',
              style: TextStyle(decoration: TextDecoration.none)),
          actions: [
            CupertinoDialogAction(
              child: const Text('知道了',
                  style: TextStyle(decoration: TextDecoration.none)),
              onPressed: () => Navigator.pop(context),
            ),
          ],
        ),
      );
      return;
    }
    // 弹底部选择列表
    final selected = await showCupertinoModalPopup<BluetoothDevice>(
      context: context,
      builder: (_) => CupertinoActionSheet(
        title: const Text('选择底座设备',
            style: TextStyle(decoration: TextDecoration.none)),
        actions: devices
            .map((d) => CupertinoActionSheetAction(
                  onPressed: () => Navigator.pop(context, d),
                  child: Text('${d.name ?? '(未命名)'}\n${d.address}',
                      style: const TextStyle(decoration: TextDecoration.none)),
                ))
            .toList(),
        cancelButton: CupertinoActionSheetAction(
          isDefaultAction: true,
          onPressed: () => Navigator.pop(context),
          child: const Text('取消',
              style: TextStyle(decoration: TextDecoration.none)),
        ),
      ),
    );
    if (selected == null) return;
    _addLocalLog('SYS', '正在连接 ${selected.name ?? selected.address}…');
    final ok = await _base.connect(selected);
    if (!mounted) return;
    _addLocalLog(ok ? 'SYS' : 'ERR',
        ok ? '连接成功' : '连接失败：${_base.errorMessage}');
  }

  void _addLocalLog(String direction, String message) {
    _onWireLog(WireLog(DateTime.now(), direction, message));
  }

  // —— 手动控制 ——

  Widget _buildManualControlSection(BaseStatus status) {
    final connected = _base.isConnected;
    return _Section(
      title: '手动控制',
      child: Column(
        children: [
          _AngleSlider(
            label: 'Yaw (水平)',
            value: _targetYaw,
            min: -90,
            max: 90,
            onChanged: connected ? (v) => setState(() => _targetYaw = v) : null,
          ),
          _AngleSlider(
            label: 'Pitch (垂直)',
            value: _targetPitch,
            min: -30,
            max: 30,
            onChanged:
                connected ? (v) => setState(() => _targetPitch = v) : null,
          ),
          _AngleSlider(
            label: 'Roll (横滚)',
            value: _targetRoll,
            min: -45,
            max: 45,
            onChanged: connected && status.dof >= 3
                ? (v) => setState(() => _targetRoll = v)
                : null,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: CupertinoButton.filled(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  onPressed: connected
                      ? () => _send('move', {
                            'yaw': _targetYaw,
                            'pitch': _targetPitch,
                            'roll': _targetRoll,
                          })
                      : null,
                  child: const Text('发送',
                      style: TextStyle(decoration: TextDecoration.none)),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: CupertinoButton(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  color: AppTheme.groupedBackground,
                  onPressed: connected
                      ? () {
                          _send('home', {});
                          setState(() {
                            _targetYaw = 0;
                            _targetPitch = 0;
                            _targetRoll = 0;
                          });
                        }
                      : null,
                  child: const Text('回原点',
                      style: TextStyle(
                          color: AppTheme.label,
                          decoration: TextDecoration.none)),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: CupertinoButton(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  color: AppTheme.accentRed,
                  onPressed: connected ? () => _send('stop', {}) : null,
                  child: const Text('停止',
                      style: TextStyle(decoration: TextDecoration.none)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // —— 测试命令 ——

  Widget _buildTestCommandsSection() {
    final connected = _base.isConnected;
    return _Section(
      title: '测试命令',
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          _CmdButton(label: 'IDLE', enabled: connected,
              onPressed: () => _send('set_mode', {'mode': 'idle'})),
          _CmdButton(label: 'TRACK', enabled: connected,
              onPressed: () => _send('set_mode', {'mode': 'track'})),
          _CmdButton(label: 'MANUAL', enabled: connected,
              onPressed: () => _send('set_mode', {'mode': 'manual'})),
          _CmdButton(label: 'SLEEP', enabled: connected,
              onPressed: () => _send('set_mode', {'mode': 'sleep'})),
          _CmdButton(label: 'DEMO', enabled: connected,
              onPressed: () => _send('set_mode', {'mode': 'demo'})),
          _CmdButton(
              label: '速度 50%',
              enabled: connected,
              onPressed: () => _send('set_speed',
                  {'yaw_speed': 50, 'pitch_speed': 50})),
          _CmdButton(
              label: '速度 100%',
              enabled: connected,
              onPressed: () => _send('set_speed',
                  {'yaw_speed': 100, 'pitch_speed': 100})),
          _CmdButton(label: '查询状态', enabled: connected,
              onPressed: () => _send('get_status', {})),
          _CmdButton(label: '查询版本', enabled: connected,
              onPressed: () => _send('get_version', {})),
        ],
      ),
    );
  }

  // —— 指令日志 ——

  Widget _buildLogSection() {
    return _Section(
      title: '指令日志',
      trailing: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: _clearLogs,
        child: const Text('清空',
            style: TextStyle(fontSize: 14, decoration: TextDecoration.none)),
      ),
      child: Container(
        height: 200,
        decoration: BoxDecoration(
          color: const Color(0xFF000000),
          borderRadius: BorderRadius.circular(8),
        ),
        child: _logs.isEmpty
            ? const Center(
                child: Text('暂无日志',
                    style: TextStyle(
                        color: AppTheme.tertiaryLabel,
                        fontSize: 13,
                        decoration: TextDecoration.none)))
            : ListView.builder(
                controller: _logScrollCtrl,
                padding: const EdgeInsets.all(8),
                itemCount: _logs.length,
                itemBuilder: (context, index) {
                  final log = _logs[index];
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 1),
                    child: Text.rich(
                      TextSpan(children: [
                        TextSpan(
                          text: log.timeStr,
                          style: const TextStyle(
                              color: AppTheme.tertiaryLabel,
                              fontSize: 11,
                              decoration: TextDecoration.none),
                        ),
                        const TextSpan(text: ' '),
                        TextSpan(
                          text: log.direction,
                          style: TextStyle(
                            color: log.direction == 'TX'
                                ? AppTheme.accent
                                : log.direction == 'RX'
                                    ? AppTheme.accentGreen
                                    : AppTheme.accentOrange,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            decoration: TextDecoration.none,
                          ),
                        ),
                        const TextSpan(text: ' '),
                        TextSpan(
                          text: log.message,
                          style: const TextStyle(
                              color: AppTheme.secondaryLabel,
                              fontSize: 11,
                              decoration: TextDecoration.none),
                        ),
                      ]),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  );
                },
              ),
      ),
    );
  }

  void _showInfo(BuildContext context) {
    showCupertinoDialog(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('底座控制调试',
            style: TextStyle(decoration: TextDecoration.none)),
        content: const Text(
          '此页面通过经典蓝牙 SPP 与开发板底座通讯。\n\n'
          '1. 先在系统蓝牙设置配对底座 XBot-Base（PIN 1234）\n'
          '2. 点「选择设备连接」建立 SPP 通道\n'
          '3. 发送指令，指令与回包会记录到日志\n\n'
          '帧格式：单行 JSON + 换行符。',
          style: TextStyle(decoration: TextDecoration.none),
        ),
        actions: [
          CupertinoDialogAction(
            child: const Text('确定',
                style: TextStyle(decoration: TextDecoration.none)),
            onPressed: () => Navigator.pop(context),
          ),
        ],
      ),
    );
  }
}

// ==================== 辅助组件 ====================

class _Section extends StatelessWidget {
  const _Section({
    required this.title,
    required this.child,
    this.trailing,
  });

  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                title,
                style: const TextStyle(
                  color: AppTheme.secondaryLabel,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  decoration: TextDecoration.none,
                ),
              ),
              const Spacer(),
              ?trailing,
            ],
          ),
          const SizedBox(height: 8),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.groupedBackground,
              borderRadius: BorderRadius.circular(12),
            ),
            child: child,
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({
    required this.label,
    required this.value,
    this.valueColor,
  });

  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 80,
            child: Text(label,
                style: const TextStyle(
                    color: AppTheme.tertiaryLabel,
                    fontSize: 14,
                    decoration: TextDecoration.none)),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                color: valueColor ?? AppTheme.label,
                fontSize: 14,
                decoration: TextDecoration.none,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AngleSlider extends StatelessWidget {
  const _AngleSlider({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    this.onChanged,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double>? onChanged;

  @override
  Widget build(BuildContext context) {
    final enabled = onChanged != null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 80,
            child: Text(
              label,
              style: TextStyle(
                color: enabled
                    ? AppTheme.secondaryLabel
                    : AppTheme.tertiaryLabel,
                fontSize: 13,
                decoration: TextDecoration.none,
              ),
            ),
          ),
          Expanded(
            child: CupertinoSlider(
              value: value.clamp(min, max),
              min: min,
              max: max,
              onChanged: onChanged,
            ),
          ),
          SizedBox(
            width: 55,
            child: Text(
              '${value.toStringAsFixed(1)}°',
              textAlign: TextAlign.right,
              style: TextStyle(
                color: enabled ? AppTheme.label : AppTheme.tertiaryLabel,
                fontSize: 13,
                decoration: TextDecoration.none,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CmdButton extends StatelessWidget {
  const _CmdButton({
    required this.label,
    required this.enabled,
    required this.onPressed,
  });

  final String label;
  final bool enabled;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return CupertinoButton(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      color: AppTheme.background,
      borderRadius: BorderRadius.circular(8),
      onPressed: enabled ? onPressed : null,
      child: Text(
        label,
        style: TextStyle(
          color: enabled ? AppTheme.label : AppTheme.tertiaryLabel,
          fontSize: 13,
          decoration: TextDecoration.none,
        ),
      ),
    );
  }
}

// ==================== 数据模型 ====================

class _LogEntry {
  _LogEntry({
    required this.time,
    required this.direction,
    required this.message,
  });

  final DateTime time;
  final String direction; // TX / RX / SYS / ERR
  final String message;

  String get timeStr {
    return '${time.hour.toString().padLeft(2, '0')}:'
        '${time.minute.toString().padLeft(2, '0')}:'
        '${time.second.toString().padLeft(2, '0')}';
  }
}
