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

  /// 本地即时反馈音播放器(独立实例,避免与 TTS 播放器抢占)。
  /// 用户说完话进入 thinking 时立即播放一个短促的"嗯/好的",
  /// 填补 STT+LLM 的等待空白;后端 TTS 就绪前会被 stopFeedback() 抢占停掉。
  final AudioPlayer _feedbackPlayer = AudioPlayer();
  bool _feedbackPlaying = false;

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

  // —— 主动消息轮询(文档 §3.8)——
  /// 轮询定时器:总开关开启期间每 5s 拉一次 /api/proactive_messages。
  Timer? _proactiveTimer;
  /// 上次拉到的最大消息 id(增量游标,作为下次 since_id)。
  int _lastProactiveId = 0;
  /// 对话进行中收到的主动消息暂存队列,idle 后补播(不打断当前对话)。
  final List<ProactiveMessage> _proactiveQueue = [];

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
    // 启动主动消息轮询(文档 §3.8):每 5s 拉取服务端到点的提醒/tick 决策。
    // 跟随 start/stop 生命周期,关闭总开关即停止轮询。
    _startProactivePolling();
    notifyListeners();
  }

  /// 停止运行:中止当前对话、释放麦克风、停唤醒。幂等。
  Future<void> stop() async {
    if (!_running) return;
    _running = false;
    // 停止主动消息轮询,清空待播队列(关闭总开关即不再主动播报)。
    _stopProactivePolling();
    _proactiveQueue.clear();
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
      Uint8List? wav;
      if (pcm == null || pcm.isEmpty) {
        debugPrint('[VoiceAssistant] no speech captured');
        conversationLog.log('listen', '未采集到语音(VAD 无输入或超时)');
        // 不直接 return:即使没语音,表情/手势等感知数据仍可能是重要输入
        // (文档 §2.5:纯表情/抚摸也是合法输入)。在下方判断是否有感知要发。
      } else {
        wav = PophieClient.pcm16ToWav(pcm);
        conversationLog.log('listen',
            '已采集语音 ${pcm.length} 字节 (PCM) → WAV ${wav.length} 字节');
      }

      // thinking:整段上传后端。有语音带 wavBytes,无语音但有感知仍发送。
      // 既无语音也无感知 → 跳过(没有有效输入)。
      final perception = perceptionProvider?.call();
      final userId = userIdProvider?.call();
      final hasPerception = perception != null && !perception.isEmpty;
      if (wav == null && !hasPerception) {
        conversationLog.log('think', '无语音且无感知数据,跳过请求');
        return;
      }

      _setState(VoiceState.thinking);
      // 即时反馈:进入 thinking 立即播放本地"嗯/好的",填补 STT+LLM 等待空白。
      // 后端 TTS 就绪时会在 speaking 前 stopFeedback() 抢占停掉。
      if (ttsEnabled) unawaited(_playFeedback());
      conversationLog.log('think',
          '上传 Pophie /api/chat (skipTts=${!ttsEnabled}'
          '${wav == null ? ', 纯感知无语音' : ''})…');
      PophieChatResult result;
      try {
        result = await pophie.chat(
          wavBytes: wav,
          perception: perception,
          userId: userId,
          // 优先请求内嵌 TTS(省一次网络请求);后端 inline_tts=false 或合成
          // 失败时 audio 为 null,下方兜底再调 /api/tts。
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
      // 带 skipTts 实际值,便于区分"无音频"是请求跳过了还是后端没返回。
      final audioInfo = result.audioBytes != null
          ? '音频 ${result.audioBytes!.length} 字节 (${result.audioFormat ?? 'wav'})'
          : '无音频 (skipTts=${!ttsEnabled})';
      conversationLog.log('think',
          '后端返回: STT="${result.sttText}" | LLM="${result.text}" | '
          'robotState=${result.robotState} | $audioInfo');

      // STT 空 / LLM 失败:后端返回静默(空 text),端侧保持安静,不播报。
      if (result.isSilent) {
        debugPrint('[VoiceAssistant] silent reply (empty STT or LLM fail)');
        conversationLog.log('think',
            '静默回复(STT 空或 LLM 失败),不播报', error: true);
        await stopFeedback();
        return; // finally 会回到 idle 并恢复唤醒监听
      }

      _replyText = result.text;
      notifyListeners();

      // speaking:优先用内嵌音频(省一次请求);没有时兜底调 /api/tts。
      Uint8List? ttsBytes = result.audioBytes;
      String ttsFormat = result.audioFormat ?? 'wav';
      if (ttsEnabled && result.text.isNotEmpty) {
        if (ttsBytes != null) {
          conversationLog.log('speak',
              '使用内嵌音频: ${ttsBytes.length} 字节 ($ttsFormat)');
        } else {
          // 后端未内嵌音频(inline_tts=false 或合成失败),兜底调 /api/tts。
          conversationLog.log('speak', '内嵌音频为空,兜底调 /api/tts…');
          try {
            final t = await pophie.tts(result.text);
            ttsBytes = t.bytes;
            ttsFormat = t.format;
            conversationLog.log('speak',
                '/api/tts 成功: ${ttsBytes.length} 字节 ($ttsFormat)');
          } catch (e) {
            debugPrint('[VoiceAssistant] /api/tts failed: $e');
            conversationLog.log('speak', '/api/tts 失败: $e', error: true);
          }
        }
      }
      if (ttsEnabled && ttsBytes != null) {
        // 抢占停掉 thinking 阶段的即时反馈音,避免与后端 TTS 撞音。
        await stopFeedback();
        _setState(VoiceState.speaking);
        conversationLog.log('speak', '开始播报…');
        // 播放前停麦克风:Android 上 AudioRecord active 时与播放器抢占
        // AEC/SCO 通路,偶发导致"有音频但没声音"。
        await audio.stop();
        await playTts(ttsBytes, ttsFormat);
        await audio.start();
      } else if (ttsEnabled && ttsBytes == null) {
        conversationLog.log('speak', '无音频可播(合成失败)', error: true);
      }
    } catch (e) {
      debugPrint('[VoiceAssistant] conversation error: $e');
      conversationLog.log('error', '对话异常: $e', error: true);
    } finally {
      // 保险:任何路径(异常/超时/静默)结束时确保反馈音停掉。
      await stopFeedback();
      _userText = '';
      _replyText = '';
      _robotState = null;
      audio.externalLevel = false;
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
      conversationLog.log('end', '一轮对话结束,回到 idle');
      // 一轮对话结束回到 idle:若有排队的主动消息(对话期间收到的),补播。
      // 不 await,放后台播;它自己会再次回到 idle,触发下一条。
      if (_proactiveQueue.isNotEmpty) {
        final next = _proactiveQueue.removeAt(0);
        _playProactive(next);
      }
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
  ///
  /// public 以便主动消息播报路径复用(同一播放器/包络/嘴型/日志)。
  /// 调用方负责停麦(audio.stop)与启麦(audio.start),本方法只管播放。
  Future<void> playTts(Uint8List bytes, String format) async {
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

  /// 即时反馈音资源(短促的"嗯/好的",填补 thinking 等待空白)。
  /// 文件放在 assets/sounds/ 下;缺失时静默跳过,不影响主流程。
  static const List<String> _feedbackAssets = [
    'sounds/feedback_1.mp3',
    'sounds/feedback_2.mp3',
    'sounds/feedback_3.mp3',
  ];

  /// 进入 thinking 时立即播放一个随机反馈音,降低用户等待焦虑。
  /// 音量较低(0.5),作为过渡音;后端 TTS 就绪时由 stopFeedback 抢占停掉。
  /// 失败(无文件/播放错误)静默忽略,不阻断对话主流程。
  Future<void> _playFeedback() async {
    try {
      final asset = _feedbackAssets[math.Random().nextInt(_feedbackAssets.length)];
      await _feedbackPlayer.setReleaseMode(ReleaseMode.stop);
      await _feedbackPlayer.setVolume(0.5);
      await _feedbackPlayer.play(AssetSource(asset));
      _feedbackPlaying = true;
      debugPrint('[VoiceAssistant] feedback playing: $asset');
    } catch (e) {
      // 资源缺失或播放失败:静默跳过,不影响对话。
      _feedbackPlaying = false;
      debugPrint('[VoiceAssistant] feedback skipped: $e');
    }
  }

  /// 抢占停掉反馈音(后端 TTS 就绪时调用)。
  Future<void> stopFeedback() async {
    if (!_feedbackPlaying) return;
    try {
      await _feedbackPlayer.stop();
    } catch (_) {/* ignore */}
    _feedbackPlaying = false;
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

  // ============== 主动消息轮询 + 播报(文档 §3.8 + §3.6) ==============
  //
  // 场景:用户说"1分钟后提醒我喝水",后端后台登记提醒(defer_side_tasks,
  // /api/chat 的 scheduled_reminders 恒为空)。到点后服务端把提醒写入
  // /api/proactive_messages,客户端轮询拉到 content,调 /api/tts 合成播放。
  // 冲突处理:对话中(idle 外)收到 → 入队,对话结束 finally 补播。

  void _startProactivePolling() {
    _proactiveTimer?.cancel();
    // 文档 §3.8 建议每 3-10s 一次;5s 平衡及时性与服务器压力。
    _proactiveTimer =
        Timer.periodic(const Duration(seconds: 5), (_) => _pollProactive());
    debugPrint('[VoiceAssistant] proactive polling started');
  }

  void _stopProactivePolling() {
    _proactiveTimer?.cancel();
    _proactiveTimer = null;
  }

  /// 轮询一次主动消息。失败静默(5s 一次的网络抖动不刷屏),只在真正播放时记日志。
  Future<void> _pollProactive() async {
    if (!_running) return;
    try {
      final result = await pophie.fetchProactiveMessages(sinceId: _lastProactiveId);
      if (result.items.isEmpty) return;
      _lastProactiveId = result.lastId;
      for (final msg in result.items) {
        _enqueueOrPlay(msg);
      }
    } catch (e) {
      debugPrint('[VoiceAssistant] proactive poll error: $e');
    }
  }

  /// 收到一条主动消息:空闲立即播,对话中入队等空闲。
  void _enqueueOrPlay(ProactiveMessage msg) {
    if (!_running) return;
    if (_state == VoiceState.idle) {
      _playProactive(msg); // 空闲,立即播(不 await,放后台)
    } else {
      _proactiveQueue.add(msg);
      conversationLog.log('proactive',
          '收到提醒(等待对话结束): ${msg.content}');
    }
  }

  /// 播报一条主动消息:合成 TTS → 停麦 → 播放 → 启麦 → 回 idle。
  /// 仅应在 idle 态调用(由 _enqueueOrPlay 或 _runConversation 的 finally 保证)。
  Future<void> _playProactive(ProactiveMessage msg) async {
    if (!_running) return;
    try {
      _setState(VoiceState.speaking); // 复用 speaking 态驱动虚拟宠物嘴型
      _replyText = msg.content; // 复用气泡字幕字段,UI 无需改
      notifyListeners();
      conversationLog.log('proactive', '主动提醒: ${msg.content}');

      // proactive_messages 只回文本,必须单独调 /api/tts 合成。
      final t = await pophie.tts(msg.content);

      // 停麦 → 播放 → 启麦(消除 AEC/SCO 竞态,同 _runConversation 范式)。
      await audio.stop();
      await playTts(t.bytes, t.format);
      await audio.start();
    } catch (e) {
      debugPrint('[VoiceAssistant] proactive play failed: $e');
      conversationLog.log('proactive', '主动提醒播放失败: $e', error: true);
    } finally {
      _replyText = '';
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
      // 恢复唤醒监听(主动播报期间唤醒被停了,见 _playProactive 没有显式
      // stop 唤醒,但 _runConversation 开头会 stop;主动播报走的是独立路径,
      // 唤醒监听此刻应仍在跑 —— 若被中断这里也会重建)。
      await _resumeWakeListening();
    }
  }

  @override
  void dispose() {
    // stop() 是异步的,这里同步取消轮询,确保 dispose 后不会再触发回调。
    _proactiveTimer?.cancel();
    _proactiveTimer = null;
    stop();
    _player.dispose();
    _feedbackPlayer.dispose();
    pophie.dispose();
    audio.dispose();
    wakeWord.dispose();
    speech.dispose();
    chat.dispose();
    super.dispose();
  }
}
