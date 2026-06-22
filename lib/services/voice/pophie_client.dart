import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'pophie_config.dart';

/// 用户侧多模态感知上下文(文档 §2.4)。仅携带非空字段。
class PophiePerception {
  /// 用户面部表情(7 类 key：angry/disgust/fear/happy/neutral/sad/surprise)。
  final String? facialExpression;

  /// 端侧身份识别到的人名(进入大模型上下文，用于个性化称呼)。
  final String? identity;

  /// 手势 type(见文档 §2.4 取值，如 wave/thumbs_up/heart)。
  final String? gestureType;

  /// 抚摸类物理交互，如「摸头」「拥抱」。
  final String? touch;

  const PophiePerception({
    this.facialExpression,
    this.identity,
    this.gestureType,
    this.touch,
  });

  bool get isEmpty =>
      (facialExpression == null || facialExpression!.isEmpty) &&
      (identity == null || identity!.isEmpty) &&
      (gestureType == null || gestureType!.isEmpty) &&
      (touch == null || touch!.isEmpty);

  Map<String, dynamic> toJson() {
    final m = <String, dynamic>{};
    if (facialExpression != null && facialExpression!.isNotEmpty) {
      m['facial_expression'] = facialExpression;
    }
    if (identity != null && identity!.isNotEmpty) m['identity'] = identity;
    if (gestureType != null && gestureType!.isNotEmpty) {
      m['gesture'] = {'type': gestureType};
    }
    if (touch != null && touch!.isNotEmpty) m['touch'] = touch;
    return m;
  }
}

/// `/api/chat` 的结构化结果(文档 §2.6 / §3.4)。
class PophieChatResult {
  /// 机器人回复正文。空字符串表示静默(STT 空结果 / LLM 失败)。
  final String text;

  /// 机器人表情(7 类 key)。
  final String facialExpression;

  /// 互动动作状态(FSM 9 态之一)，可直接驱动虚拟形象。
  final String robotState;

  /// 本轮 STT 识别到的用户文本(纯文字输入时为空)。
  final String sttText;

  /// 解码后的 TTS 音频字节(WAV/MP3)。无音频时为 null。
  final Uint8List? audioBytes;

  /// 音频格式(wav/mp3)。
  final String? audioFormat;

  /// 服务端回传的 session_id(需保存复用)。
  final String? sessionId;

  const PophieChatResult({
    required this.text,
    required this.facialExpression,
    required this.robotState,
    required this.sttText,
    this.audioBytes,
    this.audioFormat,
    this.sessionId,
  });

  bool get isSilent => text.trim().isEmpty;
}

/// TTS 音色项(文档 §3.2 tts_voices)。
class TtsVoice {
  const TtsVoice({
    required this.id,
    required this.label,
    required this.isDefault,
    required this.instruct,
  });

  /// 音色 ID(写入 ChatInput.voice_id)。
  final String id;
  /// 中文展示名(如「呆萌机器人」)。
  final String label;
  /// 是否为服务端默认音色。
  final bool isDefault;
  /// 是否支持 instruct(指令式音色)。
  final bool instruct;

  factory TtsVoice.fromJson(Map<String, dynamic> json) => TtsVoice(
        id: json['id'] as String? ?? '',
        label: json['label'] as String? ?? '',
        isDefault: json['default'] as bool? ?? false,
        instruct: json['instruct'] as bool? ?? false,
      );
}

/// `/api/schema` 的解析结果(文档 §3.2)。当前只取 tts_voices;
/// 其余字段(表情/手势/robot_states 等)暂不暴露,按需再扩。
class PophieSchema {
  const PophieSchema({required this.ttsVoices});

  /// 服务端可用 TTS 音色列表(顺序同服务端返回)。
  final List<TtsVoice> ttsVoices;

  bool get isEmpty => ttsVoices.isEmpty;

