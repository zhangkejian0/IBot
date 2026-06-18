/// 语音助手的有限状态机阶段。
///
/// 状态流转(正常一轮对话):
///   idle → waking(检测到唤醒词/手动触发)
///        → listening(采集语音 + 云端 ASR)
///        → thinking(LLM 生成回复)
///        → speaking(TTS 播报)
///        → idle
///
/// 任一阶段出错或被中断都会回到 idle。
enum VoiceState {
  /// 未启用 / 空闲(若开启唤醒则持续监听唤醒词)。
  idle,

  /// 刚检测到唤醒词,正切换到聆听态(短暂过渡)。
  waking,

  /// 聆听用户说话:麦克风采集中,同步送云端 ASR。
  /// 虚拟宠物应切到 listening 表情,嘴部随用户音量张合。
  listening,

  /// 思考中:等待 LLM 回复。
  /// 虚拟宠物应切到 thinking 表情。
  thinking,

  /// 播报中:TTS 合成并播放回复。
  /// 虚拟宠物嘴部应随 TTS 音量张合(可切 happy/speaking 态)。
  speaking,
}

/// VoiceState 的扩展工具:映射到虚拟宠物前端 FaceState 字符串。
///
/// 虚拟宠物 React 端(window.__face.setState)接受的 FaceState 取值见
/// assets/html/src/face/types.ts,与本项目 [VoiceState] 一一对应。
extension VoiceStateFaceMapping on VoiceState {
  /// 对应虚拟宠物前端的 FaceState 名称。
  ///
  /// 注意:idle 返回 'idle'(而非 null),用于在对话结束时把前端表情
  /// 明确切回 neutral。是否据此推送由 camera_screen 的 _pushAll 决定
  /// (它会在语音刚结束时强制推一次,避免表情卡在 listening)。
  String? get faceState {
    switch (this) {
      case VoiceState.idle:
        return 'idle';
      case VoiceState.waking:
      case VoiceState.listening:
        return 'listening';
      case VoiceState.thinking:
        return 'thinking';
      case VoiceState.speaking:
        return 'happy';
    }
  }

  /// 是否处于活跃对话中(非 idle)。虚拟宠物推送时据此判断要不要接管表情。
  bool get isActive => this != VoiceState.idle;
}
