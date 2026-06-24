import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

/// Pophie 后端对接配置(见 docs/API对接文档.md §1)。
///
/// - [baseUrl]：后端地址，如 `http://192.168.23.163:8000`(可在设置页修改)。
/// - [robotId]：本设备唯一标识。首次启动生成 `robot-<uuid>` 并持久化，
///   后续所有请求复用，保证记忆/上下文按设备隔离(文档 §1.2)。
/// - [sessionId]：会话标识。首轮对话后由服务端回传并保存，重启复用以延续
///   L2 会话记忆(文档 §1.2)。
/// - [voiceId]：TTS 音色 ID(文档 §2.7)。空则用服务端默认音色。
@immutable
class PophieConfig {
  final String baseUrl;
  final String robotId;
  final String? sessionId;
  final String voiceId;

  const PophieConfig({
    required this.baseUrl,
    required this.robotId,
    this.sessionId,
    this.voiceId = '',
  });

  /// 默认后端地址(用户联调环境)。设置页可覆盖。
  static const String defaultBaseUrl = 'http://223.109.143.135:8000';

  bool get isValid => baseUrl.trim().isNotEmpty;

  PophieConfig copyWith({
    String? baseUrl,
    String? robotId,
    String? sessionId,
    String? voiceId,
  }) =>
      PophieConfig(
        baseUrl: baseUrl ?? this.baseUrl,
        robotId: robotId ?? this.robotId,
        sessionId: sessionId ?? this.sessionId,
        voiceId: voiceId ?? this.voiceId,
      );

  Map<String, dynamic> toJson() => {
        'baseUrl': baseUrl,
        'robotId': robotId,
        'sessionId': sessionId,
        'voiceId': voiceId,
      };

  factory PophieConfig.fromJson(Map<String, dynamic> json) => PophieConfig(
        baseUrl: json['baseUrl'] as String? ?? defaultBaseUrl,
        robotId: json['robotId'] as String? ?? _newRobotId(),
        sessionId: json['sessionId'] as String?,
        voiceId: json['voiceId'] as String? ?? '',
      );

  static String _newRobotId() => 'robot-${const Uuid().v4()}';
}

/// Pophie 配置的本地持久化仓库(JSON 文件，范式同 [LlmConfigStore])。
class PophieConfigStore {
  static const String _fileName = 'pophie_config.json';

  PophieConfig? _config;
  bool _loaded = false;
  File? _file;

  PophieConfig get config =>
      _config ??
      PophieConfig(
        baseUrl: PophieConfig.defaultBaseUrl,
        robotId: PophieConfig._newRobotId(),
      );
  bool get isLoaded => _loaded;

  Future<File> _resolveFile() async {
    return _file ??= File(
        '${(await getApplicationDocumentsDirectory()).path}/$_fileName');
  }

  /// 加载持久化配置;文件不存在时生成新 robotId 并写入默认值。
  Future<void> load() async {
    if (_loaded) return;
    try {
      final f = await _resolveFile();
      if (await f.exists()) {
        _config = PophieConfig.fromJson(
            Map<String, dynamic>.from(jsonDecode(await f.readAsString()) as Map));
      } else {
        _config = PophieConfig(
          baseUrl: PophieConfig.defaultBaseUrl,
          robotId: PophieConfig._newRobotId(),
        );
        await _save();
      }
    } catch (e) {
      debugPrint('[PophieConfigStore] load failed, fallback to default: $e');
      _config = PophieConfig(
        baseUrl: PophieConfig.defaultBaseUrl,
        robotId: PophieConfig._newRobotId(),
      );
    }
    _loaded = true;
  }

  Future<void> save(PophieConfig config) async {
    _config = config;
    await _save();
  }

  Future<void> _save() async {
    try {
      final f = await _resolveFile();
      await f.writeAsString(jsonEncode(config.toJson()));
    } catch (e) {
      debugPrint('[PophieConfigStore] save failed: $e');
    }
  }
}
