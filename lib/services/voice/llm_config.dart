import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// 大语言模型(LLM)的服务配置。
///
/// 预设 DeepSeek(OpenAI 兼容),但 baseUrl / apiKey / model 均可在设置页修改,
/// 因此同样适用于任何 OpenAI 兼容端点(通义千问 / 智谱 / Kimi / 自部署 vLLM 等)。
@immutable
class LlmConfig {
  /// 服务商端点(不含 /chat/completions)。DeepSeek 默认 https://api.deepseek.com。
  final String baseUrl;

  /// API Key(Bearer 鉴权)。
  final String apiKey;

  /// 模型名。DeepSeek 默认 deepseek-chat(V3)。
  final String model;

  /// System prompt:塑造助手人设。可结合虚拟宠物形象。
  final String systemPrompt;

  const LlmConfig({
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    required this.systemPrompt,
  });

  /// DeepSeek 预设默认值(实际 Key 可在设置页覆盖)。
  /// 这里的 apiKey 仅为占位,真正生效的 Key 由设置页录入并持久化。
  factory LlmConfig.deepseekDefault({String apiKey = ''}) => LlmConfig(
        baseUrl: 'https://api.deepseek.com',
        apiKey: apiKey,
        model: 'deepseek-chat',
        systemPrompt: '你是狗蛋,一个友善、简练的桌面虚拟伙伴。'
            '请用简短的口语化中文回答(适合语音播报),一般不超过两三句话。',
      );

  bool get isValid => apiKey.trim().isNotEmpty && baseUrl.trim().isNotEmpty;

  LlmConfig copyWith({
    String? baseUrl,
    String? apiKey,
    String? model,
    String? systemPrompt,
  }) =>
      LlmConfig(
        baseUrl: baseUrl ?? this.baseUrl,
        apiKey: apiKey ?? this.apiKey,
        model: model ?? this.model,
        systemPrompt: systemPrompt ?? this.systemPrompt,
      );

  Map<String, dynamic> toJson() => {
        'baseUrl': baseUrl,
        'apiKey': apiKey,
        'model': model,
        'systemPrompt': systemPrompt,
      };

  factory LlmConfig.fromJson(Map<String, dynamic> json) => LlmConfig(
        baseUrl: json['baseUrl'] as String? ??
            LlmConfig.deepseekDefault().baseUrl,
        apiKey: json['apiKey'] as String? ?? '',
        model: json['model'] as String? ?? LlmConfig.deepseekDefault().model,
        systemPrompt: json['systemPrompt'] as String? ??
            LlmConfig.deepseekDefault().systemPrompt,
      );
}

/// LLM 配置的本地持久化仓库(JSON 文件,范式同 [PersonRepository])。
/// 单独存文件而非混入 DisplaySettings,因为这是需要落盘的敏感/可编辑配置。
class LlmConfigStore {
  static const String _fileName = 'llm_config.json';

  LlmConfig _config = LlmConfig.deepseekDefault();
  bool _loaded = false;
  File? _file;

  LlmConfig get config => _config;
  bool get isLoaded => _loaded;

  Future<File> _resolveFile() async {
    return _file ??= File(
        '${(await getApplicationDocumentsDirectory()).path}/$_fileName');
  }

  /// 加载持久化配置;文件不存在时写入默认值(DeepSeek 预设)。
  /// 预置默认 Key 仅在首次写入时使用,后续以用户在设置页录入的为准。
  Future<void> load({String defaultApiKey = ''}) async {
    if (_loaded) return;
    try {
      final f = await _resolveFile();
      if (await f.exists()) {
        final raw = await f.readAsString();
        _config = LlmConfig.fromJson(
            Map<String, dynamic>.from(jsonDecode(raw) as Map));
      } else {
        // 首次:写入 DeepSeek 预设(含预置 Key),便于开箱即用。
        _config = LlmConfig.deepseekDefault(apiKey: defaultApiKey);
        await _save();
      }
    } catch (e) {
      debugPrint('[LlmConfigStore] load failed, fallback to default: $e');
      _config = LlmConfig.deepseekDefault(apiKey: defaultApiKey);
    }
    _loaded = true;
  }

  /// 更新并持久化配置。
  Future<void> save(LlmConfig config) async {
    _config = config;
    await _save();
  }

  Future<void> _save() async {
    try {
      final f = await _resolveFile();
      await f.writeAsString(jsonEncode(_config.toJson()));
    } catch (e) {
      debugPrint('[LlmConfigStore] save failed: $e');
    }
  }
}
