import 'dart:async';
import 'dart:math' as math;

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';

import 'audio_capture_service.dart';
import 'chat_service.dart';
import 'conversation_logger.dart';
import 'pophie_client.dart';
import 'speech_service.dart';
import 'voice_state.dart';
import 'wake_word_service.dart';

/// 语音助手编排器:本地唤醒(KWS)+ 麦克风采集 + Pophie 后端对话。
/// 以一个有限状态机对外暴露 [state] / [userText] / [replyText] / [level]。
///
/// 一轮对话:唤醒/双击触发 → 聆听整段语音(静音 VAD) → 整段 WAV 上传
/// Pophie /api/chat(一次完成 STT+LLM+TTS) → 播放返回的 TTS 音频。
/// 详见 docs/API对接文档.md 与 [_runConversation]。
///
/// 生命周期由 [AppController] 持有,随语音总开关 start/stop。对外是
/// [ChangeNotifier]:任何状态/文本/音量变化都 notifyListeners,UI 与虚拟宠物
/// 联动均订阅它(虚拟宠物推送见 camera_screen.dart 的 _pushAll)。
class VoiceAssistant extends ChangeNotifier {
  final AudioCaptureService audio = AudioCaptureService();
  final WakeWordService wakeWord = WakeWordService();
  final SpeechService speech = SpeechService();
  final ChatService chat = ChatService();

  /// Pophie 后端客户端:一次完成 STT + LLM + TTS(见 docs/API对接文档.md)。
  final PophieClient pophie = PophieClient();

  /// 对话交互日志(各阶段埋点),供设置页「交互日志」查看。
  /// 对象在 AppController 持有,设置页订阅它实时刷新。
  final ConversationLogger conversationLog = ConversationLogger();

  /// TTS 音频播放器(audioplayers，Android/iOS 通用)。
  final AudioPlayer _player = AudioPlayer();

  /// 端侧感知上下文提供者:由 AppController 注入，读取当前人脸表情/身份/手势，
  /// 随对话发给后端(文档 §2.4)。
  PophiePerception Function()? perceptionProvider;

  /// 当前用户身份(认识我)，作为 user_id 回显/溯源。由 AppController 注入。
  String? Function()? userIdProvider;

  /// session_id 更新后回调(供 AppController 持久化以延续会话记忆)。
  Future<void> Function(String sessionId)? onSessionPersist;

  /// 机器人最近一次回复携带的 FSM 状态(驱动虚拟形象，文档 §2.6)。
  String? _robotState;
  String? get robotState => _robotState;

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
  /// 在后台逐个初始化,失败不阻断;唤醒模型缺失只禁用"语音唤醒"子能力,
  /// 不影响整体可用性(麦克风权限才是可用性闸门,双击触发不依赖唤醒词)。
  /// 因此这里**不覆盖** [_available]——它已由 [markAvailable]/[markUnavailable]
  /// 按麦克风权限设置,这里仅记录各子服务就绪情况供诊断。
  Future<void> initialize() async {
    await Future.wait([
      wakeWord.initialize(),
      speech.initialize(),
      chat.initialize(),
    ]);
    debugPrint('[VoiceAssistant] initialized '
        'wake=${wakeWord.isAvailable} asr=${speech.asrAvailable} '
        'tts=${speech.ttsAvailable} llm=${chat.isAvailable}');
    // 唤醒模型是后台异步加载的,加载结果会影响设置页"语音唤醒"子开关的
    // 启用状态,故加载完成后需通知 UI 重建。
    notifyListeners();
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
    try {
      await _player.stop();
    } catch (_) {}
    audio.externalLevel = false;
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
    conversationLog.log('wake', '唤醒词: $keyword');
    _runConversation();
  }