  factory PophieSchema.fromJson(Map<String, dynamic> json) {
    final voices = (json['tts_voices'] as List?)
            ?.map((e) => TtsVoice.fromJson(e as Map<String, dynamic>))
            .toList(growable: false) ??
        const <TtsVoice>[];
    return PophieSchema(ttsVoices: voices);
  }
}

/// 主动消息(文档 §3.8 items[] 里的一条)。服务端到点提醒/tick 决策产生,
/// 客户端轮询拉取后,用 content 文本调 /api/tts 合成播放。
class ProactiveMessage {
  const ProactiveMessage({
    required this.id,
    required this.content,
    this.createdAt,
  });

  /// 消息唯一 id(增量轮询游标,下次 since_id 用)。
  final int id;
  /// 主动消息正文(TTS 文本)。
  final String content;
  /// 创建时间(服务端 created_at)。
  final DateTime? createdAt;

  factory ProactiveMessage.fromJson(Map<String, dynamic> json) {
    DateTime? createdAt;
    final raw = json['created_at'] as String?;
    if (raw != null) {
      try {
        createdAt = DateTime.parse(raw);
      } catch (_) {}
    }
    return ProactiveMessage(
      id: (json['id'] as num?)?.toInt() ?? 0,
      content: json['content'] as String? ?? '',
      createdAt: createdAt,
    );
  }
}

/// `/api/proactive_messages` 的轮询结果(文档 §3.8)。
class ProactiveResult {
  const ProactiveResult({required this.items, required this.lastId});

  /// 本次拉到的新消息列表(按 id 升序)。
  final List<ProactiveMessage> items;
  /// 本次结果的最大 id(下次 since_id 用)。空列表时为入参 sinceId。
  final int lastId;

  bool get isEmpty => items.isEmpty;
}

