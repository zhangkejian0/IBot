import 'package:flutter/cupertino.dart';

import '../../services/persona_logger.dart';
import '../../theme/app_theme.dart';

/// 人物日志本地查看页：按天浏览本机持久化的识别/对话记录。
///
/// 顶部可切换日期(从已有日志文件读取),列表倒序展示(最新在上),
/// 每条按类型着色,展示人物/表情/手势/物体/场景/对话/分析等可用字段。
class PersonaLogScreen extends StatefulWidget {
  const PersonaLogScreen({super.key, required this.logger});

  final PersonaLogger logger;

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
      default:
        return type;
    }
  }

  String _timeStr(DateTime t) =>
      '${t.hour.toString().padLeft(2, '0')}:'
      '${t.minute.toString().padLeft(2, '0')}:'
      '${t.second.toString().padLeft(2, '0')}';

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
        trailing: GestureDetector(
          onTap: _loadEntries,
          child: const Icon(CupertinoIcons.refresh,
              color: AppTheme.accent, size: 20),
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
}
