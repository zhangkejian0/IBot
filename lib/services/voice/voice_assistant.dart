import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:audioplayers/audioplayers.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'audio_capture_service.dart';
import 'barge_in_detector.dart';
import 'chat_service.dart';
import 'conversation_logger.dart';
import 'pophie_client.dart';
import 'speech_service.dart';
import 'stt_stream_client.dart';
import 'streaming_tts_player.dart';
import 'voice_state.dart';
import 'wake_word_service.dart';

/// 触发来源标识 → 可读中文名(供交互日志 message 展示)。
///
/// 与网络日志(NetworkLogEntry.trigger)使用同一套短字符串值：
/// 端侧主动对话 wake/double_tap/gaze/manual，服务端主动播报 proactive:*。
const Map<String, String> _triggerLabels = {
  'wake': '唤醒词',
  'double_tap': '双击',
  'gaze': '注视',
  'manual': '手动',
  'proactive:welcome': '欢迎语',
  'proactive:reminder': '定时提醒',
  'proactive:living_loop': '主动陪伴',
  'proactive:poll': '主动消息轮询',
};

/// 把任意触发来源标识转成可读中文名(未知值原样返回)。
String triggerLabel(String source) =>
    _triggerLabels[source] ?? source;

/// 语音助手编排器:本地唤醒(KWS)+ 麦克风采集 + Pophie 后端对话。
/// 以一个有限状态机对外暴露 [state] / [userText] / [replyText] / [level]。
///
/// 一轮对话:唤醒/双击触发 → 聆听整段语音(静音 VAD) → 整段 WAV 上传
/// Pophie /api/chat(**skip_tts=true**,只拿文字/表情) → 调
/// /api/tts/stream(NDJSON)逐片喂 [StreamingTtsPlayer] 边收边播(首声≈500ms)。
/// 详见 docs/API对接文档.md 与 [_runConversation] / [_playStreamingTts]。
///
/// 流式播放稳定性:[StreamingTtsPlayer] 基于 flutter_pcm_sound,**无 native
/// FeedThread**(背压由原生 feed 回调驱动),规避 flutter_sound issue #508 的
/// SEGV。流式失败时回退批量 /api/tts + playTts。
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

  /// 流式 TTS 播放器:把 /api/tts/stream 的 PCM 分片逐帧喂给 AudioTrack,
  /// 首个分片到达即开口(首声延迟 ≈500ms)。基于 flutter_pcm_sound,无 native
  /// FeedThread,规避 SEGV(issue #508)。详见 [StreamingTtsPlayer]。
  /// 惰性初始化(初始化器不能引用实例成员 audio)。
  late final StreamingTtsPlayer _streamingTts = StreamingTtsPlayer(audio);

  // —— 打断(barge-in)——
  /// 打断检测器:TTS 播放时监听麦克风,用户开口即触发停止 TTS 重新聆听。
  /// 详见 [BargeInDetector](抗回声:高阈值+连续帧+冷却期)。
  late final BargeInDetector _bargeIn = BargeInDetector();

  /// 打断功能总开关。**默认关(半双工)**:播报时停麦,从物理上杜绝扬声器
  /// 回声被误判为"用户打断"导致的自激死循环(详见 [_runConversation] 注释
  /// 与 [BargeInDetector] 的回声局限说明)。即便开启,也受「打断后静默冷却 +
  /// 连续降级」保护([_resumeOrRelisten] 中 [AudioCaptureService.waitForSilence]
  /// + [_consecutiveBargeIns]),最多重听一次,不会无限循环。
  bool bargeInEnabled = false;

  /// 本轮是否因用户打断而中止播放(_runConversation finally 据此决定是否
  /// 立即重新聆听,而非只恢复唤醒监听)。
  bool _bargeInTriggered = false;

  /// 连续因打断而重听的次数(防回声自激死循环的降级计数)。每被打断一次 +1,
  /// 正常完成一轮(未被打断)清零。超过 [_maxBargeInRelistens] 则不再重听,
  /// 强制降级回唤醒监听。详见 [_resumeOrRelisten]。
  int _consecutiveBargeIns = 0;

  /// 允许的最大「打断后重听」次数。默认 1:打断 → 静默冷却 → 重听一次;若
  /// 该轮再被打断(极可能是回声而非真用户)则降级回唤醒监听,确保即便开启
  /// 打断也不会"打断→重听→又被回声打断"无限循环。
  static const int _maxBargeInRelistens = 1;

  /// 端侧感知上下文提供者:由 AppController 注入，读取当前人脸表情/身份/手势，
  /// 随对话发给后端(文档 §2.4)。
  PophiePerception Function()? perceptionProvider;

  /// 当前用户身份(认识我)，作为 user_id 回显/溯源。由 AppController 注入。
  String? Function()? userIdProvider;

  /// session_id 更新后回调(供 AppController 持久化以延续会话记忆)。
  Future<void> Function(String sessionId)? onSessionPersist;

  /// 一轮对话完成且有有效回复时回调(供 AppController 持久化到人物日志)。
  /// 携带本轮 STT 文本、机器人回复文本与响应的动作/表情状态。静默回复
  /// (STT 空或 LLM 失败)不触发。
  void Function({
    required String userText,
    required String replyText,
    String? robotState,
  })? onInteraction;

  /// 机器人最近一次回复携带的 FSM 状态(驱动虚拟形象，文档 §2.6)。
  String? _robotState;
  String? get robotState => _robotState;

  /// 当前轮对话的触发来源标识(wake/double_tap/gaze/manual/proactive:*)。
  /// 在 _onWake / _playProactive 入口设置，随每个网络请求透传给 PophieClient
  /// 写入网络日志；一轮结束 finally 清回 null，避免残留串到下一轮。
  String? _triggerSource;
  String? get triggerSource => _triggerSource;

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

  // —— 分阶段耗时埋点(排查「即问即答」延迟)——
  // 每轮对话开始时重置,各阶段用 [recordTiming] 记录,结束时 [logTimingSummary]
  // 汇总打印。阶段定义见 [_runConversation] 注释。
  _ConversationTimings? _timings;

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

  /// 是否启用**流式 STT 对话模式**(文档 §3.5.1 `WS /api/stt/stream`)。
  /// 开启(默认)后唤醒/触发即建立 WebSocket,边录边识别(partial/final),
  /// 多轮复用同一连接,识别完成走 `/api/chat/stream` + `/api/tts/stream`;
  /// 关闭则回退「整段录音 → `/api/chat` 批量 STT」的单轮模式([_runConversation])。
  bool streamingSttEnabled = true;

  /// 当前活跃的流式 STT 客户端(对话模式期间非空)。供 [stop] 主动关闭以打断
  /// `await sessionDone`,使流式对话循环及时退出。
  SttStreamClient? _activeStt;

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
    // 主动关闭流式 STT(若在对话模式中),触发 closed 事件让对话循环退出。
    try {
      await _activeStt?.close(sendEndFrame: true);
    } catch (_) {}
    await speech.stopSpeaking();
    try {
      await _streamingTts.release();
    } catch (_) {}
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
  /// [source] 为触发来源标识,用于网络/交互日志区分是谁触发的(默认 manual)。
  Future<void> triggerManually({String source = 'manual'}) async {
    if (!_running) return;
    _onWake(wakeWord.keyword, source: source);
  }

  /// 唤醒回调:进入对话主流程。
  /// idle → waking → listening → thinking → speaking → idle
  /// [source] 触发来源标识(wake=唤醒词 / double_tap / gaze / manual)，
  /// 透传给 PophieClient 写入网络日志，并体现在交互日志 message 里。
  void _onWake(String keyword, {String source = 'wake'}) {
    debugPrint('[VoiceAssistant] wake word: $keyword (source=$source)');
    _triggerSource = source;
    pophie.setTriggerSource(source);
    conversationLog.log('wake', '触发对话: ${triggerLabel(source)}'
        '${source == 'wake' ? '(唤醒词「$keyword」)' : ''}');
    if (streamingSttEnabled) {
      _runStreamingConversation();
    } else {
      _runConversation();
    }
  }

  /// 一轮对话:聆听整段语音 → 发 Pophie /api/chat(STT+LLM+TTS) → 播报。
  ///
  /// 与旧的分段式(ASR→LLM→TTS)不同:后端一次完成全部处理并回传文本、表情、
  /// FSM 状态与 TTS 音频(见 docs/API对接文档.md §3.4)。
  ///
  /// **回声自激防护**:默认半双工([bargeInEnabled]=false),播报期停麦,扬声器
  /// 回声无法进入录音通路 → 不会误触发打断 → 不会"打断→重听→又被回声打断"
  /// 死循环。即便开启打断,finally 块走 [_resumeOrRelisten]:静默冷却确认 +
  /// 连续打断降级([_consecutiveBargeIns] ≤ [_maxBargeInRelistens]),最多重听
  /// 一次,物理上不可能无限循环。
  Future<void> _runConversation() async {
    if (_state != VoiceState.idle) return; // 防重入
    // 启动本轮耗时埋点(排查「即问即答」延迟,见 _ConversationTimings)。
    _timings = _ConversationTimings();
    final t = _timings!;
    final wakeAt = t.startedAt; // 唤醒命中时刻(端到端延迟起点)
    try {
      // waking:短暂过渡。先暂停唤醒监听,避免把用户说的话再次当唤醒词。
      _setState(VoiceState.waking);
      await wakeWord.stop();
      t.wakeMs = DateTime.now().difference(wakeAt).inMilliseconds;

      // listening:采集一句完整语音(静音 VAD 自动结束)。
      _setState(VoiceState.listening);
      _userText = '';
      _replyText = '';
      _robotState = null;
      notifyListeners();
      final listenSw = Stopwatch()..start();
      final vadResult = await audio.captureUtterance();
      final pcm = vadResult?.pcm;
      t.vadSilenceMs = vadResult?.silenceMs; // VAD 静音判定时长(刚性延迟)
      listenSw.stop();
      t.listenMs = listenSw.elapsedMilliseconds;
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
      conversationLog.log('listen',
          '聆听耗时 ${t.listenMs}ms${t.vadSilenceMs != null ? "(其中静音判定 ${t.vadSilenceMs}ms)" : ""}');

      // thinking:整段上传后端。有语音带 wavBytes,无语音但有感知仍发送。
      // 既无语音也无感知 → 跳过(没有有效输入)。
      final perception = perceptionProvider?.call();
      final userId = userIdProvider?.call();
      final hasPerception = perception != null && !perception.isEmpty;
      if (wav == null && !hasPerception) {
        conversationLog.log('think', '无语音且无感知数据,跳过请求');
        return;
      }

      // 打印本轮实际发往后端的感知上下文(尤其是物体/手持/场景),
      // 便于核对「物体是否真的发出去了」。空则明确标注。
      conversationLog.log(
        'think',
        hasPerception
            ? '本轮感知 → ${jsonEncode(perception.toJson())}'
            : '本轮无感知数据(物体/表情/身份均为空)',
      );

      _setState(VoiceState.thinking);
      conversationLog.log('think',
          '上传 Pophie /api/chat (skipTts=true 流式优先'
          '${wav == null ? ', 纯感知无语音' : ''})…');
      PophieChatResult result;
      final chatSw = Stopwatch()..start();
      try {
        result = await pophie.chat(
          wavBytes: wav,
          perception: perception,
          userId: userId,
          // 始终 skip_tts=true:不等待内嵌整段音频,拿到文字/表情/voice 后立即
          // 调 /api/tts/stream 流式合成(文档 §5.1 推荐做法)。是否播放由
          // 后续 _playStreamingTts 决定,与 ttsEnabled 解耦。
          skipTts: true,
        );
      } catch (e) {
        chatSw.stop();
        t.chatMs = chatSw.elapsedMilliseconds;
        debugPrint('[VoiceAssistant] chat request failed: $e');
        conversationLog.log('think',
            'Pophie 请求失败(${t.chatMs}ms): $e', error: true);
        return; // finally 会回到 idle 并恢复唤醒监听
      }
      chatSw.stop();
      t.chatMs = chatSw.elapsedMilliseconds;
      conversationLog.log('think',
          '/api/chat 耗时 ${t.chatMs}ms(STT+LLM)');

      if (result.sessionId != null) {
        await onSessionPersist?.call(result.sessionId!);
      }

      _userText = result.sttText;
      _robotState = result.robotState;
      notifyListeners();

      // 记录后端返回详情:STT 文本、LLM 回复、robot_state、voice(供流式透传)。
      conversationLog.log('think',
          '后端返回: STT="${result.sttText}" | LLM="${result.text}" | '
          'robotState=${result.robotState} | '
          'voice=${result.voice ?? "(默认)"}');

      // STT 空 / LLM 失败:后端返回静默(空 text),端侧保持安静,不播报。
      if (result.isSilent) {
        debugPrint('[VoiceAssistant] silent reply (empty STT or LLM fail)');
        conversationLog.log('think',
            '静默回复(STT 空或 LLM 失败),不播报', error: true);
        return; // finally 会回到 idle 并恢复唤醒监听
      }

      _replyText = result.text;
      notifyListeners();

      // 上报本轮交互到人物日志(持久化分析用):有效回复才记。
      onInteraction?.call(
        userText: result.sttText,
        replyText: result.text,
        robotState: result.robotState,
      );

      // speaking:流式合成(文档 §3.6.1)收齐 PCM 后用 playTts 整段播放。
      // ttsEnabled=false 时仅显示文字。
      if (ttsEnabled && result.text.isNotEmpty) {
        _setState(VoiceState.speaking);
        conversationLog.log('speak', '流式合成开始…');
        await _playStreamingTts(
          text: result.text,
          voice: result.voice,
          fallbackFormat: result.audioFormat,
          fallbackBytes: result.audioBytes,
        );
      }
    } catch (e) {
      debugPrint('[VoiceAssistant] conversation error: $e');
      conversationLog.log('error', '对话异常: $e', error: true);
    } finally {
      // 汇总本轮各阶段耗时(排查「即问即答」)。
      _logTimingSummary();
      _timings = null;
      _userText = '';
      _replyText = '';
      _robotState = null;
      _triggerSource = null;
      pophie.setTriggerSource(null);
      audio.externalLevel = false;
      audio.level.value = 0.0;
      final wasBarged = _bargeInTriggered;
      _bargeInTriggered = false;
      // 连续打断计数:被打断则自增(供 _resumeOrRelisten 降级判定),
      // 正常完成一轮则清零(用户确实在正常对话)。
      if (wasBarged) {
        _consecutiveBargeIns++;
      } else {
        _consecutiveBargeIns = 0;
      }
      _setState(VoiceState.idle);
      conversationLog.log('end', '一轮对话结束,回到 idle');
      // 一轮对话结束回到 idle:若有排队的主动消息(对话期间收到的),补播。
      // 不 await,放后台播;它自己会再次回到 idle,触发下一条。
      // (被打断时不补播:用户要说话,优先聆听。)
      if (_proactiveQueue.isNotEmpty && !wasBarged) {
        final next = _proactiveQueue.removeAt(0);
        _playProactive(next);
      } else {
        // _playStreamingTts 播放时停了麦(barge-in 模式)或播完停麦(回退模式),
        // 这里重启麦克风,恢复唤醒监听与聆听的前置条件。
        await audio.start();
        // 打断(barge-in)后不直接立即重听,而是走 _resumeOrRelisten:
        // 静默冷却确认 + 连续打断降级,避免"打断→重听→又被回声打断"死循环。
        // 未被打断则正常恢复唤醒监听。
        if (wasBarged) {
          await _resumeOrRelisten(_runConversation);
        } else {
          await _resumeWakeListening();
        }
      }
    }
  }

  // ================= 流式 STT 对话模式(文档 §3.5.1 + §3.4.1 + §3.6.1) =========
  //
  // 与上面的单轮批量模式([_runConversation])相对:唤醒/触发后建立
  // [SttStreamClient] WebSocket,持续把麦克风 PCM 分片上行做实时识别;每个
  // `final`(text 非空)即一句用户发言,经 `/api/chat/stream` 流式拿回复、每个
  // `speak` 分句走 `/api/tts/stream` 边收边播,播完回到聆听(同一 WS 多轮复用)。
  // 退出:服务端 `session_end`(空闲超时)/`error`、总开关 [stop]、或 WS 关闭。

  /// 流式对话主循环(端侧推荐,见文件顶部小节注释)。
  ///
  /// **半双工**:整轮 LLM + TTS 期间停麦并暂停上行 chunk(避免扬声器回声被
  /// 误识别;当前版本不支持 barge-in)。播完重启麦克风继续聆听。
  ///
  /// **连接失败降级**:WS 建连失败时回退一轮批量 [_runConversation],保证可用性。
  Future<void> _runStreamingConversation() async {
    if (_state != VoiceState.idle) return; // 防重入
    _setState(VoiceState.waking);
    await wakeWord.stop();

    final stt = SttStreamClient(pophie.config.baseUrl);
    _activeStt = stt;
    final sessionDone = Completer<void>();
    StreamSubscription<Uint8List>? chunkSub;
    StreamSubscription<SttEvent>? evSub;
    var listening = false; // 是否在向 WS 上行 chunk(聆听相位)
    var handlingTurn = false; // 是否正在处理一轮(LLM + TTS,期间忽略识别事件)
    var fallbackToBatch = false; // 连接失败 → finally 里降级跑一轮批量
    // —— 残留/重复 final 过滤(防「没说话却自问自答」死循环)——
    // 连续会话里,一轮结束恢复上行后,服务端常把上一段缓冲尾音补发成 final。
    // 这种 final **不属于新的聆听窗口**:本窗口内没有新的 partial。故只接受
    // 「本窗口内收到过 partial」的 final;并额外抑制短时间内重复的相同文本。
    var sawPartialSinceListen = false; // 当前聆听窗口内是否收到过 partial
    String? lastHandledText; // 上一条已处理的 final 文本(去重)
    DateTime? lastHandledAt; // 上一条已处理的时刻(去重时间窗)

    void finishSession() {
      if (!sessionDone.isCompleted) sessionDone.complete();
    }

    try {
      // 麦克风需在跑(唤醒期间通常已开)。
      if (!audio.isRunning) await audio.start();

      conversationLog.log('listen', '连接流式 STT WebSocket…');
      try {
        await stt.connect();
      } catch (e) {
        conversationLog.log('error', '流式 STT 连接失败,本轮回退批量模式: $e',
            error: true);
        fallbackToBatch = true;
        return; // 跳到 finally
      }

      evSub = stt.events.listen((ev) {
        switch (ev.type) {
          case SttEventType.meta:
            conversationLog.log('listen',
                'STT meta: silenceCommit=${ev.silenceCommitMs}ms '
                'idle=${ev.conversationIdleSec}s');
            break;
          case SttEventType.ready:
            listening = true;
            sawPartialSinceListen = false; // 新聆听窗口
            _userText = '';
            _replyText = '';
            _robotState = null;
            _setState(VoiceState.listening);
            conversationLog.log('listen', 'STT ready,开始聆听');
            notifyListeners();
            break;
          case SttEventType.partial:
            if (handlingTurn) break;
            final p = (ev.text ?? '').trim();
            // 非空 partial 才视为「本窗口有真实新语音」(用于门控 final)。
            if (p.isNotEmpty) sawPartialSinceListen = true;
            // 中间结果驱动「聆听中」字幕;勿触发 LLM。
            _userText = ev.text ?? '';
            notifyListeners();
            break;
          case SttEventType.finalResult:
            if (handlingTurn) break;
            final t = (ev.text ?? '').trim();
            if (t.isEmpty) break; // 空 final:继续聆听,不刷新
            // 残留 final 过滤①:本聆听窗口内没收到过 partial → 多半是上一段
            // 缓冲尾音被补发的残留 final,丢弃(否则会「没说话却自问自答」)。
            if (!sawPartialSinceListen) {
              conversationLog.log('listen',
                  '忽略无 partial 的 final(疑似残留尾音): "$t"', error: true);
              break;
            }
            // 残留 final 过滤②:与上一条相同文本且间隔很短 → 视为重复,丢弃。
            final now = DateTime.now();
            if (t == lastHandledText &&
                lastHandledAt != null &&
                now.difference(lastHandledAt!).inSeconds < 8) {
              conversationLog.log('listen',
                  '忽略重复 final(疑似残留): "$t"', error: true);
              break;
            }
            lastHandledText = t;
            lastHandledAt = now;
            handlingTurn = true;
            listening = false; // 暂停上行,进入 thinking/speaking
            sawPartialSinceListen = false; // 本句已消费
            // 异步处理本轮;完成后恢复聆听(若会话仍在)。
            () async {
              try {
                await _handleStreamingTurn(t, ev.voice);
              } catch (e) {
                conversationLog.log('error', '处理对话轮异常: $e', error: true);
              } finally {
                handlingTurn = false;
                if (_running && !sessionDone.isCompleted) {
                  _userText = '';
                  _replyText = '';
                  // 重新计时去重窗口起点,并要求下一句必须有新的 partial。
                  lastHandledAt = DateTime.now();
                  sawPartialSinceListen = false;
                  listening = true;
                  _setState(VoiceState.listening);
                  conversationLog.log('listen', '回到聆听(多轮复用同一 WS)');
                  notifyListeners();
                }
              }
            }();
            break;
          case SttEventType.sessionEnd:
            conversationLog.log(
                'end', '服务端结束会话: ${ev.message ?? "空闲超时"}');
            finishSession();
            break;
          case SttEventType.error:
            conversationLog.log('error', 'STT 错误: ${ev.message}', error: true);
            finishSession();
            break;
          case SttEventType.closed:
            finishSession();
            break;
        }
      });

      stt.start(sampleRate: 16000);

      // 把麦克风 PCM 分片转发到 WS(仅聆听相位、未在处理轮、会话未结束时)。
      chunkSub = audio.audioStream.listen((bytes) {
        if (listening && !handlingTurn && !sessionDone.isCompleted) {
          stt.sendChunk(bytes);
        }
      });

      // 等待会话结束(session_end / error / closed / stop 关闭)。
      await sessionDone.future;
    } catch (e) {
      conversationLog.log('error', '流式对话异常: $e', error: true);
    } finally {
      _activeStt = null;
      await chunkSub?.cancel();
      await evSub?.cancel();
      await stt.close(sendEndFrame: true);
      _userText = '';
      _replyText = '';
      _robotState = null;
      _triggerSource = null;
      pophie.setTriggerSource(null);
      audio.externalLevel = false;
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
    }

    // —— 会话收尾(放在 try/finally 之外,避免在 finally 中改变控制流)——
    // 连接失败:降级跑一轮批量对话(它会自行恢复唤醒监听 / 补播主动消息)。
    if (fallbackToBatch && _running) {
      if (!audio.isRunning) await audio.start();
      await _runConversation();
      return;
    }

    conversationLog.log('end', '流式对话结束,回到 idle');
    // 对话期间排队的主动消息补播;否则恢复唤醒监听。
    if (_proactiveQueue.isNotEmpty && _running) {
      final next = _proactiveQueue.removeAt(0);
      if (!audio.isRunning) await audio.start();
      _playProactive(next);
    } else {
      if (_running && !audio.isRunning) await audio.start();
      await _resumeWakeListening();
    }
  }

  /// 处理流式对话的一轮:STT `final` 文本 → `/api/chat/stream` 流式回复 →
  /// 每个 `speak` 分句走 `/api/tts/stream` 边收边播(文档 §3.4.1 + §3.6.1)。
  ///
  /// 半双工:进入即停麦,播完恢复麦克风(供下一轮聆听)。流式聊天失败时回退
  /// 批量 `/api/chat`。[voice] 为 STT `final` 携带的语音侧道,写入感知由
  /// [perceptionProvider] 统一构造时无法注入(端侧感知独立),此处仅记录日志。
  Future<void> _handleStreamingTurn(
      String text, Map<String, dynamic>? voice) async {
    _userText = text;
    _replyText = '';
    notifyListeners();
    conversationLog.log('listen',
        '识别完成: "$text"${voice != null ? " | voice=$voice" : ""}');

    _setState(VoiceState.thinking);
    // 半双工:停麦,避免扬声器回声进入录音通路被误识别(当前不支持 barge-in)。
    await audio.stop();

    final perception = perceptionProvider?.call();
    final userId = userIdProvider?.call();

    // speak 分句队列:chatStream 边生成边入队,播放 worker 边取边播(降首句延迟)。
    final speakCtrl = StreamController<String>();
    final Future<void> playFuture = ttsEnabled
        ? _speakStreaming(speakCtrl.stream)
        : speakCtrl.stream.drain<void>();

    var anySpeak = false;
    String replyText = '';
    String? robotState;
    try {
      conversationLog.log('think', 'POST /api/chat/stream (流式)…');
      final chatSw = Stopwatch()..start();
      final result = await pophie.chatStream(
        text: text,
        perception: perception,
        userId: userId,
        skipTts: true,
        onSpeak: (seg) {
          final s = seg.trim();
          if (s.isEmpty) return;
          anySpeak = true;
          conversationLog.log('speak', '分句: $s');
          if (!speakCtrl.isClosed) speakCtrl.add(seg);
        },
      );
      chatSw.stop();
      replyText = result.text;
      robotState = result.robotState;
      conversationLog.log('think',
          '/api/chat/stream 完成 ${chatSw.elapsedMilliseconds}ms | '
          'LLM="$replyText" | robotState=$robotState');
      if (result.sessionId != null) {
        await onSessionPersist?.call(result.sessionId!);
      }
      // 服务端未分句(无 speak)但有完整回复:兜底整段播一次。
      if (!anySpeak &&
          ttsEnabled &&
          replyText.trim().isNotEmpty &&
          !speakCtrl.isClosed) {
        speakCtrl.add(replyText);
      }
    } catch (e) {
      conversationLog.log('think', '流式聊天失败,回退批量 /api/chat: $e',
          error: true);
      try {
        final r = await pophie.chat(
          text: text,
          perception: perception,
          userId: userId,
          skipTts: true,
        );
        replyText = r.text;
        robotState = r.robotState;
        if (r.sessionId != null) await onSessionPersist?.call(r.sessionId!);
        if (ttsEnabled && r.text.trim().isNotEmpty && !speakCtrl.isClosed) {
          speakCtrl.add(r.text);
        }
      } catch (e2) {
        conversationLog.log('think', '批量回退也失败: $e2', error: true);
      }
    } finally {
      await speakCtrl.close();
    }

    // 等待全部分句播完(队列 + 原生缓冲排空)。
    await playFuture;

    _robotState = robotState;
    if (replyText.trim().isNotEmpty) {
      _replyText = replyText;
      notifyListeners();
      onInteraction?.call(
        userText: text,
        replyText: replyText,
        robotState: robotState,
      );
    } else {
      conversationLog.log('think', '静默回复(空 STT 或 LLM 失败),不播报',
          error: true);
    }

    // 恢复麦克风供下一轮聆听(调用方在 finally 把 listening 置回 true)。
    audio.externalLevel = false;
    audio.level.value = 0.0;
    if (_running) await audio.start();
  }

  /// 顺序播放流式 `speak` 分句:对每段调 `/api/tts/stream`,复用同一
  /// [StreamingTtsPlayer] 跨分句无缝衔接(首段 `meta` 启动播放器,后续分句持续
  /// 喂入,全部播完后统一 markFeedingDone + waitForDone + release)。
  ///
  /// 调用前调用方已停麦(半双工);本方法只负责播放,不管理麦克风。
  Future<void> _speakStreaming(Stream<String> segments) async {
    var started = false;
    try {
      await for (final seg in segments) {
        if (!_running) break;
        if (_state != VoiceState.speaking) _setState(VoiceState.speaking);
        try {
          await pophie.ttsStream(
            seg,
            onMeta: (format, sampleRate) async {
              if (!started) {
                await _streamingTts.start(sampleRate);
                started = true;
              }
            },
            onChunk: (pcm16) {
              if (started) _streamingTts.feedChunk(pcm16);
            },
            onDone: (_) {},
          );
        } catch (e) {
          conversationLog.log('speak', '分句合成失败,跳过: $e', error: true);
        }
      }
    } finally {
      if (started) {
        _streamingTts.markFeedingDone();
        await _streamingTts.waitForDone();
        await _streamingTts.release();
      }
    }
  }

  /// 打印本轮对话各阶段耗时汇总(排查「即问即答」延迟)。
  /// 在 _runConversation 的 finally 调用,一次对话一条汇总,便于定位瓶颈。
  void _logTimingSummary() {
    final t = _timings;
    if (t == null) return;
    final total = DateTime.now().difference(t.startedAt).inMilliseconds;
    final msg = '耗时汇总: ${t.toString()} | 总轮次=${total}ms';
    conversationLog.log('timing', msg);
    debugPrint('[VoiceAssistant] $msg');
  }

  /// 流式合成并边收边播(文档 §3.6.1)。
  ///
  /// 流程:
  /// 1. **先停麦**:`audio.stop()` 在首个分片到达前完成,避免 Android 上
  ///    AudioRecord active 时与播放器抢占 AEC/SCO 通路(同 [playTts] 注释)。
  /// 2. 调 [PophieClient.ttsStream] 拉取 NDJSON 流:
  ///    - `meta` 到达 → [StreamingTtsPlayer.start](sampleRate) 初始化播放器;
  ///    - `chunk` 到达 → [StreamingTtsPlayer.feedChunk] 入队并尝试立即喂入,
  ///      **首个 chunk 即开口**(首声延迟 ≈ LLM 结束 + DashScope 首包 ~500ms);
  ///    - `done` → 记录首包延迟,标记喂入结束。
  /// 3. 全部喂完 → [StreamingTtsPlayer.waitForDone](等队列+原生缓冲排空)
  ///    → [StreamingTtsPlayer.release] → `audio.start()` 重启麦克风。
  ///
  /// **稳定性**:[StreamingTtsPlayer] 基于 flutter_pcm_sound,**无 native FeedThread**
  /// (背压由原生 feed 回调驱动,不存在后台线程撞已释放 track 的 SEGV,见类注释),
  /// 彻底规避 flutter_sound issue #508。
  ///
  /// **回退**:网络错误 / 非 2xx / 流内 `error` 行 → [ttsStream] 抛异常,
  /// 本方法 catch 后改走批量 [pophie.tts] + [playTts](整段缓冲再播),
  /// 保证可用性。若 [fallbackBytes] 非空(/api/chat 意外返回了内嵌音频),
  /// 直接用它,省一次请求。
  ///
  /// 主动消息路径([_playProactive])也复用本方法,其 voice 为 null(用默认情感)。
  ///
  /// [text] 必须非空;调用前已置 speaking 态。
  Future<void> _playStreamingTts({
    required String text,
    Map<String, dynamic>? voice,
    Uint8List? fallbackBytes,
    String? fallbackFormat,
  }) async {
    // 内嵌音频兜底优先(若 /api/chat 意外带回了 audio,省一次流式请求)。
    // 注意:内嵌路径用 playTts 整段播放,不支持打断(整段缓冲);停麦播完再启麦。
    if (fallbackBytes != null && fallbackBytes.isNotEmpty) {
      conversationLog.log('speak',
          '使用内嵌音频(回退): ${fallbackBytes.length} 字节 '
          '(${fallbackFormat ?? 'wav'})');
      await audio.stop();
      await playTts(fallbackBytes, fallbackFormat ?? 'wav');
      await audio.start();
      return;
    }

    // —— 打断(barge-in)策略 ——
    // 默认 [bargeInEnabled]=false(半双工):播放时**停麦**(audio.stop),
    // 扬声器声音不会进入录音通路,从根上杜绝回声自激死循环。播完由调用方
    // finally 的 audio.start() 恢复。
    // 开启打断(bargeInEnabled=true)时:播放期间保持麦克风开启 + 启
    // [BargeInDetector],用户开口即停 TTS。打断后的恢复/重听由 finally 块的
    // [_resumeOrRelisten] 统一处理(静默冷却 + 连续降级,防自激)。
    if (bargeInEnabled) {
      await audio.start();
      _bargeIn.start(audio.audioStream);
    } else {
      // 关闭打断(默认):播放时停麦,消除回声自激与 AEC/SCO 竞态。
      await audio.stop();
    }

    var chunkCount = 0;
    var started = false; // meta 是否已到(已 start 播放器)
    final ttsSw = Stopwatch()..start(); // TTS 总耗时(发起到播完)
    Stopwatch? firstPacketSw; // 从发起到首片到达
    // 流式下载的取消令牌:用户打断时 cancel(),中断 HTTP 下载。
    final cancelToken = CancelToken();
    // 打断回调:停播放器 + 取消下载 + 设标志。触发后让 await ttsStream 抛出。
    void onBargeIn() {
      if (_bargeInTriggered) return;
      _bargeInTriggered = true;
      conversationLog.log('speak', '打断触发:停止 TTS,准备重新聆听', error: true);
      // 立即停播、清队列(release 内部复位 externalLevel/level)。
      unawaited(_streamingTts.release());
      // 中断 HTTP 流式下载(await ttsStream 抛 DioException cancel)。
      if (!cancelToken.isCancelled) cancelToken.cancel();
    }

    StreamSubscription? bargeSub;
    if (bargeInEnabled) {
      bargeSub = _bargeIn.onBargeIn.listen((_) => onBargeIn());
    }
    try {
      firstPacketSw = Stopwatch()..start();
      await pophie.ttsStream(
        text,
        voice: voice,
        cancelToken: cancelToken,
        onMeta: (format, sampleRate) async {
          // meta 到达即初始化播放器,后续 chunk 一到就开口。
          await _streamingTts.start(sampleRate);
          started = true;
        },
        onChunk: (pcm16) {
          // 用户已打断:丢弃后续分片(防御)。
          if (_bargeInTriggered) return;
          if (chunkCount == 0) {
            firstPacketSw!.stop();
            final fpMs = firstPacketSw.elapsedMilliseconds;
            _timings?.ttsFirstPacketMs = fpMs;
            // 端到端首声:从本轮唤醒命中 → 用户听到首声(核心「即问即答」指标)。
            _timings?.speakStartMs = _timings == null
                ? null
                : DateTime.now().difference(_timings!.startedAt).inMilliseconds;
            conversationLog.log('speak',
                '首个 PCM 分片到达,开始边收边播 (首包 ${fpMs}ms'
                ', 端到端首声 ${_timings?.speakStartMs ?? "?"}ms)');
          }
          chunkCount++;
          // 入队并尝试立即喂入(首片即开口);队列满时由原生 feed 回调驱动。
          _streamingTts.feedChunk(pcm16);
        },
        onDone: (firstPacketMs) {
          debugPrint('[VoiceAssistant] tts stream done '
              'chunks=$chunkCount firstPacketMs=$firstPacketMs');
          conversationLog.log('speak',
              '流式合成完成: $chunkCount 片, 服务端首包 ${firstPacketMs ?? "?"}ms');
          // 标记喂入结束:之后 waitForDone 会等队列+原生缓冲都排空。
          _streamingTts.markFeedingDone();
        },
      );
      // 全部喂完:等排空再 release,确保尾部音频完整播完。
      // (若已打断,_bargeInTriggered 为 true,onBargeIn 已 release,跳过等待。)
      if (started && !_bargeInTriggered) {
        await _streamingTts.waitForDone();
        await _streamingTts.release();
        ttsSw.stop();
        _timings?.ttsTotalMs = ttsSw.elapsedMilliseconds;
        conversationLog.log('speak',
            '流式播报完成 (TTS 总耗时 ${ttsSw.elapsedMilliseconds}ms)');
      } else {
        ttsSw.stop();
      }
    } on DioException catch (e) {
      // HTTP 流式下载取消(用户打断):DioException(type: cancel)。
      // 用户打断:不走批量回退(用户要说话,不该再播),直接结束。
      ttsSw.stop();
      _timings?.ttsTotalMs = ttsSw.elapsedMilliseconds;
      if (e.type == DioExceptionType.cancel && _bargeInTriggered) {
        debugPrint('[VoiceAssistant] tts stream cancelled by barge-in');
        await _streamingTts.release();
      } else {
        // 真网络错误:走批量回退。
        debugPrint('[VoiceAssistant] tts stream dio error: $e — 回退批量');
        conversationLog.log('speak', '流式失败(网络),回退批量: $e', error: true);
        await _streamingTts.release();
        try {
          final t = await pophie.tts(text);
          await playTts(t.bytes, t.format);
        } catch (e2) {
          debugPrint('[VoiceAssistant] fallback /api/tts failed: $e2');
          conversationLog.log('speak', '批量回退也失败: $e2', error: true);
        }
      }
    } catch (e) {
      ttsSw.stop();
      _timings?.ttsTotalMs = ttsSw.elapsedMilliseconds;
      debugPrint('[VoiceAssistant] tts stream failed: $e — 回退批量 /api/tts');
      conversationLog.log('speak', '流式失败,回退批量: $e', error: true);
      // 流式途中可能已开口播放,先释放播放器再走批量。
      await _streamingTts.release();
      try {
        final t = await pophie.tts(text);
        await playTts(t.bytes, t.format);
      } catch (e2) {
        debugPrint('[VoiceAssistant] fallback /api/tts failed: $e2');
        conversationLog.log('speak', '批量回退也失败: $e2', error: true);
      }
    } finally {
      // 停打断监听。
      await bargeSub?.cancel();
      _bargeIn.stop();
      // 恢复麦克风到正常状态:打断模式下麦一直开着,这里确保停一次再交给上层;
      // 关闭打断模式下麦已停。统一 stop 后由 _runConversation finally 按需启。
      await audio.stop();
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

  /// 打断(barge-in)后的恢复/重听决策(防回声自激死循环的**核心安全网**)。
  ///
  /// 背景:默认半双工([bargeInEnabled]=false)时播报期停麦,不存在回声打断,
  /// 此方法仅在开启打断时发挥作用。即便开启打断,本方法确保「打断→重听」
  /// 不会无限循环:
  ///
  /// 1. **连续打断降级**:本轮被打断则 [_consecutiveBargeIns] 已在调用方自增。
  ///    若已超过 [_maxBargeInRelistens](默认 1),视为回声误触发,放弃重听,
  ///    直接恢复唤醒监听(回到待唤醒态,需用户再说唤醒词)。
  /// 2. **静默冷却确认**:允许重听时,先调 [AudioCaptureService.waitForSilence]
  ///    等待连续 ~1s 静音(确认扬声器残余/回声消散)。等待失败(超时仍吵)也
  ///    放弃重听降级回唤醒。这避免把上一轮 TTS 尾音/回声当成新一轮用户语音。
  /// 3. 通过上述确认才 [relisten](重新跑一轮 [captureUtterance])。
  ///
  /// 调用前提:_state 已置 idle(防重入闸门),麦克风已 `audio.start()`。
  Future<void> _resumeOrRelisten(void Function() relisten) async {
    // 1) 连续打断降级:超过上限不再重听。
    if (_consecutiveBargeIns > _maxBargeInRelistens) {
      conversationLog.log('wake',
          '连续打断 $_consecutiveBargeIns 次超过上限,降级回唤醒监听(防自激)',
          error: true);
      await _resumeWakeListening();
      return;
    }
    // 2) 静默冷却确认:等扬声器残余/环境噪声消散。
    final quiet = await audio.waitForSilence();
    if (!quiet) {
      conversationLog.log('wake',
          '打断后静默冷却超时(环境仍吵),降级回唤醒监听', error: true);
      await _resumeWakeListening();
      return;
    }
    // 3) 确认为真用户打断,重新聆听一轮。
    conversationLog.log('wake', '静默冷却完成,重新聆听');
    relisten();
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

  /// 播报一条主动消息:流式合成 → 停麦 → 边收边播 → 启麦 → 回 idle。
  /// 仅应在 idle 态调用(由 _enqueueOrPlay 或 _runConversation 的 finally 保证)。
  ///
  /// 主动消息只回文本(无 output.voice),用默认情感参数走流式;流式失败时
  /// [_playStreamingTts] 内部会自动回退批量 /api/tts + playTts。
  Future<void> _playProactive(ProactiveMessage msg) async {
    if (!_running) return;
    // 主动播报的触发来源:由后端 metadata.trigger 归一化得到(welcome/reminder/
    // living_loop)，统一加 proactive: 前缀，与端侧触发区分。
    final type = msg.triggerType ?? 'unknown';
    final source = 'proactive:$type';
    _triggerSource = source;
    pophie.setTriggerSource(source);
    try {
      _setState(VoiceState.speaking); // 复用 speaking 态驱动虚拟宠物嘴型
      _replyText = msg.content; // 复用气泡字幕字段,UI 无需改
      notifyListeners();
      conversationLog.log('proactive',
          '主动提醒[${triggerLabel(source)}]: ${msg.content}');

      // 流式合成播报(内部含停麦/启麦与批量回退,同主对话范式)。
      await _playStreamingTts(text: msg.content);
    } catch (e) {
      debugPrint('[VoiceAssistant] proactive play failed: $e');
      conversationLog.log('proactive', '主动提醒播放失败: $e', error: true);
    } finally {
      final wasBarged = _bargeInTriggered;
      _bargeInTriggered = false;
      // 连续打断计数(同主对话范式):被打断自增,正常完成清零。
      if (wasBarged) {
        _consecutiveBargeIns++;
      } else {
        _consecutiveBargeIns = 0;
      }
      _replyText = '';
      _triggerSource = null;
      pophie.setTriggerSource(null);
      audio.level.value = 0.0;
      _setState(VoiceState.idle);
      // _playStreamingTts 播放时停了麦,这里重启(唤醒与聆听前置条件)。
      await audio.start();
      // 打断后走 _resumeOrRelisten(静默冷却 + 连续降级,防回声自激死循环);
      // 未被打断则正常恢复唤醒监听。
      if (wasBarged) {
        await _resumeOrRelisten(_runConversation);
      } else {
        // 恢复唤醒监听。
        await _resumeWakeListening();
      }
    }
  }

  @override
  void dispose() {
    // stop() 是异步的,这里同步取消轮询,确保 dispose 后不会再触发回调。
    _proactiveTimer?.cancel();
    _proactiveTimer = null;
    stop();
    _player.dispose();
    _streamingTts.dispose();
    _bargeIn.dispose();
    pophie.dispose();
    audio.dispose();
    wakeWord.dispose();
    speech.dispose();
    chat.dispose();
    super.dispose();
  }
}

/// 一轮对话各阶段耗时(排查「即问即答」延迟)。
///
/// 阶段(对应 [_runConversation] 时序):
/// - wake:唤醒命中 → 进入 waking(wakeWord.stop 完成)
/// - listen:进入 listening → captureUtterance 返回(含 VAD 静音等待)
/// - vadSilence:说完话后的静音判定时长(700ms 超时,刚性延迟)
/// - chat:发起 /api/chat → 后端返回(STT + LLM 完成)
/// - ttsFirstPacket:发起 /api/tts/stream → 首个 PCM 分片到达(首声延迟)
/// - ttsTotal:TTS 流式总耗时(从开始到播完)
/// - speakStart:唤醒命中 → 用户听到首声(端到端延迟,核心指标)
///
/// 记录方式:每阶段用 Stopwatch 测量,边界处 [record]。结束时 [summary]
/// 输出可读摘要,一目了然哪个阶段是瓶颈。
class _ConversationTimings {
  /// 对话起点(唤醒命中时刻)。用于计算端到端延迟。
  final DateTime startedAt = DateTime.now();

  // 各阶段耗时(毫秒)。null 表示该阶段未到达(如静默回复无 tts)。
  int? wakeMs;
  int? listenMs;
  int? vadSilenceMs; // VAD 静音判定(从说完话到 VAD 触发结束)
  int? chatMs;
  int? ttsFirstPacketMs;
  int? ttsTotalMs;
  /// 首声时刻相对唤醒命中的总延迟(核心「即问即答」指标)。
  int? speakStartMs;

  @override
  String toString() {
    final parts = <String>[];
    void add(String label, int? ms) {
      if (ms != null) parts.add('$label=${ms}ms');
    }
    add('wake', wakeMs);
    add('listen', listenMs);
    if (vadSilenceMs != null) parts.add('(含静音判定 ${vadSilenceMs}ms)');
    add('chat', chatMs);
    add('tts首包', ttsFirstPacketMs);
    add('tts总', ttsTotalMs);
    add('⏱端到端首声', speakStartMs);
    return parts.join(' | ');
  }
}
