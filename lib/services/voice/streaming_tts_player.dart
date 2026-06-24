import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_pcm_sound/flutter_pcm_sound.dart';

import 'audio_capture_service.dart';

/// 流式 TTS 播放器:把 `/api/tts/stream`(文档 §3.6.1)逐片到达的 PCM16 分片
/// 喂给设备扬声器,做到「首个分片到达即开口」,首声延迟 ≈ LLM 结束 +
/// DashScope 首包(~500ms)。
///
/// 基于 `flutter_pcm_sound`:专为实时裸 PCM 喂入设计,**无 native FeedThread**
/// (flutter_sound 的 FeedThread 是 SEGV 根源,issue #508)。其背压模型由原生
/// `feed` 回调驱动:缓冲低于阈值时原生回调要数据,我们从待播队列取下一片喂入。
/// 没有"后台线程撞已释放 track"的竞态,稳定可靠。
///
/// ## 工作模型
/// - [start]: `FlutterPcmSound.setup` 配置采样率/声道 + 注册 feed 回调 + 设阈值;
///   feed 回调触发时调用 [pump],从 [_queue] 取下一片喂入。
/// - [feedChunk]: 把 `/api/tts/stream` 的 PCM 分片加入 [_queue](并尝试立即 pump,
///   使首片到达即开口)。**收齐不必等喂完**——边收边排队边播。
/// - [waitForDone]: 全部分片入队后,等原生缓冲排空(remainingFrames==0)再 release。
/// - [release]: 释放音频资源(停播放、清队列)。
///
/// 嘴型联动:每片喂入时算 RMS(归一化 0..1,EMA 平滑,算法对齐
/// [AudioCaptureService._rmsLevel])写入 [audio.level]。虚拟宠物订阅 level
/// 驱动嘴部张合(camera_screen.dart 的 setListeningLoudness),无需改 UI。
class StreamingTtsPlayer {
  StreamingTtsPlayer(this._audio);

  /// 共享的音频采集服务:播放期间接管它的 [AudioCaptureService.level]
  /// 驱动虚拟宠物嘴型(与 [VoiceAssistant.playTts] 同一机制)。
  final AudioCaptureService _audio;

  /// 待播放的 PCM 分片队列(FIFO)。feed 回调触发时从队首取。
  final ListQueue<Uint8List> _queue = ListQueue<Uint8List>();

  /// 是否已 setup(实例活跃)。release 后置 false。
  bool _active = false;
  bool get isActive => _active;

  /// 全部分片已入队(NDJSON `done` 收到)。用于 [waitForDone] 判断是否真正播完。
  bool _feedingDone = false;

  /// 本轮已喂入的总样本数(用于估算播放时长,作 waitForDone 兜底)。
  int _totalSamplesFed = 0;

  /// 本轮采样率(setup 时记录,用于估算播放时长)。
  int _sampleRate = 22050;

  /// 排空完成信号:原生 remainingFrames==0 且无待播数据时 complete。
  Completer<void>? _drainedCompleter;

  /// 按 [sampleRate] 启动播放(收到 meta 行后调用,首个 chunk 即开口)。
  /// 幂等:已 setup 则忽略(仅更新采样率记录)。
  ///
  /// 注意:flutter_pcm_sound 是全局静态单例(FlutterPcmSound),不支持多实例。
  /// 故采用 setup→feed→release 的单轮生命周期,每轮重新 setup。
  Future<void> start(int sampleRate) async {
    if (_active) return;
    _sampleRate = sampleRate;
    _queue.clear();
    _feedingDone = false;
    _totalSamplesFed = 0;
    _drainedCompleter = null;
    // 接管 level:播放期间由本播放器写入,避免麦克风实时音量覆盖。
    _audio.externalLevel = true;
    await FlutterPcmSound.setLogLevel(LogLevel.error);
    await FlutterPcmSound.setup(
      sampleRate: sampleRate,
      channelCount: 1, // 单声道,与文档 §3.6.1 PCM 格式一致
    );
    // feed 阈值:缓冲低于此帧数时原生回调要数据。
    // 取 sampleRate*0.1(约 100ms 缓冲),平衡延迟与欠载风险。
    final threshold = (sampleRate * 0.1).round();
    await FlutterPcmSound.setFeedThreshold(threshold);
    FlutterPcmSound.setFeedCallback(_onFeed);
    _active = true;
  }

  /// 把一段 PCM16(小端、单声道)分片加入待播队列,并尝试立即 pump(首片开口)。
  /// 必须在 [start] 之后调用。同时计算本片 RMS 驱动嘴型。
  void feedChunk(Uint8List pcm16) {
    if (!_active || pcm16.isEmpty) return;
    _queue.add(pcm16);
    // 立即 pump:若原生缓冲未满会立即喂入(首片到达即开口);
    // 若已满则 pump 不做任何事,等 feed 回调触发再喂。
    pump();
  }

  /// 标记所有分片已入队(NDJSON `done` 后调用)。之后 [waitForDone] 会等
  /// 队列与原生缓冲都排空。
  void markFeedingDone() {
    _feedingDone = true;
    // 触发一次 pump/drain 判定,可能立即满足排空条件。
    pump();
  }

