import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'llm_config.dart';

/// LLM 对话服务:基于 OpenAI 兼容端点(默认 DeepSeek)的流式 chat completions。
///
/// 设计为可替换的服务商实现层:只要 baseUrl 指向的端点是 OpenAI 兼容的
/// /chat/completions(DeepSeek / 通义千问 / 智谱 / Kimi / vLLM 等),本类无需改动,
/// 仅靠 [LlmConfig] 切换。上层 [VoiceAssistant] 只依赖本抽象。
class ChatService {
  // 阶段 4 接入 LLM 后,根据 API Key 校验结果改写。
  bool _available = false; // ignore: prefer_final_fields
  bool get isAvailable => _available;

  final Dio _dio = Dio();

  /// 当前生效配置(由 AppController 从 [LlmConfigStore] 注入)。
  LlmConfig _config = const LlmConfig(
    baseUrl: 'https://api.deepseek.com',
    apiKey: '',
    model: 'deepseek-chat',
    systemPrompt: '',
  );
  LlmConfig get config => _config;

  /// 对话历史(含 system / user / assistant 消息)。新一轮会话保留上下文。
  final List<ChatMessage> _history = [];
  List<ChatMessage> get history => List.unmodifiable(_history);

  /// 注入配置并据此判断可用性(key 非空即视为可用,真正校验在首次请求时)。
  void configure(LlmConfig config) {
    _config = config;
    _available = config.isValid;
    debugPrint('[Chat] configured: baseUrl=${config.baseUrl} '
        'model=${config.model} available=$_available');
  }

  /// 初始化:本项目用配置驱动,无额外资源需预建,保留以匹配生命周期。
  Future<void> initialize() async {}

  /// 发送用户文本并返回助手完整回复。
  ///
  /// 走流式 SSE,逐段通过 [onDelta] 回调(供实时字幕),同时拼接为最终文本返回。
  /// system prompt 作为首条消息随每次请求一起发送(不计入可见历史)。
  /// 请求成功后把 user/assistant 两条并入 [_history]。
  Future<String> reply(
    String userText, {
    void Function(String delta)? onDelta,
  }) async {
    if (!_available) {
      throw StateError('ChatService 未配置有效的 baseUrl/apiKey');
    }

    final messages = <Map<String, String>>[
      {'role': 'system', 'content': _config.systemPrompt},
      ..._history
          .map((m) => {'role': m.role.name, 'content': m.content}),
      {'role': 'user', 'content': userText},
    ];

    final url = '${_config.baseUrl}/chat/completions';
    final full = StringBuffer();

    try {
      // 流式响应:responseType=stream 让 dio 返回 ResponseBody,我们逐行解析 SSE。
      final response = await _dio.post<ResponseBody>(
        url,
        options: Options(
          headers: {
            'Authorization': 'Bearer ${_config.apiKey}',
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
          },
          responseType: ResponseType.stream,
        ),
        data: jsonEncode({
          'model': _config.model,
          'messages': messages,
          'stream': true,
        }),
      );

      // SSE 每条以 "data: " 前缀;流末尾是 "data: [DONE]"。
      final stream = response.data!.stream;
      await for (final chunk in stream) {
        final text = utf8.decode(chunk);
        for (final line in LineSplitter.split(text)) {
          final trimmed = line.trim();
          if (trimmed.isEmpty || !trimmed.startsWith('data:')) continue;
          final payload = trimmed.substring(5).trim(); // 去掉 "data:"
          if (payload == '[DONE]') break;
          final delta = _extractDelta(payload);
          if (delta != null && delta.isNotEmpty) {
            full.write(delta);
            onDelta?.call(delta);
          }
        }
      }
    } on DioException catch (e) {
      final msg = 'LLM 请求失败: ${e.response?.statusCode} '
          '${e.response?.data != null ? utf8.decode(e.response!.data as List<int>) : e.message}';
      debugPrint('[Chat] $msg');
      throw StateError(msg);
    }

    final answer = full.toString().trim();
    // 并入历史(供下一轮上下文)。
    _history.add(ChatMessage(ChatRole.user, userText));
    _history.add(ChatMessage(ChatRole.assistant, answer));
    return answer.isEmpty ? '(没有收到回复)' : answer;
  }

  /// 从一条 SSE data JSON 中提取增量文本。
  /// OpenAI 兼容格式:choices[0].delta.content。
  String? _extractDelta(String json) {
    try {
      final obj = jsonDecode(json) as Map<String, dynamic>;
      final choices = obj['choices'] as List<dynamic>?;
      if (choices == null || choices.isEmpty) return null;
      final delta =
          (choices.first as Map<String, dynamic>)['delta'] as Map<String, dynamic>?;
      return delta?['content'] as String?;
    } catch (_) {
      return null;
    }
  }

  /// 清空对话历史(新一轮会话)。
  void reset() => _history.clear();

  Future<void> dispose() async {
    _dio.close();
  }
}

/// 单条对话消息。
class ChatMessage {
  final ChatRole role;
  final String content;
  const ChatMessage(this.role, this.content);
}

/// 对话角色。name 与 OpenAI 兼容 API 的 role 字段一致。
enum ChatRole { system, user, assistant }
