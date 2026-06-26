import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:record/record.dart';

/// 麦克风音频采集服务:提供 PCM 流(供唤醒 KWS / 云端 ASR)与实时音量
/// (供虚拟宠物嘴部张合联动)。
///
/// 音频格式:16kHz / 单声道 / 16-bit PCM(signed Int16 little-endian)。
/// 这是 sherpa-onnx KWS 与多数云端 ASR(阿里云一句话识别)的标准输入。
class AudioCaptureService {
  final AudioRecorder _recorder = AudioRecorder();

  bool _running = false;
  bool get isRunning => _running;

  /// 外部接管音量标志:为 true 时麦克风采集不再写入 [level]，
  /// 交由外部(TTS 播放包络)驱动，避免播报时被麦克风实时音量覆盖。
  bool _externalLevel = false;
  set externalLevel(bool v) => _externalLevel = v;

  /// 当前实时音量(归一化 0.0..1.0),驱动虚拟宠物嘴部张合。
  /// 用 ValueNotifier 而非 Stream,便于 Widget 直接 ListenableBuilder。
  final ValueNotifier<double> level = ValueNotifier<double>(0.0);

  /// PCM 音频分片流。每个元素是一段 16-bit PCM 小端字节(signed Int16)。
  /// 仅在 [start] 后、[stop] 前有数据。
  final StreamController<Uint8List> _audioStream =
      StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get audioStream => _audioStream.stream;

  StreamSubscription? _sub;

  /// 开始采集。幂等:已在运行则直接返回。
  Future<void> start() async {
    if (_running) return;
    // 先确认有录音权限(record 包内部也会查,但显式确认更稳)。
    final hasPerm = await _recorder.hasPermission();
    if (!hasPerm) {
      debugPrint('[AudioCapture] no recording permission');
      return;
    }

    _running = true;
    // 16kHz / 单声道 / 16-bit PCM 流。
    final stream = await _recorder.startStream(const RecordConfig(
      encoder: AudioEncoder.pcm16bits,
      sampleRate: 16000,
      numChannels: 1,
      autoGain: true,
      echoCancel: true,
      noiseSuppress: true,
    ));

    _sub = stream.listen(
      (bytes) {
        if (!_running) return;
        // 计算这一片的 RMS 音量,归一化到 0..1 并轻微放大(便于嘴部可见)。
        // PCM16 的满幅是 32767;用 8000 作分母做经验放大,再 clamp。
        if (!_externalLevel) {
          final level01 = _rmsLevel(bytes);
          // 平滑:与上一帧 EMA,避免嘴部张合抖动。
          final prev = level.value;
          final smoothed = prev * 0.6 + level01 * 0.4;
          level.value = smoothed;
        }
        _audioStream.add(bytes);
      },
      onError: (e) => debugPrint('[AudioCapture] stream error: $e'),
    );
    debugPrint('[AudioCapture] started (16kHz/mono/pcm16)');
  }

  /// 停止采集并重置音量。幂等。
  Future<void> stop() async {
    if (!_running) return;
    _running = false;
    await _sub?.cancel();
    _sub = null;
    try {
      await _recorder.stop();
    } catch (_) {}
    level.value = 0.0;
    debugPrint('[AudioCapture] stopped');
  }