  /// 一轮对话:聆听整段语音 → 发 Pophie /api/chat(STT+LLM+TTS) → 播报。
  ///
  /// 与旧的分段式(ASR→LLM→TTS)不同:后端一次完成全部处理并回传文本、表情、
  /// FSM 状态与 TTS 音频(见 docs/API对接文档.md §3.4)。
  Future<void> _runConversation() async {
    if (_state != VoiceState.idle) return; // 防重入
    try {
      // waking:短暂过渡。先暂停唤醒监听,避免把用户说的话再次当唤醒词。
      _setState(VoiceState.waking);
      await wakeWord.stop();

      // listening:采集一句完整语音(静音 VAD 自动结束)。
      _setState(VoiceState.listening);
      _userText = '';
      _replyText = '';
      _robotState = null;
      notifyListeners();
      final pcm = await audio.captureUtterance();
      if (pcm == null || pcm.isEmpty) {
        debugPrint('[VoiceAssistant] no speech captured');
        conversationLog.log('listen', '未采集到语音(VAD 无输入或超时)',
            error: true);
        return; // finally 会回到 idle 并恢复唤醒监听
      }
      final wav = PophieClient.pcm16ToWav(pcm);
      conversationLog.log('listen',
          '已采集语音 ${pcm.length} 字节 (PCM) → WAV ${wav.length} 字节');

      // thinking:整段上传后端,等待 STT+LLM+TTS 一体化结果。
      _setState(VoiceState.thinking);
      final perception = perceptionProvider?.call();
      final userId = userIdProvider?.call();
      conversationLog.log('think',
          '上传 Pophie /api/chat (skipTts=${!ttsEnabled})…');
      PophieChatResult result;
      try {
        result = await pophie.chat(
          wavBytes: wav,
          perception: perception,
          userId: userId,
          skipTts: !ttsEnabled,
        );
      } catch (e) {
        debugPrint('[VoiceAssistant] chat request failed: $e');
        conversationLog.log('think', 'Pophie 请求失败: $e', error: true);
        return; // finally 会回到 idle 并恢复唤醒监听
      }

      if (result.sessionId != null) {
        await onSessionPersist?.call(result.sessionId!);
      }

      _userText = result.sttText;
      _robotState = result.robotState;
      notifyListeners();

      // 记录后端返回详情:STT 文本、LLM 回复、robot_state、音频字节数。
      final audioInfo = result.audioBytes != null
          ? '音频 ${result.audioBytes!.length} 字节 (${result.audioFormat ?? 'wav'})'
          : '无音频';
      conversationLog.log('think',
          '后端返回: STT="${result.sttText}" | LLM="${result.text}" | '
          'robotState=${result.robotState} | $audioInfo');

      // STT 空 / LLM 失败:后端返回静默(空 text),端侧保持安静,不播报。
      if (result.isSilent) {
        debugPrint('[VoiceAssistant] silent reply (empty STT or LLM fail)');
        conversationLog.log('think',
            '静默回复(STT 空或 LLM 失败),不播报', error: true);
        return; // finally 会回到 idle 并恢复唤醒监听
      }

      _replyText = result.text;
      notifyListeners();

      // speaking:播放后端合成的 TTS 音频,音量包络驱动虚拟宠物嘴部张合。
      if (ttsEnabled && result.audioBytes != null) {
        _setState(VoiceState.speaking);
        conversationLog.log('speak', '开始播报 TTS…');
        // 播放前停麦克风:Android 上 AudioRecord active 时与播放器抢占
        // AEC/SCO 通路,偶发导致"有音频但没声音"。播报期间唤醒已关闭,
        // 麦克风可安全暂停,播放完 finally 里 _resumeWakeListening 前会重启。
        await audio.stop();
        await _playTts(result.audioBytes!, result.audioFormat ?? 'wav');
        // 播放完重启麦克风采集,供 finally 恢复唤醒监听用。
        await audio.start();
      } else if (ttsEnabled && result.audioBytes == null) {
        conversationLog.log('speak', 'TTS 开启但后端未返回音频,跳过播报',
            error: true);
      }
    } catch (e) {
      debugPrint('[VoiceAssistant] conversation error: $e');
      conversationLog.log('error', '对话异常: $e', error: true);
    } finally {
      _userText = '';
      _replyText = '';
      _robotState = null;
      audio.externalLevel = false;
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
      conversationLog.log('end', '一轮对话结束,回到 idle');
      // 恢复唤醒监听,等待下一次唤醒。
      await _resumeWakeListening();
    }
  }

