import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// 一条人物行为日志记录。用于持久化分析「谁、在什么时间、在做什么、
/// 周围有什么物体、说了什么、机器人如何响应」等有价值的陪伴上下文。
///
/// 字段刻意做成宽松可空 + [extra] 自由扩展：不同来源(感知帧/语音对话/
/// 系统事件)只填各自相关的字段，未来新增维度也无需改表结构。
class PersonaLogEntry {
  PersonaLogEntry({
    required this.timestamp,
    required this.type,
    this.person,
    this.persons = const [],
    this.relation,
    this.expression,
    this.gesture,
    this.objects = const [],
    this.heldObject,
    this.scene,
    this.faceCount,
    this.userText,
    this.replyText,
    this.robotState,
    this.note,
    Map<String, dynamic>? extra,
  }) : extra = extra ?? const {};

  /// 记录时刻(本地时间)。
  final DateTime timestamp;

  /// 记录类型：perception(感知) / conversation(对话) / event(系统事件)。
  final String type;

  /// 识别到的主要人物姓名(身份识别命中，主脸)。
  final String? person;

  /// 画面中所有识别到的人物姓名列表(多人脸场景)。
  /// 包含主脸和其他被识别出的人物，按人脸面积降序排列。
  final List<String> persons;

  /// 该人物与主人的关系标签(如「主人」「朋友」)。
  final String? relation;

  /// 主脸表情(中文标签，如「高兴」)。
  final String? expression;

  /// 手势(中文标签，如「点赞」)。
  final String? gesture;

  /// 画面中识别到的物体及其置信度(去重，按名称保留最高置信度)。
  /// 每个元素结构：{'name': '杯子', 'confidence': 0.85}
  final List<Map<String, dynamic>> objects;

  /// 被判定为「手持」的物体名。
  final String? heldObject;

  /// 端侧拼装的自然语言场景描述。
  final String? scene;

  /// 当前画面中的人脸数量。
  final int? faceCount;

  /// 对话：用户说的话(STT 文本)。
  final String? userText;

  /// 对话：机器人回复文本。
  final String? replyText;

  /// 机器人响应的动作/表情状态(FSM state)。
  final String? robotState;

  /// 分析得到的、值得记录的额外信息(自由文本)。
  final String? note;

  /// 自由扩展字段，随写随读，便于后续追加新维度而不破坏既有数据。
  final Map<String, dynamic> extra;

  Map<String, dynamic> toJson() {
    final map = <String, dynamic>{
      'ts': timestamp.toIso8601String(),
      'type': type,
    };
    if (person != null) map['person'] = person;
    if (persons.isNotEmpty) map['persons'] = persons;
    if (relation != null) map['relation'] = relation;
    if (expression != null) map['expression'] = expression;
    if (gesture != null) map['gesture'] = gesture;
    if (objects.isNotEmpty) map['objects'] = objects.map((o) => {'name': o['name'], 'confidence': o['confidence']}).toList();
    if (heldObject != null) map['heldObject'] = heldObject;
    if (scene != null) map['scene'] = scene;
    if (faceCount != null) map['faceCount'] = faceCount;
    if (userText != null) map['userText'] = userText;
    if (replyText != null) map['replyText'] = replyText;
    if (robotState != null) map['robotState'] = robotState;
    if (note != null) map['note'] = note;
    if (extra.isNotEmpty) map['extra'] = extra;
    return map;
  }

