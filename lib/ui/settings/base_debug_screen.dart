import 'dart:convert';

import 'package:flutter/cupertino.dart';

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../theme/app_theme.dart';

/// 底座控制调试页面
///
/// 提供完整的底座调试功能：
/// - 连接状态显示
/// - 手动角度控制（Yaw/Pitch/Roll 滑杆）
/// - 测试命令按钮
/// - 指令日志列表
///
/// 首版使用 Mock 实现，不实际通讯。
class BaseDebugScreen extends StatefulWidget {
  const BaseDebugScreen({super.key});

  @override
  State<BaseDebugScreen> createState() => _BaseDebugScreenState();
}

class _BaseDebugScreenState extends State<BaseDebugScreen> {
  // —— 连接状态（Mock）——
  bool _connected = false;
  int _dof = 2;
  String _mode = 'idle';
  double _currentYaw = 0;
  double _currentPitch = 0;
  double _currentRoll = 0;

  // —— 手动控制 ——
  double _targetYaw = 0;
  double _targetPitch = 0;
  double _targetRoll = 0;

  // —— 指令日志 ——
  final List<_LogEntry> _logs = [];
  final ScrollController _logScrollCtrl = ScrollController();

  @override
  void dispose() {
    _logScrollCtrl.dispose();
    super.dispose();
  }

  // ==================== 指令发送（Mock） ====================

  void _sendCommand(String cmd, Map<String, dynamic> params) {
    final json = {'cmd': cmd, ...params};
    _addLog('TX', jsonEncode(json));

    // Mock 响应
    Future.delayed(const Duration(milliseconds: 50), () {
      _handleMockResponse(cmd, params);
    });
  }

  void _handleMockResponse(String cmd, Map<String, dynamic> params) {
    switch (cmd) {
      case 'move':
        setState(() {
          _currentYaw = (params['yaw'] as num).toDouble();
          _currentPitch = (params['pitch'] as num).toDouble();
          _currentRoll = (params['roll'] as num?)?.toDouble() ?? 0;
        });
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'move_rel':
        setState(() {
          _currentYaw += (params['dyaw'] as num).toDouble();
          _currentPitch += (params['dpitch'] as num).toDouble();
          _currentRoll += (params['droll'] as num?)?.toDouble() ?? 0;
        });
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'home':
        setState(() {
          _currentYaw = 0;
          _currentPitch = 0;
          _currentRoll = 0;
          _targetYaw = 0;
          _targetPitch = 0;
          _targetRoll = 0;
        });
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'stop':
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'set_mode':
        setState(() {
          _mode = params['mode'] as String;
        });
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'set_speed':
        _addLog('RX', '{"cmd":"ack","ok":true}');
        break;
      case 'get_status':
        _addLog('RX',
            '{"cmd":"status","mode":"$_mode","yaw":$_currentYaw,"pitch":$_currentPitch,"roll":$_currentRoll,"dof":$_dof,"moving":false,"calibrated":true}');
        break;
      case 'get_version':
        _addLog('RX',
            '{"cmd":"version","major":1,"minor":0,"patch":0,"build":1,"dof":$_dof,"hw":"XBot-Base-Mock"}');
        break;
      default:
        _addLog('RX', '{"cmd":"ack","ok":true}');
    }
  }

