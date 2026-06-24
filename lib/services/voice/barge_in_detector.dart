import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

/// 打断检测器(barge-in):TTS 播放期间持续监听麦克风,检测用户开口即触发,
/// 让编排器 [VoiceAssistant] 立即停止 TTS、重新聆听,实现「用户随时可插话」。
///
/// ## 抗 TTS 回声误触发(核心难点)
///
/// 播放时麦克风仍开着,扬声器声音会被拾音(回声/串扰)。平台 `echoCancel`
/// 只是提示,非应用级回声消除(它不感知本应用的 TTS 输出,无法做参考相减)。
/// 故若用低阈值开口检测,TTS 自身会误触发「打断」。
///
/// 本检测器的三重防护:
/// 1. **高阈值** [speechThreshold]:TTS 回声 RMS 归一化通常 <0.12;用户近距离
///    说话常 >0.3。取 0.18 拉开差距,远高于 [AudioCaptureService] 采音的 0.06。
/// 2. **持续性要求** [consecutiveFrames]:要求连续多帧(每帧 ~一个 PCM 分片)
///    超阈值才触发,过滤偶发回声尖峰。N=3 → 约 300ms 持续高能量才算开口。
/// 3. **冷却期** [cooldownMs]:TTS 刚开口的前 ~500ms 不检测,避免播放起始瞬态
///    (刚出声时音量尖峰 + 扬声器刚启动的不稳定)。
///
/// 算法:对每个 PCM 分片算 RMS(归一化 0..1,分母 6000,与
/// [AudioCaptureService._rmsLevel] 一致)。连续 N 帧超阈值(且过冷却期)→
/// 触发 [onBargeIn](单次,触发后停止)。
///
/// 阈值/连续帧/冷却期均为可调常量(实测驱动):误触发→调高阈值/加连续帧;
/// 打断不灵→调低阈值/减连续帧。
class BargeInDetector {
  BargeInDetector({
    this.speechThreshold = 0.18,
    this.consecutiveFrames = 3,
    this.cooldownMs = 500,
  });

  /// 触发「打断」的开口能量阈值(归一化 0..1)。默认 0.18。
  /// 远高于采音的 0.06;TTS 回声通常 <0.12,近距离说话常 >0.3。
  final double speechThreshold;

  /// 触发所需的连续超阈值帧数。默认 3(约 300ms 持续高能量才算开口)。
  final int consecutiveFrames;

  /// TTS 开口后的冷却期(ms),此期间不检测,避免播放起始瞬态。默认 500ms。
  final int cooldownMs;

  final StreamController<void> _controller =
      StreamController<void>.broadcast();
  Stream<void> get onBargeIn => _controller.stream;

  StreamSubscription<Uint8List>? _sub;
  bool _arming = false; // 是否在监听
  bool _triggered = false; // 已触发(防重复)
  int _consecutive = 0; // 当前连续超阈值帧数
  DateTime? _firstChunkAt; // 首个 PCM 分片到达时刻(冷却期起点)

  /// 开始监听打断。订阅 [micStream](来自 AudioCaptureService.audioStream)。
  /// 在 TTS 开始播放前调用。幂等:已在监听则忽略。
  ///
  /// [micStream] 必须是 broadcast 流(audioStream 已是 broadcast)。
  void start(Stream<Uint8List> micStream) {
    if (_arming) return;
    _arming = true;
    _triggered = false;
    _consecutive = 0;
    _firstChunkAt = null;
    _sub = micStream.listen(_onFrame, onError: (e) {
      debugPrint('[BargeIn] stream error: $e');
    });
  }

  void _onFrame(Uint8List bytes) {
    if (!_arming || _triggered) return;
    // 冷却期:TTS 首声后 cooldownMs 内不检测(避免起始瞬态误触发)。
    final now = DateTime.now();
    if (_firstChunkAt == null) {
      _firstChunkAt = now;
      return; // 第一帧只记录时刻,不参与判定
    }
    if (now.difference(_firstChunkAt!).inMilliseconds < cooldownMs) return;

    final level = _rmsLevel(bytes);
    if (level >= speechThreshold) {
      _consecutive++;
      if (_consecutive >= consecutiveFrames) {
        _triggered = true;
        debugPrint('[BargeIn] 用户开口打断触发 '
            '(level=${level.toStringAsFixed(3)} ≥ $speechThreshold, '
            '连续 $_consecutive 帧)');
        _controller.add(null);
        return;
      }
    } else {
      // 中断连续性:能量回落,清零重新计数。
      _consecutive = 0;
    }
  }

  /// 停止监听(不取消已触发的 onBargeIn)。在 TTS 播完/被打断后调用。
  void stop() {
    _arming = false;
    _sub?.cancel();
    _sub = null;
    _consecutive = 0;
    _firstChunkAt = null;
  }

  /// 是否已触发打断。
  bool get isTriggered => _triggered;

  /// 释放资源。
  Future<void> dispose() async {
    stop();
    await _controller.close();
  }

  // —— RMS 归一化(算法对齐 AudioCaptureService._rmsLevel)——
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
    return (rms / 6000).clamp(0.0, 1.0); // 分母 6000 同采音侧
  }
}
