import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// 流式 STT 事件类型(对应文档 §3.5.1 服务端 → 客户端 JSON 文本帧的 `type`)。
enum SttEventType {
  /// 会话参数(含 silence_commit_ms / conversation_idle_sec 等)。
  meta,

  /// DashScope 会话已建立,可开始发 chunk。
  ready,

  /// 中间识别结果(可驱动「聆听中」UI;勿直接触发 LLM)。
  partial,

  /// 当前一句定稿(text 非空时视为有效对话)。
  finalResult,

  /// 服务端主动结束会话(如空闲超时);随后关闭 WebSocket。
  sessionEnd,

  /// 致命错误;客户端应关闭 WS 并提示。
  error,

  /// WebSocket 已关闭(本地事件,流结束/连接断开时发出)。
  closed,
}

/// 流式 STT 的一条事件(文档 §3.5.1)。
class SttEvent {
  const SttEvent(
    this.type, {
    this.text,
    this.voice,
    this.message,
    this.silenceCommitMs,
    this.conversationIdleSec,
  });

  final SttEventType type;

  /// partial / final 的识别文本。
  final String? text;

  /// final 携带的语音侧道情感参数(tone/intonation/speed),透传给 LLM/TTS。
  final Map<String, dynamic>? voice;

  /// error / session_end 的说明文案。
  final String? message;

  /// meta 下发的端侧 VAD 兜底阈值(毫秒)。
  final int? silenceCommitMs;

  /// meta 下发的对话空闲超时(秒)。
  final int? conversationIdleSec;
}

/// 流式语音识别 WebSocket 客户端(文档 §3.5.1 `WS /api/stt/stream`)。
///
/// 用户唤醒后进入对话模式:[connect] 建连 → [start] 发送会话参数 → 收到
/// `ready` 后持续 [sendChunk] 推送 16kHz/PCM16/单声道 Base64 分片;服务端回推
/// `partial` / `final`。一句结束(云侧 turn_detection 或端侧 [commit])后由
/// 上层调 `/api/chat/stream` 与 `/api/tts/stream`,播完回到聆听(同一 WS 多轮复用)。
///
/// 事件经 [events] 广播流对外暴露(meta/ready/partial/final/session_end/error/closed)。
/// 空闲超时由服务端主动 `session_end` + 关 WS,客户端**无需**发 `end`。
///
/// 载荷为 **JSON 文本帧**(非二进制)。`speech.enabled=false` 时服务端以 close
/// code 1013 拒绝连接([connect] 抛异常)。
class SttStreamClient {
  SttStreamClient(this.baseUrl);

  /// 后端基地址(同 HTTP API,如 `http://host:8000`);内部换成 ws(s) 并接
  /// `/api/stt/stream`。
  final String baseUrl;

  WebSocketChannel? _channel;
  StreamSubscription? _sub;

  final StreamController<SttEvent> _events =
      StreamController<SttEvent>.broadcast();

  /// 流式 STT 事件广播流。
  Stream<SttEvent> get events => _events.stream;

  bool _closed = false;
  bool get isClosed => _closed;

  /// 把 HTTP(S) 基地址转换为流式 STT 的 WebSocket URL。
  /// `http://h:8000` → `ws://h:8000/api/stt/stream`;`https` → `wss`。
  static String toWsUrl(String baseUrl) {
    var b = baseUrl.trim();
    while (b.endsWith('/')) {
      b = b.substring(0, b.length - 1);
    }
    if (b.startsWith('https://')) {
      b = 'wss://${b.substring('https://'.length)}';
    } else if (b.startsWith('http://')) {
      b = 'ws://${b.substring('http://'.length)}';
    }
    return '$b/api/stt/stream';
  }