/// Pophie 后端 HTTP 客户端(见 docs/API对接文档.md)。
///
/// 核心是 [chat]：把整段录音(16k 单声道 WAV)或文字发给 `/api/chat`，
/// 服务端一次完成 STT + LLM + TTS，返回回复文本、表情、FSM 状态与 TTS 音频。
class PophieClient {
  final Dio _dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 8),
    receiveTimeout: const Duration(seconds: 40),
  ));

  PophieConfig _config = const PophieConfig(
    baseUrl: PophieConfig.defaultBaseUrl,
    robotId: 'robot-default',
  );
  PophieConfig get config => _config;

  /// 注入/更新配置(由 AppController 从 [PophieConfigStore] 注入)。
  void configure(PophieConfig config) {
    _config = config;
    debugPrint('[Pophie] configured baseUrl=${config.baseUrl} '
        'robotId=${config.robotId} session=${config.sessionId}');
  }

  /// session_id 由服务端回传后更新(供持久化)。
  String? get sessionId => _config.sessionId;
  set sessionId(String? id) {
    if (id == null || id == _config.sessionId) return;
    _config = _config.copyWith(sessionId: id);
  }

  bool get isConfigured => _config.isValid;

  /// 健康检查(文档 §3.1)。返回语音是否可用，失败返回 false。
  Future<bool> health() async {
    try {
      final r = await _dio.get<Map<String, dynamic>>('${_config.baseUrl}/api/health');
      final ok = r.data?['ok'] == true;
      debugPrint('[Pophie] health ok=$ok data=${r.data}');
      return ok;
    } catch (e) {
      debugPrint('[Pophie] health failed: $e');
      return false;
    }
  }

  /// 拉取 Schema 元数据(文档 §3.2)。用于构建 TTS 音色选择 UI。
  /// 失败抛异常,调用方捕获降级(回退到本地空列表)。
  Future<PophieSchema> fetchSchema() async {
    final r = await _dio.get<Map<String, dynamic>>(
      '${_config.baseUrl}/api/schema',
    );
    final data = r.data ?? const {};
    debugPrint('[Pophie] schema fetched: '
        'tts_voices=${(data['tts_voices'] as List?)?.length ?? 0}');
    return PophieSchema.fromJson(data);
  }

  /// 轮询主动消息(文档 §3.8)。返回 id > [sinceId] 的新消息。
  /// 失败不抛异常,返回空列表(避免 5s 轮询抖动刷屏);真正要播放时调用方
  /// 再自行处理。robot_id 必传(隔离设备);session_id 可选(按会话过滤)。
  Future<ProactiveResult> fetchProactiveMessages({
    int sinceId = 0,
    int limit = 50,
  }) async {
    try {
      final r = await _dio.get<Map<String, dynamic>>(
        '${_config.baseUrl}/api/proactive_messages',
        queryParameters: {
          'robot_id': _config.robotId,
          if (_config.sessionId != null && _config.sessionId!.isNotEmpty)
            'session_id': _config.sessionId,
          'since_id': sinceId,
          'limit': limit,
        },
      );
      final data = r.data ?? const {};
      final items = (data['items'] as List?)
              ?.map((e) => ProactiveMessage.fromJson(e as Map<String, dynamic>))
              .where((m) => m.content.isNotEmpty) // 跳过空内容
              .toList(growable: false) ??
          const <ProactiveMessage>[];
      final lastId = items.isEmpty
          ? sinceId
          : items.map((m) => m.id).reduce((a, b) => a > b ? a : b);
      return ProactiveResult(items: items, lastId: lastId);
    } catch (e) {
      debugPrint('[Pophie] proactive poll failed: $e');
      return ProactiveResult(items: const [], lastId: sinceId);
    }
  }

  /// 单独 TTS 合成(文档 §3.6)。主动消息等场景:proactive_messages 只回文本,
  /// 需用本方法把文本合成成音频再播放。失败抛异常,调用方 catch 降级。
  ///
  /// 返回 (音频字节, 格式)。voiceId 省略时用配置中的音色。
  Future<({Uint8List bytes, String format})> tts(
    String text, {
    String? voiceId,
  }) async {
    final body = <String, dynamic>{
      'text': text,
      if ((voiceId ?? _config.voiceId).isNotEmpty)
        'voice_id': voiceId ?? _config.voiceId,
    };
    final r = await _dio.post<Map<String, dynamic>>(
      '${_config.baseUrl}/api/tts',
      data: body,
      // TTS 合成可能较慢,但不应占用 chat 的 40s;给单独超时。
      options: Options(
        contentType: Headers.jsonContentType,
        receiveTimeout: const Duration(seconds: 20),
      ),
    );
    final data = r.data ?? const {};
    final audio = (data['audio'] as Map?)?.cast<String, dynamic>();
    if (audio == null || audio['data'] is! String) {
      throw Exception('TTS response has no audio.data');
    }
    final bytes = base64Decode(audio['data'] as String);
    final format = audio['format'] as String? ?? 'wav';
    return (bytes: bytes, format: format);
  }

  /// 主对话(文档 §3.4)。[wavBytes] 为 16k 单声道 WAV，与 [text] 至少一个非空。
  ///
  /// [userId] 用于响应回显/溯源；[skipTts] 为 null 时遵循服务端配置。
  Future<PophieChatResult> chat({
    String? text,
    Uint8List? wavBytes,
    PophiePerception? perception,
    String? userId,
    bool? skipTts,
  }) async {
    final input = <String, dynamic>{
      'text': text ?? '',
    };
    if (wavBytes != null && wavBytes.isNotEmpty) {
      input['audio'] = {
        'format': 'wav',
        'encoding': 'base64',
        'sample_rate': 16000,
        'data': base64Encode(wavBytes),
      };
    }
    if (perception != null && !perception.isEmpty) {
      input['perception'] = perception.toJson();
    }
    if (_config.voiceId.isNotEmpty) input['voice_id'] = _config.voiceId;
    if (skipTts != null) input['skip_tts'] = skipTts;

    final body = <String, dynamic>{
      'robot_id': _config.robotId,
      if (_config.sessionId != null) 'session_id': _config.sessionId,
      if (userId != null && userId.isNotEmpty) 'user_id': userId,
      'input': input,
    };

    final r = await _dio.post<Map<String, dynamic>>(
      '${_config.baseUrl}/api/chat',
      data: body,
      options: Options(contentType: Headers.jsonContentType),
    );
    final data = r.data ?? const {};

    // 保存 session_id 供后续复用。
    final sid = data['session_id'] as String?;
    if (sid != null) sessionId = sid;

    final output = (data['output'] as Map?)?.cast<String, dynamic>() ?? const {};
    final stt = (data['stt'] as Map?)?.cast<String, dynamic>();

    Uint8List? audioBytes;
    String? audioFormat;
    final audio = (output['audio'] as Map?)?.cast<String, dynamic>();

    // 诊断:打印 output 完整结构(audio.data 截断),定位"无音频"根因。
    // 确认稳定后删掉。
    {
      final safeOutput = Map<String, dynamic>.from(output);
      if (safeOutput['audio'] is Map) {
        final a = Map<String, dynamic>.from(safeOutput['audio'] as Map);
        if (a['data'] is String) {
          a['data'] = '<base64 ${(a['data'] as String).length} chars>';
        }
        safeOutput['audio'] = a;
      }
      debugPrint('[Pophie] response data.keys: ${data.keys.toList()}');
      debugPrint('[Pophie] response output: ${jsonEncode(safeOutput)}');
    }

    if (audio != null && audio['data'] is String) {
      try {
        audioBytes = base64Decode(audio['data'] as String);
        audioFormat = audio['format'] as String? ?? 'wav';
      } catch (e) {
        debugPrint('[Pophie] audio decode failed: $e');
      }
    }

    return PophieChatResult(
      text: (output['text'] as String?) ?? '',
      facialExpression: (output['facial_expression'] as String?) ?? 'neutral',
      robotState: (output['robot_state'] as String?) ?? 'idle',
      sttText: (stt?['text'] as String?) ?? '',
      audioBytes: audioBytes,
      audioFormat: audioFormat,
      sessionId: sid,
    );
  }

  void dispose() => _dio.close(force: true);

  // —— PCM16 → WAV 封装 ——
  // 后端 STT 要求 16kHz / 单声道 / 16-bit PCM 的 WAV(文档 §2.3)。
  // 录音管线产出的是裸 PCM 分片，这里补 44 字节 RIFF/WAVE 头拼成完整 WAV。
  static Uint8List pcm16ToWav(
    Uint8List pcm, {
    int sampleRate = 16000,
    int channels = 1,
    int bitsPerSample = 16,
  }) {
    final byteRate = sampleRate * channels * bitsPerSample ~/ 8;
    final blockAlign = channels * bitsPerSample ~/ 8;
    final dataLen = pcm.length;
    final header = BytesBuilder();

    void writeString(String s) => header.add(ascii.encode(s));
    void writeUint32(int v) {
      final b = ByteData(4)..setUint32(0, v, Endian.little);
      header.add(b.buffer.asUint8List());
    }

    void writeUint16(int v) {
      final b = ByteData(2)..setUint16(0, v, Endian.little);
      header.add(b.buffer.asUint8List());
    }

    writeString('RIFF');
    writeUint32(36 + dataLen); // 整个文件大小 - 8
    writeString('WAVE');
    writeString('fmt ');
    writeUint32(16); // fmt chunk 大小
    writeUint16(1); // PCM
    writeUint16(channels);
    writeUint32(sampleRate);
    writeUint32(byteRate);
    writeUint16(blockAlign);
    writeUint16(bitsPerSample);
    writeString('data');
    writeUint32(dataLen);

    final out = BytesBuilder();
    out.add(header.toBytes());
    out.add(pcm);
    return out.toBytes();
  }
}
