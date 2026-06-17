import 'dart:async';

import 'package:flutter/foundation.dart';

import 'audio_capture_service.dart';
import 'chat_service.dart';
import 'speech_service.dart';
import 'voice_state.dart';
import 'wake_word_service.dart';

/// 语音助手编排器:组合"采集 + 唤醒 + ASR + LLM + TTS"五段能力,
/// 以一个有限状态机对外暴露 [state] / [userText] / [replyText] / [level]。
///
/// 生命周期由 [AppController] 持有,随语音总开关 start/stop。对外是
/// [ChangeNotifier]:任何状态/文本/音量变化都 notifyListeners,UI 与虚拟宠物
/// 联动均订阅它(虚拟宠物推送见 camera_screen.dart 的 _pushAll)。
///
/// 状态流转见 [VoiceState] 注释。阶段 1 为可编译骨架:子服务均为 stub,
/// 本类只负责状态机骨架与 start/stop 流程;各阶段的真实串联在阶段 2-5 填充。
class VoiceAssistant extends ChangeNotifier {
  final AudioCaptureService audio = AudioCaptureService();
  final WakeWordService wakeWord = WakeWordService();
  final SpeechService speech = SpeechService();
  final ChatService chat = ChatService();

  VoiceState _state = VoiceState.idle;
  VoiceState get state => _state;

  /// 语音功能是否就绪可用(权限 + 模型/凭证齐全)。不可用时 start 直接跳过。
  bool _available = false;
  bool get isAvailable => _available;

  /// 是否正在运行(总开关已开)。区分"可用但未启用"与"已启用"。
  bool _running = false;
  bool get isRunning => _running;

  /// 最近一轮识别到的用户文本(字幕)。
  String _userText = '';
  String get userText => _userText;

  /// 最近一轮 LLM 回复文本(气泡字幕)。
  String _replyText = '';
  String get replyText => _replyText;

  /// 实时音量(0.0..1.0):listening 时来自麦克风,speaking 时来自 TTS。
  /// 虚拟宠物用它驱动嘴部张合(setListeningLoudness)。
  ValueNotifier<double> get level => audio.level;

  StreamSubscription<String>? _wakeSub;

  /// 是否启用语音唤醒(true 时 idle 态持续监听唤醒词)。
  bool wakeWordEnabled = true;
  /// 是否启用语音播报(TTS)。关闭时回复仅以文字显示。
  bool ttsEnabled = true;

  /// 由 AppController 在获取麦克风权限后调用,标记可用性。
  void markAvailable() {
    _available = true;
    notifyListeners();
  }

  /// 标记不可用(无麦克风权限 / 模型缺失等),并停止运行。
  void markUnavailable({String? reason}) {
    _available = false;
    if (_running) {
      stop();
    }
    debugPrint('[VoiceAssistant] unavailable: ${reason ?? '未知原因'}');
    notifyListeners();
  }

  /// 初始化子服务(加载唤醒模型、配置云端凭证)。幂等。
  /// 在后台逐个初始化,失败不阻断;最终可用性取决于各子服务的 available 标志。
  Future<void> initialize() async {
    await Future.wait([
      wakeWord.initialize(),
      speech.initialize(),
      chat.initialize(),
    ]);
    // 唤醒模型就绪即视为可用(至少能监听唤醒词);ASR/TTS/LLM 缺失时各流程会降级。
    _available = wakeWord.isAvailable || true; // 阶段1:先放行骨架
    debugPrint('[VoiceAssistant] initialized '
        'wake=${wakeWord.isAvailable} asr=${speech.asrAvailable} '
        'tts=${speech.ttsAvailable} llm=${chat.isAvailable}');
  }

  /// 开始运行:启动麦克风 + 唤醒监听。仅在总开关打开时调用。
  Future<void> start() async {
    if (_running || !_available) return;
    _running = true;
    _setState(VoiceState.idle);

    // 订阅唤醒事件:命中 → 进入对话流程。
    _wakeSub = wakeWord.onWake.listen(_onWake);

    // 启动麦克风采集,并把音频流同时喂给唤醒服务(持续)。
    await audio.start();
    if (wakeWordEnabled) {
      await wakeWord.start(audio.audioStream);
    }
    debugPrint('[VoiceAssistant] started (wakeWord=$wakeWordEnabled)');
    notifyListeners();
  }

  /// 停止运行:中止当前对话、释放麦克风、停唤醒。幂等。
  Future<void> stop() async {
    if (!_running) return;
    _running = false;
    await _wakeSub?.cancel();
    _wakeSub = null;
    await wakeWord.stop();
    await speech.stopSpeaking();
    await audio.stop();
    _userText = '';
    _replyText = '';
    _setState(VoiceState.idle);
    debugPrint('[VoiceAssistant] stopped');
    notifyListeners();
  }

  /// 手动触发一轮对话(不依赖唤醒词,供按钮/调试使用)。
  Future<void> triggerManually() async {
    if (!_running) return;
    _onWake(wakeWord.keyword);
  }

  /// 唤醒回调:进入对话主流程。
  /// idle → waking → listening → thinking → speaking → idle
  void _onWake(String keyword) {
    debugPrint('[VoiceAssistant] wake word: $keyword');
    _runConversation();
  }

  Future<void> _runConversation() async {
    if (_state != VoiceState.idle) return; // 防重入
    try {
      // waking:短暂过渡,通知 UI 进入聆听态。先暂停唤醒监听,
      // 避免把用户接下来说的话再次当作唤醒词触发。
      _setState(VoiceState.waking);
      await wakeWord.stop();

      // listening:ASR。麦克风流送云端识别。
      _setState(VoiceState.listening);
      _userText = '';
      notifyListeners();
      // TODO(阶段3): 阶段 3 接入云端 ASR 后,把 audio.audioStream 传入。
      //   届时 ASR 需要在识别完成后关闭流(或用 controller 收尾)。
      final text = await speech.recognize(
        const Stream.empty(),
        onPartial: (p) {
          _userText = p;
          notifyListeners();
        },
      );
      _userText = text;
      notifyListeners();
      if (text.trim().isEmpty) {
        await _resumeWakeListening();
        return;
      }

      // thinking:LLM。
      _setState(VoiceState.thinking);
      final reply = await chat.reply(
        text,
        onDelta: (d) {
          _replyText += d;
          notifyListeners();
        },
      );
      _replyText = reply;
      notifyListeners();

      // speaking:TTS。
      if (ttsEnabled && speech.ttsAvailable) {
        _setState(VoiceState.speaking);
        await speech.synthesizeAndPlay(
          reply,
          onLevel: (v) => audio.level.value = v,
        );
      }
    } catch (e) {
      debugPrint('[VoiceAssistant] conversation error: $e');
    } finally {
      _userText = '';
      _replyText = '';
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
      // 恢复唤醒监听,等待下一次唤醒。
      await _resumeWakeListening();
    }
  }

  /// 恢复唤醒监听(若总开关仍在运行且开启了唤醒)。
  /// 对话结束后调用,让助手重新回到待唤醒态。
  Future<void> _resumeWakeListening() async {
    if (_running && wakeWordEnabled && wakeWord.isAvailable) {
      await wakeWord.start(audio.audioStream);
    }
  }

  void _setState(VoiceState s) {
    if (_state == s) return;
    _state = s;
    debugPrint('[VoiceAssistant] state -> ${s.name}');
    notifyListeners();
  }

  @override
  void dispose() {
    stop();
    audio.dispose();
    wakeWord.dispose();
    speech.dispose();
    chat.dispose();
    super.dispose();
  }
}
