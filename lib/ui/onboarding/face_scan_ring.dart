import 'dart:math' as math;

import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';

/// 人脸录入结果态（驱动扫描环遮罩与配色）。
enum FaceScanState { idle, collecting, success, failed, duplicate }

/// Face-ID 风格的人脸扫描环组件（圆形摄像头取景框 + 放射状刻度环）。
///
/// 从 `face_registration_screen.dart` 抽取的共享组件，向导与设置页录入共用。
/// 通过 [cameraController] 直接预览常驻主相机流（不另开 CameraController），
/// 与主识别管线共享同一帧，避免坐标空间错位。
///
/// 组件本身不负责采集逻辑（由调用方驱动 `captureFaceSample`），只负责展示：
/// 扫描动画 [sweep]、采集进度 [progress]、结果态 [state]。
class FaceScanRing extends StatelessWidget {
  const FaceScanRing({
    super.key,
    required this.cameraController,
    required this.state,
    required this.progress,
    required this.sweep,
    this.diameter,
  });

  /// 常驻主相机控制器（AppController.cameraController）。
  final CameraController? cameraController;

  /// 当前采集状态。
  final FaceScanState state;

  /// 采集进度 [0,1]（已采集 / 需采集）。
  final double progress;

  /// 扫描动画当前值 [0,1]，null 表示静止。
  final double? sweep;

  /// 直径；为空时由父级约束决定。
  final double? diameter;

  static const Color _activeColor = AppTheme.accent;
  static const Color _baseColor = Color(0xFF38383A);
  static const Color _card = Color(0xFF1C1C1E);
  static const Color _green = AppTheme.accentGreen;
  static const Color _red = AppTheme.accentRed;

  Color get _ringColor {
    switch (state) {
      case FaceScanState.success:
        return _green;
      case FaceScanState.failed:
      case FaceScanState.duplicate:
        return _red;
      default:
        return _activeColor;
    }
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxWidth = constraints.maxWidth;
        final maxHeight = constraints.maxHeight;
        final size = (diameter ??
                math.min(maxWidth, maxHeight))
            .clamp(96.0, 360.0)
            .toDouble();
        return Center(child: _buildCircle(size));
      },
    );
  }

  Widget _buildCircle(double size) {
    const ringPadding = 22.0;
    final previewSize = size - ringPadding * 2;

    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(
            size: Size(size, size),
            painter: _FaceIdRingPainter(
              activeColor: _ringColor,
              baseColor: _baseColor,
              sweep: sweep,
              progress: progress,
            ),
          ),
          ClipOval(
            child: SizedBox(
              width: previewSize,
              height: previewSize,
              child: _buildPreview(previewSize),
            ),
          ),
          if (state == FaceScanState.success)
            Container(
              width: previewSize,
              height: previewSize,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _green.withValues(alpha: 0.18),
              ),
              child: const Icon(CupertinoIcons.checkmark_alt,
                  color: _green, size: 72),
            ),
          if (state == FaceScanState.failed ||
              state == FaceScanState.duplicate)
            Container(
              width: previewSize,
              height: previewSize,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _red.withValues(alpha: 0.18),
              ),
              child: const Icon(CupertinoIcons.exclamationmark,
                  color: _red, size: 64),
            ),
        ],
      ),
    );
  }

  Widget _buildPreview(double size) {
    final controller = cameraController;
    if (controller == null || !controller.value.isInitialized) {
      return Container(
        color: _card,
        child: const Center(
          child:
              CupertinoActivityIndicator(color: Colors.white, radius: 14),
        ),
      );
    }
    // 强制横向比例（宽>高），由 FittedBox.cover 按长边裁剪铺满圆形，避免拉伸。
    final preview = controller.value.previewSize ?? Size(size, size);
    final maxSide = math.max(preview.width, preview.height);
    final minSide = math.min(preview.width, preview.height);
    return ClipRect(
      child: OverflowBox(
        alignment: Alignment.center,
        child: FittedBox(
          fit: BoxFit.cover,
          child: SizedBox(
            width: maxSide,
            height: minSide,
            child: CameraPreview(controller),
          ),
        ),
      ),
    );
  }
}

/// Face ID 风格的放射状刻度环。
class _FaceIdRingPainter extends CustomPainter {
  _FaceIdRingPainter({
    required this.activeColor,
    required this.baseColor,
    this.sweep,
    this.progress = 0,
  });

  static const int tickCount = 72;
  final Color activeColor;
  final Color baseColor;
  final double? sweep;

  /// 采集进度 [0,1]，决定已完成刻度的染色比例。
  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final outerRadius = size.width / 2 - 2;
    const baseLen = 7.0;
    const activeLen = 14.0;

    for (var i = 0; i < tickCount; i++) {
      final t = i / tickCount;
      final angle = -math.pi / 2 + 2 * math.pi * t;
      final len = sweep != null ? activeLen : baseLen;
      final inner = outerRadius - len;
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      final p1 = Offset(center.dx + cosA * inner, center.dy + sinA * inner);
      final p2 = Offset(
          center.dx + cosA * outerRadius, center.dy + sinA * outerRadius);

      var color = baseColor;
      var width = 2.0;

      // 已采集部分整体染色，给予进度感。
      if (t < progress) {
        color = Color.lerp(baseColor, activeColor, 0.55)!;
        width = 2.5;
      }

      // 录入中：流动高光环绕。
      if (sweep != null) {
        final dist = (sweep! - t).abs();
        final glow = (1 - (dist * 6)).clamp(0.0, 1.0);
        if (glow > 0) {
          color = Color.lerp(color, activeColor, glow)!;
          width = 3.0 + glow * 1.5;
        }
      }
      canvas.drawLine(p1, p2,
          Paint()..color = color..strokeWidth = width..strokeCap = StrokeCap.round);
    }
  }

  @override
  bool shouldRepaint(covariant _FaceIdRingPainter old) =>
      old.activeColor != activeColor ||
      old.baseColor != baseColor ||
      old.sweep != sweep ||
      old.progress != progress;
}