  /// 采集一段完整语音(简易 VAD):从 [audioStream] 累积 PCM，
  /// 检测到语音起始后，若持续 [silenceTimeout] 静音则结束并返回整段裸 PCM16
  /// (16kHz/mono)。一直无人说话超过 [onsetTimeout] 返回 null。
  ///
  /// 用于「双击/唤醒后聆听一句话 → 整段上传 Pophie /api/chat」的流程。
  /// 必须在 [start] 之后调用(录音管线已在跑)。
  ///
  /// 返回 [VadResult]:含 PCM 与 VAD 静音判定时长([VadResult.silenceMs],
  /// 即说完话后等到 VAD 触发结束的时长,排查「即问即答」延迟用)。
  /// 未采集到语音返回 null。
  ///
  /// 阈值说明(均为 RMS 归一化到 0..1,分母 6000):
  /// - [speechThreshold] 起说话门槛。0.06 在安静环境够用,但办公室(键盘声/
  ///   旁人说话/空调)底噪偏高,频繁误采 → 提高到 0.15(近距离正常说话常
  ///   >0.3,远处人声/键盘多 <0.15),拉开与环境噪声的差距。
  /// - [silenceThreshold] 续说话门槛(低于此值开始计静音)。配合提高到 0.08。
  /// - [speechOnsetFrames] **起说话连续帧门控**(抗误采核心):要求连续 N 帧
  ///   都超 [speechThreshold] 才认定"开始说话",过滤键盘敲击/单次咳嗽/远处
  ///   偶发一句话等瞬态尖峰。每帧约 100ms,N=3 ≈ 持续 300ms 高能量才触发。
  ///   确认前的高能量帧会经预缓冲补回,避免切掉开头的字。
  /// - [silenceTimeout] 静音判定结束。原 1500ms 偏长(说完话还要等 1.5s
  ///   才上传),降到 500ms(Google ~600ms 的业界值,进一步压低以提升
  ///   「即问即答」体感),省 ~0.2s 端到端延迟;在"不误截停顿"与"快速响应"
  ///   间取得平衡。
  /// - [onsetTimeout] 起始超时。原 6s 偏短(唤醒后停顿一下就超时),延到 10s。
  Future<VadResult?> captureUtterance({
    Duration maxDuration = const Duration(seconds: 12),
    Duration silenceTimeout = const Duration(milliseconds: 500),
    Duration onsetTimeout = const Duration(seconds: 10),
    double speechThreshold = 0.15,
    double silenceThreshold = 0.08,
    int speechOnsetFrames = 3,
  }) async {
    if (!_running) return null;
    final completer = Completer<VadResult?>();
    final buffer = BytesBuilder();
    var speechStarted = false;
    // 起说话连续帧计数 + 预缓冲:确认"持续高能量"才起说话,过滤瞬态尖峰
    // (键盘/咳嗽/远处偶发人声),并把确认前的高能量帧补回,避免切掉开头。
    var onsetConsec = 0;
    final preRoll = <Uint8List>[];
    DateTime? lastVoiceAt;
    final startAt = DateTime.now();
    StreamSubscription<Uint8List>? sub;
    Timer? ticker;
    // 诊断:跟踪采样到的最大音量,结束时打印,便于排查"采不到语音"是
    // 阈值问题(音量低)还是真的没声音(音量为 0)。
    var maxObservedLevel = 0.0;
    var sampleCount = 0;

    void finish(Uint8List? result, {DateTime? silenceStart}) {
      if (completer.isCompleted) return;
      ticker?.cancel();
      sub?.cancel();
      // VAD 静音判定时长:从最后一次检测到语音 → 结束(说完话后的等待)。
      final silenceMs = silenceStart != null
          ? DateTime.now().difference(silenceStart).inMilliseconds
          : null;
      debugPrint('[AudioCapture] utterance finished: '
          'speechStarted=$speechStarted bytes=${result?.length ?? 0} '
          'maxLevel=${maxObservedLevel.toStringAsFixed(3)} '
          'samples=$sampleCount threshold=$speechThreshold '
          'silenceMs=$silenceMs');
      completer.complete(result == null
          ? null
          : VadResult(pcm: result, silenceMs: silenceMs));
    }

    sub = audioStream.listen(
      (bytes) {
        final lvl = _rmsLevel(bytes);
        sampleCount++;
        if (lvl > maxObservedLevel) maxObservedLevel = lvl;
        if (!speechStarted) {
          // 起说话前:要求连续 speechOnsetFrames 帧超阈值才认定开始说话,
          // 过滤偶发瞬态尖峰(键盘/咳嗽/远处一句话)。高能量帧先入预缓冲,
          // 一旦确认即整体补回 buffer(避免切掉开头的字);能量回落则丢弃。
          if (lvl >= speechThreshold) {
            onsetConsec++;
            preRoll.add(bytes);
            if (onsetConsec >= speechOnsetFrames) {
              speechStarted = true;
              for (final b in preRoll) {
                buffer.add(b);
              }
              preRoll.clear();
              lastVoiceAt = DateTime.now();
            }
          } else {
            onsetConsec = 0;
            preRoll.clear();
          }
        } else {
          // 已起说话:续说话门槛之上刷新"最后有声"时刻,持续累积。
          if (lvl >= silenceThreshold) lastVoiceAt = DateTime.now();
          buffer.add(bytes);
        }
      },
      onError: (e) {
        debugPrint('[AudioCapture] utterance stream error: $e');
        finish(speechStarted ? buffer.toBytes() : null,
            silenceStart: lastVoiceAt);
      },
    );

    ticker = Timer.periodic(const Duration(milliseconds: 100), (_) {
      final now = DateTime.now();
      if (now.difference(startAt) >= maxDuration) {
        finish(speechStarted ? buffer.toBytes() : null,
            silenceStart: lastVoiceAt);
        return;
      }
      if (!speechStarted) {
        if (now.difference(startAt) >= onsetTimeout) finish(null);
        return;
      }
      if (lastVoiceAt != null &&
          now.difference(lastVoiceAt!) >= silenceTimeout) {
        finish(buffer.toBytes(), silenceStart: lastVoiceAt);
      }
    });

    return completer.future;
  }