  /// 播放 TTS 音频(WAV/MP3 字节)。播放期间用音频自身的音量包络驱动
  /// [audio.level],让虚拟宠物嘴部随语音张合。WAV 可精确计算包络,
  /// 其它格式退化为轻微的合成张合。
  ///
  /// 关键:播放前先停麦克风采集,播放完再恢复。否则 AudioRecord 处于
  /// active 时启动播放器,Android 的 AEC(回声消除)/蓝牙 SCO 路由会与
  /// 播放器抢占音频通路,偶发导致"有音频但没声音"。录音停了不影响功能:
  /// 播报期间唤醒本就处于关闭态(见 _runConversation 开头 wakeWord.stop)。
  Future<void> _playTts(Uint8List bytes, String format) async {
    // 接管 level,避免被麦克风采集的实时音量覆盖。
    audio.externalLevel = true;
    final envelope = format == 'wav' ? _wavEnvelope(bytes) : const <double>[];
    Timer? ticker;
    var idx = 0;
    if (envelope.isNotEmpty) {
      ticker = Timer.periodic(const Duration(milliseconds: 60), (_) {
        audio.level.value = idx < envelope.length ? envelope[idx] : 0.0;
        idx++;
      });
    }
    // 监听播放器状态/日志,便于排查"收到音频但没播放"。
    StreamSubscription? stateSub;
    StreamSubscription? logSub;
    stateSub = _player.onPlayerStateChanged.listen((st) {
      debugPrint('[VoiceAssistant] player state: $st');
    });
    logSub = _player.onLog.listen((msg) {
      debugPrint('[VoiceAssistant] player log: $msg');
    });
    try {
      await _player.stop();
      debugPrint('[VoiceAssistant] playing tts ${bytes.length} bytes '
          'format=$format envelope=${envelope.length}');
      await _player.setReleaseMode(ReleaseMode.stop);
      await _player.setVolume(1.0);
      await _player.play(BytesSource(
        bytes,
        mimeType: format == 'mp3' ? 'audio/mpeg' : 'audio/wav',
      ));
      // 等播放结束;用包络时长(或固定上限)兜底,避免回调缺失时卡死。
      // 注意:不用 .first.timeout —— broadcast stream 的 first 在二次调用时
      // 类型推断会出错(Null 不是 FutureOr<AudioEvent>),改用 Completer+Timer
      // 显式管理,类型安全且可控。
      final fallback = envelope.isNotEmpty
          ? Duration(milliseconds: envelope.length * 60 + 800)
          : const Duration(seconds: 20);
      final done = Completer<void>();
      final completeSub = _player.onPlayerComplete.listen((_) {
        if (!done.isCompleted) done.complete();
      });
      final timer = Timer(fallback, () {
        if (!done.isCompleted) {
          debugPrint('[VoiceAssistant] player onPlayerComplete timeout '
              '(${fallback.inSeconds}s) — 回调未触发');
          conversationLog.log('speak',
              '播放完成回调超时(${fallback.inSeconds}s),可能未正常播放',
              error: true);
          done.complete();
        }
      });
      await done.future;
      timer.cancel();
      completeSub.cancel();
      conversationLog.log('speak', '播报完成');
    } catch (e) {
      debugPrint('[VoiceAssistant] tts play failed: $e');
      conversationLog.log('speak', '播放异常: $e', error: true);
    } finally {
      stateSub.cancel();
      logSub.cancel();
      ticker?.cancel();
      audio.level.value = 0.0;
    }
  }

  /// 从 WAV 字节计算逐 60ms 窗口的 RMS 音量包络(归一化 0..1)。
  /// 读取头部 sampleRate(TTS 输出常为 22050),跳过标准 44 字节头取 PCM16。
  List<double> _wavEnvelope(Uint8List wav, {int windowMs = 60}) {
    if (wav.length < 46) return const [];
    final bd = ByteData.sublistView(wav);
    var sampleRate = 22050;
    try {
      final sr = bd.getUint32(24, Endian.little);
      if (sr > 0 && sr <= 96000) sampleRate = sr;
    } catch (_) {}
    const headerLen = 44;
    final frames = (wav.length - headerLen) ~/ 2;
    if (frames <= 0) return const [];
    final windowSamples =
        (sampleRate * windowMs / 1000).round().clamp(1, frames);
    final out = <double>[];
    for (var i = 0; i < frames; i += windowSamples) {
      final end = math.min(i + windowSamples, frames);
      var sumSq = 0.0;
      for (var j = i; j < end; j++) {
        final s = bd.getInt16(headerLen + j * 2, Endian.little);
        sumSq += s * s;
      }
      final n = end - i;
      final rms = n > 0 ? math.sqrt(sumSq / n) : 0.0;
      out.add((rms / 6000).clamp(0.0, 1.0));
    }
    return out;
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
    _player.dispose();
    pophie.dispose();
    audio.dispose();
    wakeWord.dispose();
    speech.dispose();
    chat.dispose();
    super.dispose();
  }
}
