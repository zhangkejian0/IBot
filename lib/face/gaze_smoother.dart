import 'dart:ui';

/// 注视方向的指数平滑器（EMA）。
///
/// 注视是连续值（-1..1），来自主脸包围盒位置，帧间会有抖动。
/// 用一阶低通平滑：out = prev + k * (raw - prev)，k 越小越平滑、滞后越大。
/// 网页侧已让 gaze 绕过弹簧直接应用，k=1.0 即零延迟直接跟随，
/// 仅由人脸检测本身的稳定性决定平滑度。
class GazeSmoother {
  GazeSmoother({this.k = 0.8});

  /// 平滑系数（0..1），越大越贴近原始值。
  final double k;

  Offset _value = Offset.zero;
  bool _initialized = false;

  /// 输入本帧原始 (x, y)，返回平滑后的 (x, y)。
  Offset update(double x, double y) {
    final raw = Offset(x, y);
    if (!_initialized) {
      _value = raw;
      _initialized = true;
    } else {
      _value = Offset(
        _value.dx + k * (raw.dx - _value.dx),
        _value.dy + k * (raw.dy - _value.dy),
      );
    }
    return _value;
  }

  /// 重置（如丢失人脸一段时间后）。
  void reset() {
    _value = Offset.zero;
    _initialized = false;
  }
}