  /// 建立 WebSocket 连接并开始监听服务端帧。连接失败(含语音未启用 1013)抛异常。
  Future<void> connect() async {
    final url = toWsUrl(baseUrl);
    debugPrint('[SttStream] connecting $url');
    final channel = WebSocketChannel.connect(Uri.parse(url));
    // ready 在握手完成后 complete;失败(含被拒)抛异常,交上层回退。
    await channel.ready;
    _channel = channel;
    _sub = channel.stream.listen(
      _onMessage,
      onError: (e) {
        debugPrint('[SttStream] socket error: $e');
        _emit(const SttEvent(SttEventType.error, message: 'WebSocket 错误'));
        _emit(const SttEvent(SttEventType.closed));
      },
      onDone: () {
        debugPrint('[SttStream] socket done (code=${channel.closeCode})');
        _emit(const SttEvent(SttEventType.closed));
      },
      cancelOnError: true,
    );
    debugPrint('[SttStream] connected');
  }

  /// 发送 `start`:开启一次流式识别会话(文档 §3.5.1)。
  void start({
    int sampleRate = 16000,
    String language = 'zh',
    bool? turnDetection,
  }) {
    _send({
      'type': 'start',
      'sample_rate': sampleRate,
      'language': language,
      'turn_detection': ?turnDetection,
    });
  }

  /// 推送一段 PCM16(小端、单声道、16kHz)分片。内部 Base64 编码后发送。
  void sendChunk(Uint8List pcm16) {
    if (_closed || pcm16.isEmpty) return;
    _send({'type': 'chunk', 'data': base64Encode(pcm16)});
  }

  /// 手动结束当前一句(端侧 VAD 兜底;云侧 turn_detection 已开时一般不需要)。
  void commit() => _send({'type': 'commit'});

  /// 主动退出对话模式(可选;空闲时也可等服务端 session_end)。
  void sendEnd() => _send({'type': 'end'});

  void _send(Map<String, dynamic> msg) {
    if (_closed) return;
    try {
      _channel?.sink.add(jsonEncode(msg));
    } catch (e) {
      debugPrint('[SttStream] send failed: $e');
    }
  }

  void _onMessage(dynamic data) {
    if (data is! String) {
      // 协议为 JSON 文本帧;二进制帧忽略。
      return;
    }
    Map<String, dynamic> obj;
    try {
      obj = jsonDecode(data) as Map<String, dynamic>;
    } catch (e) {
      debugPrint('[SttStream] bad json frame: $data ($e)');
      return;
    }
    final type = obj['type'] as String?;
    switch (type) {
      case 'meta':
        _emit(SttEvent(
          SttEventType.meta,
          silenceCommitMs: (obj['silence_commit_ms'] as num?)?.toInt(),
          conversationIdleSec: (obj['conversation_idle_sec'] as num?)?.toInt(),
        ));
        break;
      case 'ready':
        _emit(const SttEvent(SttEventType.ready));
        break;
      case 'partial':
        _emit(SttEvent(SttEventType.partial, text: obj['text'] as String?));
        break;
      case 'final':
        _emit(SttEvent(
          SttEventType.finalResult,
          text: obj['text'] as String?,
          voice: (obj['voice'] as Map?)?.cast<String, dynamic>(),
        ));
        break;
      case 'session_end':
        _emit(SttEvent(
          SttEventType.sessionEnd,
          message: obj['message'] as String?,
          conversationIdleSec: (obj['conversation_idle_sec'] as num?)?.toInt(),
        ));
        break;
      case 'error':
        _emit(SttEvent(SttEventType.error, message: obj['message'] as String?));
        break;
      default:
        debugPrint('[SttStream] unknown frame type: $type');
    }
  }

  void _emit(SttEvent ev) {
    if (!_events.isClosed) _events.add(ev);
  }

  /// 关闭连接并释放资源。可选先发 `end`(主动退出);幂等。
  Future<void> close({bool sendEndFrame = false}) async {
    if (_closed) return;
    if (sendEndFrame) sendEnd();
    _closed = true;
    await _sub?.cancel();
    _sub = null;
    try {
      await _channel?.sink.close();
    } catch (_) {}
    _channel = null;
    if (!_events.isClosed) await _events.close();
    debugPrint('[SttStream] closed');
  }
}