  /// 等待「安静窗口」:持续监听 [audioStream],当连续 [window] 时长的帧 RMS
  /// 都低于 [threshold] 时返回 true(确认安静);超过 [timeout] 仍未达成返回
  /// false(仍吵/仍在发声)。必须在 [start] 之后调用。
  ///
  /// 用途:打断后重新聆听前的**静默冷却确认**——确认扬声器残余/环境噪声已
  /// 消散,避免把上一轮 TTS 的尾音/回声当成新一轮用户语音采集上传(否则会
  /// 与"打断后立即重听"叠加形成回声自激死循环)。
  ///
  /// 复用 [_rmsLevel] 归一化算法(分母 6000)。[threshold] 默认 0.04,与
  /// [captureUtterance] 的续说话门槛 [silenceThreshold] 一致。
  Future<bool> waitForSilence({
    Duration window = const Duration(milliseconds: 1000),
    double threshold = 0.04,
    Duration timeout = const Duration(seconds: 3),
  }) async {
    if (!_running) return true; // 未采集,视为已安静
    final completer = Completer<bool>();
    StreamSubscription<Uint8List>? sub;
    Timer? deadline;
    DateTime? quietSince;
    final startAt = DateTime.now();

    void resolve(bool ok) {
      if (completer.isCompleted) return;
      deadline?.cancel();
      sub?.cancel();
      completer.complete(ok);
    }

    sub = audioStream.listen((bytes) {
      if (completer.isCompleted) return;
      final lvl = _rmsLevel(bytes);
      if (lvl < threshold) {
        // 静音:记录/保持安静起点。
        quietSince ??= DateTime.now();
        if (DateTime.now().difference(quietSince!) >= window) {
          resolve(true);
        }
      } else {
        // 出声:重置安静计时(要求连续 window 时长的安静)。
        quietSince = null;
      }
    }, onError: (e) {
      debugPrint('[AudioCapture] waitForSilence stream error: $e');
      resolve(true); // 出错不阻塞,放行让上层按正常流程处理
    });

    // 兜底超时:超时仍未达到连续安静窗口 → 视为环境仍吵,返回 false。
    deadline = Timer(timeout, () {
      debugPrint('[AudioCapture] waitForSilence timeout(${timeout.inSeconds}s) '
          '— 仍未安静(自 $startAt 起),返回 false');
      resolve(false);
    });

    return completer.future;
  }

  /// 由一段 16-bit PCM 字节计算 RMS 音量,归一化到 0..1。
  /// bytes 长度可能为奇数(非完整帧),按完整 Int16 取样。
  double _rmsLevel(Uint8List bytes) {
    final frameCount = bytes.length ~/ 2;
    if (frameCount == 0) return 0;
    final data = ByteData.sublistView(bytes);
    var sumSq = 0.0;
    for (var i = 0; i < frameCount; i++) {
      final s = data.getInt16(i * 2, Endian.little).abs();
      sumSq += s * s;
    }
    final rms = math.sqrt(sumSq / frameCount);
    // 经验归一化:安静环境 RMS 常在 300~1500,正常说话 2000~8000。
    // 用 6000 作分母,再 clamp,使正常说话落在 0.3~1.0。
    return (rms / 6000).clamp(0.0, 1.0);
  }

  /// 释放资源。stop 之后通常不再使用。
  Future<void> dispose() async {
    await stop();
    await _audioStream.close();
    await _recorder.dispose();
  }
}

/// [AudioCaptureService.captureUtterance] 的返回结果。
///
/// [pcm] 为整段裸 PCM16(16kHz/mono);[silenceMs] 为 VAD 静音判定时长
/// (说完话后等到 VAD 触发结束的时长,排查「即问即答」延迟用)。
/// silenceMs 为 null 表示非正常结束(如到达 maxDuration 截断)。
class VadResult {
  const VadResult({required this.pcm, this.silenceMs});

  final Uint8List pcm;
  final int? silenceMs;
}
