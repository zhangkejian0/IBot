import 'dart:async';

import 'package:flutter/foundation.dart';

/// 语音识别(ASR)+ 语音合成(TTS)抽象服务。
///
/// 设计为可替换的服务商实现层(默认阿里云智能语音交互),上层 [VoiceAssistant]
/// 只依赖此抽象,换讯飞/百度时只改本文件内的实现。
///
/// 阶段 1 骨架:接口已定,实现留到阶段 3(ASR)/ 阶段 5(TTS)。
class SpeechService {
  // 阶段 3/5 接入阿里云后,根据凭证校验结果改写。
  bool _asrAvailable = false; // ignore: prefer_final_fields
  bool _ttsAvailable = false; // ignore: prefer_final_fields
  bool get asrAvailable => _asrAvailable;
  bool get ttsAvailable => _ttsAvailable;

  /// 初始化(预建连接 / 加载凭证)。失败置对应 available=false。
  Future<void> initialize() async {
    // TODO(阶段3): 配置阿里云 ASR 凭证(AppKey/AccessKey/token)。
    // TODO(阶段5): 配置阿里云 TTS 凭证。
    debugPrint('[Speech] initialize (stub)');
  }

  /// 流式语音识别:边收音边转文字,返回最终完整文本。
  ///
  /// [pcmStream] 为 16kHz/16-bit/单声道 PCM 流;[onPartial] 回调中间结果
  /// (可用于实时显示字幕)。识别在流关闭或检测到静音后完成。
  ///
  /// 阶段 3 实现:阿里云一句话识别(WebSocket,实时双向流)。
  Future<String> recognize(
    Stream<Uint8List> pcmStream, {
    void Function(String partial)? onPartial,
  }) async {
    // 语音对话现已改走 Pophie 后端 /api/chat(STT+LLM+TTS 一体化),
    // 本地 SpeechService 暂不参与主流程。保留接口供 /api/stt、/api/tts 单独调用。
    debugPrint('[Speech] recognize (stub, unused: 走 Pophie /api/chat)');
    return '';
  }

  /// 文本转语音:合成 [text] 的语音并播放(或返回音频字节)。
  ///
  /// 阶段 5 实现:阿里云语音合成。播放用 `record` 配套播放器或 just_audio。
  /// [onLevel] 回调合成/播放音量,驱动虚拟宠物嘴部张合。
  Future<void> synthesizeAndPlay(
    String text, {
    void Function(double level)? onLevel,
  }) async {
    // TODO(阶段5): TTS 合成 + 播放,边播边报音量。
    debugPrint('[Speech] synthesizeAndPlay (stub): $text');
  }

  /// 停止当前播放(TTS)。
  Future<void> stopSpeaking() async {
    // TODO(阶段5): 停止播放器。
    debugPrint('[Speech] stopSpeaking (stub)');
  }

  Future<void> dispose() async {
    // TODO: 释放 WebSocket / 播放器资源。
  }
}
