import 'dart:async';
import 'dart:math' as math;

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
        final level01 = _rmsLevel(bytes);
        // 平滑:与上一帧 EMA,避免嘴部张合抖动。
        final prev = level.value;
        final smoothed = prev * 0.6 + level01 * 0.4;
        level.value = smoothed;
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
