import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// 一条「与后端的网络交互」日志记录。
///
/// 专门用于调试：清楚记录每次发给后端(主要是 Pophie `/api/chat`、
/// `/api/tts/stream` 等)究竟提交了哪些信息、后端回了什么、耗时多久、是否出错。
///
/// 与 [PersonaLogEntry] 区分：人物日志关注「谁在做什么」，网络日志关注
/// 「客户端↔服务端」的常规 network 细节(method/url/headers/body/status/耗时)。
class NetworkLogEntry {
  NetworkLogEntry({
    required this.timestamp,
    required this.method,
    required this.url,
    this.path,
    this.requestHeaders = const {},
    this.requestBody,
    this.statusCode,
    this.responseHeaders = const {},
    this.responseBody,
    this.durationMs,
    this.requestBytes,
    this.responseBytes,
    this.error,
    this.trigger,
  });

  /// 记录时刻(本地时间)，即请求发起时间。
  final DateTime timestamp;

  /// HTTP 方法(GET/POST/PUT/DELETE…)。
  final String method;

  /// 完整请求 URL。
  final String url;

  /// 仅路径部分(便于网页端按端点过滤，如 `/api/chat`)。
  final String? path;

  /// 请求头(已剔除/折叠超大值)。
  final Map<String, dynamic> requestHeaders;

  /// 请求体(已做大字段截断的可读结构；流式请求为简述)。
  final dynamic requestBody;

  /// 响应状态码。网络层失败(无响应)时为 null。
  final int? statusCode;

  /// 响应头。
  final Map<String, dynamic> responseHeaders;

  /// 响应体(已做大字段截断；流式响应为占位说明)。
  final dynamic responseBody;

  /// 端到端耗时(毫秒)：发起到收到响应/出错。
  final int? durationMs;

  /// 请求体原始大小(字节，估算)。
  final int? requestBytes;

  /// 响应体原始大小(字节，估算)。
  final int? responseBytes;

  /// 错误信息(超时/连接失败/非 2xx 等)。成功时为 null。
  final String? error;

  /// 触发本次请求的语音交互来源标识(由 VoiceAssistant 透传)。
  ///
  /// 端侧主动对话：wake(唤醒词) / double_tap(双击) / gaze(注视) / manual(手动)。
  /// 服务端主动播报：proactive:welcome / proactive:reminder / proactive:living_loop，
  /// 或 proactive:poll(主动消息轮询本身)。与语音交互无关的请求(如健康检查)为 null。
  final String? trigger;

  bool get ok => error == null && statusCode != null && statusCode! < 400;

  Map<String, dynamic> toJson() {
    final map = <String, dynamic>{
      'ts': timestamp.toIso8601String(),
      'method': method,
      'url': url,
    };
    if (path != null) map['path'] = path;
    if (requestHeaders.isNotEmpty) map['requestHeaders'] = requestHeaders;
    if (requestBody != null) map['requestBody'] = requestBody;
    if (statusCode != null) map['statusCode'] = statusCode;
    if (responseHeaders.isNotEmpty) map['responseHeaders'] = responseHeaders;
    if (responseBody != null) map['responseBody'] = responseBody;
    if (durationMs != null) map['durationMs'] = durationMs;
    if (requestBytes != null) map['requestBytes'] = requestBytes;
    if (responseBytes != null) map['responseBytes'] = responseBytes;
    if (error != null) map['error'] = error;
    if (trigger != null) map['trigger'] = trigger;
    return map;
  }

  factory NetworkLogEntry.fromJson(Map<String, dynamic> json) {
    return NetworkLogEntry(
      timestamp:
          DateTime.tryParse(json['ts'] as String? ?? '') ?? DateTime.now(),
      method: json['method'] as String? ?? '',
      url: json['url'] as String? ?? '',
      path: json['path'] as String?,
      requestHeaders:
          (json['requestHeaders'] as Map?)?.cast<String, dynamic>() ?? const {},
      requestBody: json['requestBody'],
      statusCode: (json['statusCode'] as num?)?.toInt(),
      responseHeaders:
          (json['responseHeaders'] as Map?)?.cast<String, dynamic>() ??
              const {},
      responseBody: json['responseBody'],
      durationMs: (json['durationMs'] as num?)?.toInt(),
      requestBytes: (json['requestBytes'] as num?)?.toInt(),
      responseBytes: (json['responseBytes'] as num?)?.toInt(),
      error: json['error'] as String?,
      trigger: json['trigger'] as String?,
    );
  }
}

/// 按天持久化的网络交互日志记录器。
///
/// 设计与 [PersonaLogger] 一致：每天一个 `network_logs/YYYY-MM-DD.jsonl`
/// (JSON Lines)，串行追加写入，永久保存直到手动删除。对外是 [ChangeNotifier]，
/// 本地查看页可订阅实时刷新。
///
/// 落盘前会用 [sanitize] 折叠超长字符串(如 base64 音频)，避免日志被巨量
/// 二进制数据撑爆，同时保留「提交了哪些字段」这一关键信息。
class NetworkLogger extends ChangeNotifier {
  NetworkLogger({this.memoryCapacity = 200, this.maxStringLength = 256});