  void _addLog(String direction, String message) {
    setState(() {
      _logs.add(_LogEntry(
        time: DateTime.now(),
        direction: direction,
        message: message,
      ));
      // 保留最近 100 条
      if (_logs.length > 100) {
        _logs.removeAt(0);
      }
    });
    // 滚动到底部
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScrollCtrl.hasClients) {
        _logScrollCtrl.jumpTo(_logScrollCtrl.position.maxScrollExtent);
      }
    });
  }

  void _clearLogs() {
    setState(() {
      _logs.clear();
    });
  }

  // ==================== UI 构建 ====================

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);

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
                _buildManualControlSection(),
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

  // —— 人脸检测位置 ——

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
            Container(
              height: 1,
              color: AppTheme.separator,
              margin: const EdgeInsets.symmetric(vertical: 8),
            ),
            _InfoRow(
              label: '主脸位置',
              value: '(${face.boundingBox.left.toStringAsFixed(2)}, '
                  '${face.boundingBox.top.toStringAsFixed(2)})',
            ),
            _InfoRow(
              label: '主脸大小',
              value: '${face.boundingBox.width.toStringAsFixed(2)} × '
                  '${face.boundingBox.height.toStringAsFixed(2)}',
            ),
            _InfoRow(
              label: '中心点',
              value:
                  '(${(face.boundingBox.left + face.boundingBox.width / 2).toStringAsFixed(2)}, '
                  '${(face.boundingBox.top + face.boundingBox.height / 2).toStringAsFixed(2)})',
            ),
            Container(
              height: 1,
              color: AppTheme.separator,
              margin: const EdgeInsets.symmetric(vertical: 8),
            ),
            _InfoRow(
              label: '注视 X',
              value: face.gazeX.toStringAsFixed(3),
              valueColor: _gazeColor(face.gazeX),
            ),
            _InfoRow(
              label: '注视 Y',
              value: face.gazeY.toStringAsFixed(3),
              valueColor: _gazeColor(face.gazeY),
            ),
            Container(
              height: 1,
              color: AppTheme.separator,
              margin: const EdgeInsets.symmetric(vertical: 8),
            ),
            _InfoRow(
              label: '映射 Yaw',
              value: '${(face.gazeX * 90).toStringAsFixed(1)}°',
              valueColor: AppTheme.accentTeal,
            ),
            _InfoRow(
              label: '映射 Pitch',
              value: '${(face.gazeY * 30).toStringAsFixed(1)}°',
              valueColor: AppTheme.accentTeal,
            ),
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
    return _Section(
      title: '连接状态',
      child: Column(
        children: [
          _InfoRow(
            label: '设备',
            value: _connected ? 'Mock 底座 (模拟)' : '未连接',
            valueColor:
                _connected ? AppTheme.accentGreen : AppTheme.secondaryLabel,
          ),
          _InfoRow(
            label: '自由度',
            value: _connected ? '$_dof DOF' : '--',
          ),
          _InfoRow(
            label: '当前模式',
            value: _connected ? _mode.toUpperCase() : '--',
          ),
          _InfoRow(
            label: '当前角度',
            value: _connected
                ? 'Y:${_currentYaw.toStringAsFixed(1)}° '
                    'P:${_currentPitch.toStringAsFixed(1)}° '
                    'R:${_currentRoll.toStringAsFixed(1)}°'
                : '--',
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: CupertinoButton.filled(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  onPressed: _connected
                      ? null
                      : () {
                          setState(() {
                            _connected = true;
                            _dof = 3;
                          });
                          _addLog('SYS', '已连接到 Mock 底座 (3 DOF)');
                        },
                  child: const Text('搜索设备',
                      style: TextStyle(decoration: TextDecoration.none)),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: CupertinoButton(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  color: AppTheme.accentRed,
                  onPressed: _connected
                      ? () {
                          setState(() {
                            _connected = false;
                            _mode = 'idle';
                          });
                          _addLog('SYS', '已断开连接');
                        }
                      : null,
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

  // —— 手动控制 ——

  Widget _buildManualControlSection() {
    return _Section(
      title: '手动控制',
      child: Column(
        children: [
          _AngleSlider(
            label: 'Yaw (水平)',
            value: _targetYaw,
            min: -90,
            max: 90,
            onChanged: _connected
                ? (v) => setState(() => _targetYaw = v)
                : null,
          ),
          _AngleSlider(
            label: 'Pitch (垂直)',
            value: _targetPitch,
            min: -30,
            max: 30,
            onChanged: _connected
                ? (v) => setState(() => _targetPitch = v)
                : null,
          ),
          _AngleSlider(
            label: 'Roll (横滚)',
            value: _targetRoll,
            min: -45,
            max: 45,
            onChanged: _connected && _dof >= 3
                ? (v) => setState(() => _targetRoll = v)
                : null,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: CupertinoButton.filled(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  onPressed: _connected
                      ? () => _sendCommand('move', {
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
                  onPressed: _connected
                      ? () {
                          _sendCommand('home', {});
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
                  onPressed: _connected
                      ? () => _sendCommand('stop', {})
                      : null,
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
    return _Section(
      title: '测试命令',
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          _CmdButton(
            label: 'IDLE',
            enabled: _connected,
            onPressed: () => _sendCommand('set_mode', {'mode': 'idle'}),
          ),
          _CmdButton(
            label: 'TRACK',
            enabled: _connected,
            onPressed: () => _sendCommand('set_mode', {'mode': 'track'}),
          ),
          _CmdButton(
            label: 'MANUAL',
            enabled: _connected,
            onPressed: () => _sendCommand('set_mode', {'mode': 'manual'}),
          ),
          _CmdButton(
            label: 'SLEEP',
            enabled: _connected,
            onPressed: () => _sendCommand('set_mode', {'mode': 'sleep'}),
          ),
          _CmdButton(
            label: 'DEMO',
            enabled: _connected,
            onPressed: () => _sendCommand('set_mode', {'mode': 'demo'}),
          ),
          _CmdButton(
            label: '速度 50%',
            enabled: _connected,
            onPressed: () => _sendCommand('set_speed', {
              'yaw_speed': 50,
              'pitch_speed': 50,
            }),
          ),
          _CmdButton(
            label: '速度 100%',
            enabled: _connected,
            onPressed: () => _sendCommand('set_speed', {
              'yaw_speed': 100,
              'pitch_speed': 100,
            }),
          ),
          _CmdButton(
            label: '查询状态',
            enabled: _connected,
            onPressed: () => _sendCommand('get_status', {}),
          ),
          _CmdButton(
            label: '查询版本',
            enabled: _connected,
            onPressed: () => _sendCommand('get_version', {}),
          ),
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
          '此页面用于调试底座通讯协议。\n\n'
          '当前为 Mock 模式，不实际连接设备。\n'
          '所有指令仅记录到日志中。\n\n'
          '后续版本将支持 BLE/WiFi 连接。',
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
              if (trailing != null) trailing!,
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
  final String direction; // TX / RX / SYS
  final String message;

  String get timeStr {
    return '${time.hour.toString().padLeft(2, '0')}:'
        '${time.minute.toString().padLeft(2, '0')}:'
        '${time.second.toString().padLeft(2, '0')}';
  }
}
