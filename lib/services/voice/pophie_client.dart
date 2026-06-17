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