  /// 内存中保留的最近记录条数(仅用于预览，不影响磁盘完整记录)。
  final int memoryCapacity;

  /// 单个字符串字段保留的最大长度，超出折叠为占位说明(主要针对 base64)。
  final int maxStringLength;

  Directory? _dir;
  bool _initialized = false;

  /// 写入串行化队列：保证 append 顺序且不交错。
  Future<void> _writeChain = Future.value();

  final List<NetworkLogEntry> _recent = [];
  List<NetworkLogEntry> get recent => List.unmodifiable(_recent);

  /// 是否启用记录。关闭时 [log] 直接丢弃(不落盘、不通知)。
  bool enabled = true;

  /// 日志根目录(初始化后可用)。
  String? get directoryPath => _dir?.path;

  /// 初始化：定位/创建日志目录，预载当天已有记录到内存缓存。幂等。
  Future<void> initialize() async {
    if (_initialized) return;
    try {
      final docs = await getApplicationDocumentsDirectory();
      final dir = Directory('${docs.path}/network_logs');
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      _dir = dir;
      _initialized = true;
      final today = _dateKey(DateTime.now());
      final entries = await readDate(today);
      _recent
        ..clear()
        ..addAll(entries.length > memoryCapacity
            ? entries.sublist(entries.length - memoryCapacity)
            : entries);
      debugPrint('[NetworkLogger] dir: ${dir.path} (today ${entries.length})');
      notifyListeners();
    } catch (e) {
      debugPrint('[NetworkLogger] init failed: $e');
    }
  }

  /// 追加一条记录：写入当天文件并更新内存缓存。
  void log(NetworkLogEntry entry) {
    if (!enabled || !_initialized) return;
    _recent.add(entry);
    while (_recent.length > memoryCapacity) {
      _recent.removeAt(0);
    }
    notifyListeners();
    final line = '${jsonEncode(entry.toJson())}\n';
    final key = _dateKey(entry.timestamp);
    _writeChain = _writeChain.then((_) async {
      try {
        final file = File('${_dir!.path}/$key.jsonl');
        await file.writeAsString(line, mode: FileMode.append, flush: false);
      } catch (e) {
        debugPrint('[NetworkLogger] write failed: $e');
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
      debugPrint('[NetworkLogger] list dates failed: $e');
      return const [];
    }
  }

  /// 读取某一天的全部记录(按时间顺序，最旧在前)。
  Future<List<NetworkLogEntry>> readDate(String dateKey) async {
    final dir = _dir;
    if (dir == null) return const [];
    final file = File('${dir.path}/$dateKey.jsonl');
    if (!await file.exists()) return const [];
    try {
      final lines = await file.readAsLines();
      final out = <NetworkLogEntry>[];
      for (final line in lines) {
        final trimmed = line.trim();
        if (trimmed.isEmpty) continue;
        try {
          final json = jsonDecode(trimmed) as Map<String, dynamic>;
          out.add(NetworkLogEntry.fromJson(json));
        } catch (_) {
          // 单行损坏不影响其余记录。
        }
      }
      return out;
    } catch (e) {
      debugPrint('[NetworkLogger] read $dateKey failed: $e');
      return const [];
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
      debugPrint('[NetworkLogger] delete $dateKey failed: $e');
    }
  }

  /// 递归折叠超长字符串(如 base64 音频)与超大集合，使日志可读且体积可控。
  /// 返回的结构只含 JSON 可序列化类型(Map/List/String/num/bool/null)。
  Object? sanitize(Object? value, {int depth = 0}) {
    if (depth > 8) return '<…too deep…>';
    if (value == null || value is num || value is bool) return value;
    if (value is String) {
      if (value.length > maxStringLength) {
        return '<${value.length} chars omitted>';
      }
      return value;
    }
    if (value is Map) {
      final out = <String, dynamic>{};
      value.forEach((k, v) {
        out['$k'] = sanitize(v, depth: depth + 1);
      });
      return out;
    }
    if (value is List) {
      const maxItems = 50;
      final out = <Object?>[];
      for (var i = 0; i < value.length && i < maxItems; i++) {
        out.add(sanitize(value[i], depth: depth + 1));
      }
      if (value.length > maxItems) {
        out.add('<${value.length - maxItems} more items omitted>');
      }
      return out;
    }
    // 其他不可直接序列化的类型，转字符串后再做长度折叠。
    final s = value.toString();
    return s.length > maxStringLength ? '<${s.length} chars omitted>' : s;
  }

  /// 估算任意 body 的原始字节大小(用于展示请求/响应体量)。
  static int? estimateBytes(Object? body) {
    if (body == null) return null;
    try {
      if (body is String) return utf8.encode(body).length;
      if (body is List<int>) return body.length;
      return utf8.encode(jsonEncode(body)).length;
    } catch (_) {
      return null;
    }
  }

  /// 本地日期键 YYYY-MM-DD。
  static String _dateKey(DateTime dt) {
    final y = dt.year.toString().padLeft(4, '0');
    final m = dt.month.toString().padLeft(2, '0');
    final d = dt.day.toString().padLeft(2, '0');
    return '$y-$m-$d';
  }
}