  factory PersonaLogEntry.fromJson(Map<String, dynamic> json) {
    return PersonaLogEntry(
      timestamp:
          DateTime.tryParse(json['ts'] as String? ?? '') ?? DateTime.now(),
      type: json['type'] as String? ?? 'event',
      person: json['person'] as String?,
      persons: (json['persons'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      relation: json['relation'] as String?,
      expression: json['expression'] as String?,
      gesture: json['gesture'] as String?,
      objects: (json['objects'] as List<dynamic>? ?? const [])
          .map((e) => e is Map<String, dynamic>
              ? {'name': e['name']?.toString() ?? '', 'confidence': (e['confidence'] as num?)?.toDouble() ?? 0.0}
              : {'name': e.toString(), 'confidence': 0.0})
          .toList(),
      heldObject: json['heldObject'] as String?,
      scene: json['scene'] as String?,
      faceCount: (json['faceCount'] as num?)?.toInt(),
      userText: json['userText'] as String?,
      replyText: json['replyText'] as String?,
      robotState: json['robotState'] as String?,
      note: json['note'] as String?,
      extra: (json['extra'] as Map?)?.cast<String, dynamic>() ?? const {},
    );
  }
}

/// 按天持久化的人物行为日志记录器。
///
/// 每天一个文件 `persona_logs/YYYY-MM-DD.jsonl`(JSON Lines：一行一条记录)，
/// 追加写入。选择 JSONL 而非单个大 JSON：可流式追加、单行损坏不影响整文件、
/// 便于按行读取与外部工具(jq/grep)分析。
///
/// 写入串行化(单条 Future 链)，避免并发 append 交错；内存保留当天最近若干条
/// 以便设置页快速预览。对外是 [ChangeNotifier]，本地查看页可订阅实时刷新。
class PersonaLogger extends ChangeNotifier {
  PersonaLogger({this.memoryCapacity = 300});

  /// 内存中保留的最近记录条数(仅用于 App 内预览，不影响磁盘完整记录)。
  final int memoryCapacity;

  Directory? _dir;
  bool _initialized = false;

  /// 写入串行化队列：保证 append 顺序且不交错。
  Future<void> _writeChain = Future.value();

  /// 当天(最近)若干条记录的内存缓存，供 App 内预览。
  final List<PersonaLogEntry> _recent = [];
  List<PersonaLogEntry> get recent => List.unmodifiable(_recent);

  /// 是否启用记录。关闭时 [log] 直接丢弃(不落盘、不通知)。
  bool enabled = true;

  /// 日志根目录(初始化后可用)，用于设置页显示存储位置。
  String? get directoryPath => _dir?.path;

  /// 初始化：定位/创建日志目录，预载当天已有记录到内存缓存。幂等。
  Future<void> initialize() async {
    if (_initialized) return;
    try {
      final docs = await getApplicationDocumentsDirectory();
      final dir = Directory('${docs.path}/persona_logs');
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      _dir = dir;
      _initialized = true;
      // 预载当天记录(尾部 memoryCapacity 条)到内存缓存。
      final today = _dateKey(DateTime.now());
      final entries = await readDate(today);
      _recent
        ..clear()
        ..addAll(entries.length > memoryCapacity
            ? entries.sublist(entries.length - memoryCapacity)
            : entries);
      debugPrint('[PersonaLogger] dir: ${dir.path} (today ${entries.length})');
      notifyListeners();
    } catch (e) {
      debugPrint('[PersonaLogger] init failed: $e');
    }
  }

  /// 追加一条记录：写入当天文件并更新内存缓存。
  /// 未启用或未初始化时静默丢弃。
  void log(PersonaLogEntry entry) {
    if (!enabled || !_initialized) return;
    _recent.add(entry);
    while (_recent.length > memoryCapacity) {
      _recent.removeAt(0);
    }
    notifyListeners();
    final line = '${jsonEncode(entry.toJson())}\n';
    final key = _dateKey(entry.timestamp);
    // 串行追加，避免并发写交错。
    _writeChain = _writeChain.then((_) async {
      try {
        final file = File('${_dir!.path}/$key.jsonl');
        await file.writeAsString(line, mode: FileMode.append, flush: false);
      } catch (e) {
        debugPrint('[PersonaLogger] write failed: $e');
      }
    });
  }

  /// 列出已有日志的日期(YYYY-MM-DD)，按时间倒序(最近在前)。
  Future<List<String>> availableDates() async {
    final dir = _dir;
    if (dir == null) return const [];
    try {
      final files = await dir
          .list()
          .where((e) => e is File && e.path.endsWith('.jsonl'))
          .toList();
      final dates = files
          .map((e) => e.uri.pathSegments.last.replaceAll('.jsonl', ''))
          .toList()
        ..sort((a, b) => b.compareTo(a));
      return dates;
    } catch (e) {
      debugPrint('[PersonaLogger] list dates failed: $e');
      return const [];
    }
  }

  /// 读取某一天的全部记录(按时间顺序，最旧在前)。
  Future<List<PersonaLogEntry>> readDate(String dateKey) async {
    final dir = _dir;
    if (dir == null) return const [];
    final file = File('${dir.path}/$dateKey.jsonl');
    if (!await file.exists()) return const [];
    try {
      final lines = await file.readAsLines();
      final out = <PersonaLogEntry>[];
      for (final line in lines) {
        final trimmed = line.trim();
        if (trimmed.isEmpty) continue;
        try {
          final json = jsonDecode(trimmed) as Map<String, dynamic>;
          out.add(PersonaLogEntry.fromJson(json));
        } catch (_) {
          // 单行损坏不影响其余记录。
        }
      }
      return out;
    } catch (e) {
      debugPrint('[PersonaLogger] read $dateKey failed: $e');
      return const [];
    }
  }

  /// 读取某一天的原始 JSONL 文本(供 HTTP 服务直接透传，省一次序列化)。
  Future<String> readDateRaw(String dateKey) async {
    final dir = _dir;
    if (dir == null) return '';
    final file = File('${dir.path}/$dateKey.jsonl');
    if (!await file.exists()) return '';
    try {
      return await file.readAsString();
    } catch (e) {
      debugPrint('[PersonaLogger] readRaw $dateKey failed: $e');
      return '';
    }
  }

  /// 删除某一天的日志文件。
  Future<void> deleteDate(String dateKey) async {
    final dir = _dir;
    if (dir == null) return;
    try {
      final file = File('${dir.path}/$dateKey.jsonl');
      if (await file.exists()) await file.delete();
      if (dateKey == _dateKey(DateTime.now())) {
        _recent.clear();
        notifyListeners();
      }
    } catch (e) {
      debugPrint('[PersonaLogger] delete $dateKey failed: $e');
    }
  }

  /// 本地日期键 YYYY-MM-DD(用本地时区，便于按「自然天」归档)。
  static String _dateKey(DateTime dt) {
    final y = dt.year.toString().padLeft(4, '0');
    final m = dt.month.toString().padLeft(2, '0');
    final d = dt.day.toString().padLeft(2, '0');
    return '$y-$m-$d';
  }
}
