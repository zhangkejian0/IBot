import 'dart:convert';

import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart' show Clipboard, ClipboardData;

import '../../core/app_controller.dart';
import '../../models/detection.dart';
import '../../services/persona_logger.dart';
import '../../theme/app_theme.dart';

/// 人物日志本地查看页：按天浏览本机持久化的识别/对话记录。
///
/// 顶部可切换日期(从已有日志文件读取),列表倒序展示(最新在上),
/// 每条按类型着色,展示人物/表情/手势/物体/场景/对话/分析等可用字段。
///
/// 导航栏额外提供「点位采样」按钮(调试用):把当前帧的全部坐标连同用户
/// 标注的动作名存成 type='sample' 记录,用于精调行为识别阈值。
class PersonaLogScreen extends StatefulWidget {
  const PersonaLogScreen({
    super.key,
    required this.logger,
    required this.controller,
  });

  final PersonaLogger logger;

  /// 应用控制器:取当前帧的检测结果用于点位采样。
  final AppController controller;

  @override
  State<PersonaLogScreen> createState() => _PersonaLogScreenState();
}

class _PersonaLogScreenState extends State<PersonaLogScreen> {
  List<String> _dates = [];
  String? _selected;
  List<PersonaLogEntry> _entries = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadDates();
  }

  Future<void> _loadDates() async {
    final dates = await widget.logger.availableDates();
    if (!mounted) return;
    setState(() {
      _dates = dates;
      _selected = dates.isNotEmpty ? dates.first : null;
    });
    await _loadEntries();
  }

  Future<void> _loadEntries() async {
    final date = _selected;
    if (date == null) {
      setState(() {
        _entries = [];
        _loading = false;
      });
      return;
    }
    setState(() => _loading = true);
    final entries = await widget.logger.readDate(date);
    if (!mounted) return;
    setState(() {
      _entries = entries;
      _loading = false;
    });
  }

  void _pickDate() {
    if (_dates.isEmpty) return;
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoActionSheet(
        title: const Text('选择日期'),
        actions: _dates
            .map((d) => CupertinoActionSheetAction(
                  onPressed: () {
                    Navigator.of(ctx).pop();
                    if (d != _selected) {
                      setState(() => _selected = d);
                      _loadEntries();
                    }
                  },
                  child: Text(d,
                      style: TextStyle(
                          color: d == _selected
                              ? AppTheme.accent
                              : AppTheme.label)),
                ))
            .toList(),
        cancelButton: CupertinoActionSheetAction(
          isDefaultAction: true,
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('取消'),
        ),
      ),
    );
  }

  Color _typeColor(String type) {
    switch (type) {
      case 'perception':
        return AppTheme.accentTeal;
      case 'conversation':
        return AppTheme.accentGreen;
      case 'event':
        return AppTheme.accentOrange;
      case 'state':
        return AppTheme.accentRed;
      case 'activity':
        return AppTheme.accentPurple;
      case 'sample':
        return AppTheme.accentTeal;
      default:
        return AppTheme.secondaryLabel;
    }
  }

  String _typeLabel(String type) {
    switch (type) {
      case 'perception':
        return '感知';
      case 'conversation':
        return '对话';
      case 'event':
        return '事件';
      case 'state':
        return '状态';
      case 'activity':
        return '活动';
      case 'sample':
        return '采样';
      default:
        return type;
    }
  }

  String _timeStr(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:'
      '${t.minute.toString().padLeft(2, '0')}:'
      '${t.second.toString().padLeft(2, '0')}';

  /// 点位采样:取当前帧的全部坐标,让用户标注动作名,存成 type='sample'。
  /// 用于精调行为识别阈值——采样多个动作后,在日志/HTTP 看板查看或导出分析。
  Future<void> _captureSample() async {
    final result = widget.controller.result;
    // 空数据保护:检测未运行或当前无任何识别结果。
    if (result.faces.isEmpty &&
        result.hands.isEmpty &&
        result.poses.isEmpty &&
        result.objects.isEmpty) {
      showCupertinoDialog<void>(
        context: context,
        builder: (_) => CupertinoAlertDialog(
          title: const Text('当前无检测数据'),
          content: const Text('请确认摄像头检测正在运行(如切到调试画面),'
              '并做出要采样的动作后再点采样。'),
          actions: [
            CupertinoDialogAction(
              isDefaultAction: true,
              child: const Text('好的'),
              onPressed: () => Navigator.of(context).pop(),
            ),
          ],
        ),
      );
      return;
    }

    // 输入动作名(默认填"托腮",便于快速采样常见调试动作)。
    final controller = TextEditingController(text: '托腮');
    final action = await showCupertinoDialog<String>(
      context: context,
      builder: (_) => CupertinoAlertDialog(
        title: const Text('标注当前动作'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: CupertinoTextField(
            controller: controller,
            autofocus: true,
            placeholder: '如:托腮 / 举手 / 喝水…',
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDestructiveAction: true,
            child: const Text('取消'),
            onPressed: () => Navigator.of(context).pop(),
          ),
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('采样'),
            onPressed: () =>
                Navigator.of(context).pop(controller.text.trim()),
          ),
        ],
      ),
    );
    if (action == null || action.isEmpty) return;

    // 序列化当前帧的全部点位数据。
    final extra = _serializeFrame(result);
    extra['action'] = action;
    widget.logger.log(PersonaLogEntry(
      timestamp: DateTime.now(),
      type: 'sample',
      faceCount: result.faces.length,
      note: '采样: $action',
      extra: extra,
    ));

    // 复制一份到剪贴板,方便即时粘贴分析;同时提示已存入日志。
    final json = const JsonEncoder.withIndent('  ').convert(extra);
    await Clipboard.setData(ClipboardData(text: json));
    if (!mounted) return;
    await _loadEntries(); // 刷新列表,让采样记录立即可见(最新在上)
    if (!mounted) return;
    showCupertinoDialog<void>(
      context: context,
      builder: (_) => CupertinoAlertDialog(
        title: const Text('已采样'),
        content: Text('动作「$action」的点位已存入日志并复制到剪贴板。\n'
            'faces=${result.faces.length} hands=${result.hands.length} '
            'poses=${result.poses.length} objects=${result.objects.length}'),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('好的'),
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
    );
  }

  /// 把一帧 DetectionResult 的全部坐标序列化成 extra Map。
  Map<String, dynamic> _serializeFrame(DetectionResult result) {
    final face = result.face;
    return {
      'mirror': result.mirror,
      'face': face == null
          ? null
          : {
              'box': _rectToList(face.boundingBox),
              'gazeX': _round(face.gazeX),
              'gazeY': _round(face.gazeY),
              'eyeBlink': _round(face.eyeBlink),
              'mouthOpenness': _round(face.mouthOpenness),
            },
      'hands': result.hands
          .map((h) => {
                'handedness': h.handedness?.name,
                'box': _rectToList(h.boundingBox),
                'landmarks': h.landmarks.map(_offsetToList).toList(),
              })
          .toList(),
      'objects': result.objects
          .map((o) => {
                'label': o.label,
                'confidence': _round(o.confidence),
                'box': _rectToList(o.boundingBox),
                'heldByHand': o.heldByHand,
              })
          .toList(),
      'poses': result.poses
          .map((p) => {
                'landmarks': p.landmarks.map(_offsetToList).toList(),
                'visibilities':
                    p.visibilities.map((v) => _round(v)).toList(),
              })
          .toList(),
    };
  }

  List<double> _offsetToList(Offset o) => [_round(o.dx), _round(o.dy)];

  List<double> _rectToList(Rect r) =>
      [_round(r.left), _round(r.top), _round(r.right), _round(r.bottom)];

  double _round(double v) => (v * 1000).roundToDouble() / 1000;

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: AppTheme.background,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xF01C1C1E),
        middle: GestureDetector(
          onTap: _pickDate,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_selected ?? '人物日志',
                  style: const TextStyle(color: AppTheme.label)),
              if (_dates.isNotEmpty) ...[
                const SizedBox(width: 4),
                const Icon(CupertinoIcons.chevron_down,
                    size: 14, color: AppTheme.secondaryLabel),
              ],
            ],
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 点位采样按钮(调试用):把当前帧坐标+动作标注存成采样记录。
            GestureDetector(
              onTap: _captureSample,
              child: const Padding(
                padding: EdgeInsets.only(left: 8),
                child: Icon(CupertinoIcons.antenna_radiowaves_left_right,
                    color: AppTheme.accentTeal, size: 20),
              ),
            ),
            GestureDetector(
              onTap: _loadEntries,
              child: const Padding(
                padding: EdgeInsets.only(left: 12),
                child: Icon(CupertinoIcons.refresh,
                    color: AppTheme.accent, size: 20),
              ),
            ),
          ],
        ),
      ),
      child: SafeArea(
        child: _loading
            ? const Center(child: CupertinoActivityIndicator())
            : _entries.isEmpty
                ? const Center(
                    child: Text('暂无记录',
                        style: TextStyle(color: AppTheme.secondaryLabel)),
                  )
                : ListView.builder(
                    reverse: true,
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    itemCount: _entries.length,
                    itemBuilder: (context, i) {
                      final e = _entries[_entries.length - 1 - i];
                      return _LogCard(
                        entry: e,
                        typeColor: _typeColor(e.type),
                        typeLabel: _typeLabel(e.type),
                        time: _timeStr(e.timestamp),
                      );
                    },
                  ),
      ),
    );
  }
}