  /// 原生 feed 回调:缓冲低于阈值或排空时触发。
  /// [remainingFrames] 为原生当前剩余帧数(含 AudioTrack 硬件缓冲)。
  void _onFeed(int remainingFrames) {
    pump();
    // 排空判定:已喂完 + Dart 队列空 + 原生缓冲也空(0 帧) → 真正播完。
    // 必须等 remainingFrames==0:AudioTrack 硬件缓冲有 ~几百 ms 未播数据,
    // 提前 release 会触发 cleanup→flush 丢弃它们(导致音频被截断)。
    if (_feedingDone && _queue.isEmpty && remainingFrames == 0) {
      final c = _drainedCompleter;
      if (c != null && !c.isCompleted) {
        debugPrint('[StreamingTts] drained (remainingFrames=0) — 本轮播完');
        c.complete();
      }
    }
  }

  /// 从队列取下一片喂入原生。无数据时静默(等下次 feed 回调或新分片到达)。
  /// 嘴型 RMS 在此处驱动(实际喂入的片才是当前发声)。
  void pump() {
    if (!_active) return;
    if (_queue.isEmpty) return;
    final chunk = _queue.removeFirst();
    // 累计样本数(每帧 2 字节,单声道),供 waitForDone 估算播放时长兜底。
    _totalSamplesFed += chunk.lengthInBytes ~/ 2;
    // 嘴型:本片 RMS→level(EMA 平滑,与麦克风采集同一套归一化/分母)。
    _audio.level.value = _smoothedRms(chunk);
    // PcmArrayInt16 直接用 chunk 的 ByteData 构造,避免逐样本拷贝。
    final bytes = chunk.buffer.asByteData(chunk.offsetInBytes, chunk.lengthInBytes);
    FlutterPcmSound.feed(PcmArrayInt16(bytes: bytes));
  }

  /// 等待本轮全部播完(队列 + 原生缓冲都排空)。需先 [markFeedingDone]。
  ///
  /// **关键**:必须等原生 `remainingFrames==0`(硬件真正播完)才返回,绝不能在
  /// Dart 队列空时就提前返回——此时 AudioTrack 硬件缓冲还有几百 ms 未播数据,
  /// 紧接着的 [release] 会 `cleanup→flush` 丢弃它们,导致音频被截断。
  ///
  /// 实现靠 [_onFeed] 的零穿越(remainingFrames==0)事件 complete completer。
  /// 超时兜底按「已喂样本数 / 采样率」估算的播放时长 + 余量,确保即使零穿越
  /// 事件被原生抑制(mLastZeroFeed==totalFeeds)也不会过早 release。
  Future<bool> waitForDone({Duration timeout = const Duration(seconds: 30)}) async {
    if (!_active) return true;
    // 按已喂样本数估算播放时长:duration = samples / sampleRate(秒)。
    // +1s 余量覆盖硬件缓冲与调度抖动;与外部 timeout 取较小者防过长等待。
    final estimatedMs = (_totalSamplesFed / _sampleRate * 1000).round();
    final computed = Duration(milliseconds: estimatedMs + 1000);
    final effective = computed < timeout ? computed : timeout;
    _drainedCompleter = Completer<void>();
    try {
      await _drainedCompleter!.future.timeout(effective, onTimeout: () {
        debugPrint('[StreamingTts] waitForDone timeout(${effective.inSeconds}s, '
            'fed=$_totalSamplesFed samples ≈${estimatedMs}ms) '
            '— 零穿越事件可能被抑制,强制返回');
      });
      return true;
    } catch (e) {
      debugPrint('[StreamingTts] waitForDone error: $e');
      return false;
    }
  }

  /// 释放音频资源(停播放、清队列、归还 audio focus)。每轮播完或异常时调用。
  Future<void> release() async {
    if (!_active) {
      _resetLevel();
      return;
    }
    _active = false;
    _queue.clear();
    _feedingDone = false;
    final c = _drainedCompleter;
    if (c != null && !c.isCompleted) c.complete();
    _drainedCompleter = null;
    try {
      await FlutterPcmSound.release();
    } catch (e) {
      debugPrint('[StreamingTts] release failed: $e');
    }
    _resetLevel();
  }

  void _resetLevel() {
    _audio.level.value = 0.0;
    _audio.externalLevel = false;
  }

  /// 释放资源(进程级)。dispose 后不可再用。
  Future<void> dispose() async {
    await release();
  }

  // —— 实时嘴型(算法对齐 AudioCaptureService._rmsLevel)——
  double _lastLevel = 0.0;

  double _smoothedRms(Uint8List bytes) {
    final frameCount = bytes.length ~/ 2;
    if (frameCount == 0) return _lastLevel * 0.5;
    final data = ByteData.sublistView(bytes);
    var sumSq = 0.0;
    for (var i = 0; i < frameCount; i++) {
      final s = data.getInt16(i * 2, Endian.little).abs();
      sumSq += s * s;
    }
    final rms = math.sqrt(sumSq / frameCount);
    // 分母 6000 同麦克风侧,clamp 0..1。
    final raw = (rms / 6000).clamp(0.0, 1.0);
    // EMA 平滑(权重同 AudioCaptureService 采集侧:0.6 旧 + 0.4 新),
    // 避免嘴部张合抖动。
    _lastLevel = _lastLevel * 0.6 + raw * 0.4;
    return _lastLevel;
  }
}