class _LogCard extends StatelessWidget {
  const _LogCard({
    required this.entry,
    required this.typeColor,
    required this.typeLabel,
    required this.time,
  });

  final PersonaLogEntry entry;
  final Color typeColor;
  final String typeLabel;
  final String time;

  @override
  Widget build(BuildContext context) {
    final e = entry;
    final headerBits = <String>[];
    // 优先显示所有识别到的人物（多人脸场景）。
    if (e.persons.length > 1) {
      headerBits.add('${e.persons.join("、")}（${e.persons.length}人）');
    } else if (e.person != null) {
      headerBits.add(e.relation != null
          ? '${e.person}（${e.relation}）'
          : e.person!);
    } else if (e.faceCount != null && e.faceCount! > 0) {
      headerBits.add('${e.faceCount} 张脸');
    }
    if (e.expression != null) headerBits.add(e.expression!);
    if (e.gesture != null) headerBits.add(e.gesture!);

    // 采样记录:头部显示标注的动作名。
    final isSample = e.type == 'sample';
    if (isSample) {
      final action = e.extra['action']?.toString() ?? '?';
      headerBits.insert(0, '🎬 $action');
    }

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppTheme.groupedBackground,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: typeColor.withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(typeLabel,
                    style: TextStyle(
                        color: typeColor,
                        fontSize: 11,
                        fontWeight: FontWeight.w600)),
              ),
              const SizedBox(width: 8),
              Text(time,
                  style: const TextStyle(
                      color: AppTheme.tertiaryLabel, fontSize: 11)),
              const Spacer(),
              if (headerBits.isNotEmpty)
                Flexible(
                  child: Text(headerBits.join(' · '),
                      textAlign: TextAlign.right,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          color: AppTheme.label, fontSize: 12)),
                ),
            ],
          ),
          if (e.objects.isNotEmpty) ...[
            const SizedBox(height: 6),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: e.objects
                  .map((o) {
                    final name = o['name'] as String? ?? '';
                    final conf = ((o['confidence'] as double? ?? 0) * 100).round();
                    return Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 7, vertical: 1),
                      decoration: BoxDecoration(
                        color: AppTheme.background,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text('$name ($conf%)',
                          style: const TextStyle(
                              color: AppTheme.secondaryLabel, fontSize: 12)),
                    );
                  })
                  .toList(),
            ),
          ],
          if (e.heldObject != null) ...[
            const SizedBox(height: 4),
            Text('✋ 手持: ${e.heldObject}',
                style: const TextStyle(
                    color: AppTheme.accentOrange, fontSize: 12)),
          ],
          if (e.userText != null) ...[
            const SizedBox(height: 4),
            Text('🗣 ${e.userText}',
                style: const TextStyle(color: AppTheme.label, fontSize: 13)),
          ],
          if (e.replyText != null) ...[
            const SizedBox(height: 4),
            Text('🤖 ${e.replyText}',
                style: const TextStyle(
                    color: AppTheme.accentGreen, fontSize: 13)),
          ],
          if (e.robotState != null) ...[
            const SizedBox(height: 4),
            Text('状态: ${e.robotState}',
                style: const TextStyle(
                    color: AppTheme.tertiaryLabel, fontSize: 11)),
          ],
          if (e.scene != null && e.userText == null) ...[
            const SizedBox(height: 4),
            Text(e.scene!,
                style: const TextStyle(
                    color: AppTheme.tertiaryLabel, fontSize: 12)),
          ],
          if (isSample) ...[
            const SizedBox(height: 4),
            Text(
              '脸 ${_sampleCount(e, 'face')} · '
              '手 ${_sampleHandCount(e)} · '
              '姿态 ${_sampleCount(e, 'poses')} · '
              '物体 ${_sampleObjectCount(e)}',
              style: const TextStyle(
                  color: AppTheme.accentTeal, fontSize: 11),
            ),
          ],
          if (e.note != null) ...[
            const SizedBox(height: 4),
            Text('💡 ${e.note}',
                style: const TextStyle(
                    color: AppTheme.accentYellow, fontSize: 12)),
          ],
        ],
      ),
    );
  }

  // —— 采样记录的点位统计辅助 ——
  static int _sampleCount(PersonaLogEntry e, String key) {
    final v = e.extra[key];
    if (v == null) return 0;
    if (v is List) return v.length;
    return 1; // face 是单个对象
  }

  static int _sampleHandCount(PersonaLogEntry e) =>
      (e.extra['hands'] as List?)?.length ?? 0;
  static int _sampleObjectCount(PersonaLogEntry e) =>
      (e.extra['objects'] as List?)?.length ?? 0;
}
