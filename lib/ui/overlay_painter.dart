import 'package:flutter/material.dart';
import 'package:hand_detection/hand_detection.dart';

import '../core/app_controller.dart';
import '../face/gaze_zone_detector.dart';
import '../models/detection.dart';
import '../models/expression.dart';
import '../models/hand_gesture.dart';
import '../models/person.dart';
import '../theme/app_theme.dart';

/// 调试覆盖层：绘制人脸关键点 / 人脸框 / 表情 / 身份，以及手部骨架 / 手势。
class DetectionOverlayPainter extends CustomPainter {
  final DetectionResult result;
  final DisplaySettings settings;
  final GazeZoneDetector? zoneDetector;

  DetectionOverlayPainter({
    required this.result,
    required this.settings,
    this.zoneDetector,
  });

  Offset _map(Offset n, Size size) {
    final x = result.mirror ? (1 - n.dx) : n.dx;
    return Offset(x * size.width, n.dy * size.height);
  }

  Rect _mapRect(Rect r, Size size) {
    final a = _map(Offset(r.left, r.top), size);
    final b = _map(Offset(r.right, r.bottom), size);
    return Rect.fromLTRB(
      a.dx < b.dx ? a.dx : b.dx,
      a.dy < b.dy ? a.dy : b.dy,
      a.dx > b.dx ? a.dx : b.dx,
      a.dy > b.dy ? a.dy : b.dy,
    );
  }

  @override
  void paint(Canvas canvas, Size size) {
    // 绘制区域网格（如果启用了区域检测）
    if (zoneDetector != null) {
      _paintZoneGrid(canvas, size);
    }

    for (final face in result.faces) {
      _paintFace(canvas, size, face);
    }
    for (final hand in result.hands) {
      _paintHand(canvas, size, hand);
    }
  }

  /// 绘制区域网格
  void _paintZoneGrid(Canvas canvas, Size size) {
    final zone = zoneDetector!;
    final cols = GazeZoneDetector.cols;
    final rows = GazeZoneDetector.rows;

    // 网格线画笔
    final gridPaint = Paint()
      ..color = Colors.white.withValues(alpha: 0.3)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;

    // 高亮区域画笔
    final highlightPaint = Paint()
      ..color = Colors.green.withValues(alpha: 0.3)
      ..style = PaintingStyle.fill;

    // 当前区域高亮画笔
    final currentHighlightPaint = Paint()
      ..color = Colors.red.withValues(alpha: 0.4)
      ..style = PaintingStyle.fill;

    final cellWidth = size.width / cols;
    final cellHeight = size.height / rows;

    // 绘制网格线
    for (var i = 0; i <= cols; i++) {
      final x = i * cellWidth;
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), gridPaint);
    }
    for (var i = 0; i <= rows; i++) {
      final y = i * cellHeight;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), gridPaint);
    }

    // 高亮当前区域
    final currentCol = zone.currentCol;
    final currentRow = zone.currentRow;
    if (currentCol != null && currentRow != null) {
      final rect = Rect.fromLTWH(
        currentCol * cellWidth,
        currentRow * cellHeight,
        cellWidth,
        cellHeight,
      );
      canvas.drawRect(rect, currentHighlightPaint);

      // 绘制区域名称
      final zoneName = zone.currentZoneName ?? '';
      _paintText(
        canvas,
        zoneName,
        Offset(rect.left + 4, rect.top + 4),
        color: Colors.white,
        fontSize: 10,
      );
    }

    // 绘制死区边界（半透明红色线条）
    final deadZonePaint = Paint()
      ..color = Colors.red.withValues(alpha: 0.5)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;

    final deadZoneRatio = 0.03; // 与GazeZoneDetector._deadZoneRatio一致
    for (var col = 0; col < cols; col++) {
      for (var row = 0; row < rows; row++) {
        final rect = Rect.fromLTWH(
          col * cellWidth,
          row * cellHeight,
          cellWidth,
          cellHeight,
        );

        // 绘制死区边界
        final deadZoneWidth = cellWidth * deadZoneRatio;
        final deadZoneHeight = cellHeight * deadZoneRatio;

        // 左边界死区
        if (col > 0) {
          canvas.drawLine(
            Offset(rect.left + deadZoneWidth, rect.top),
            Offset(rect.left + deadZoneWidth, rect.bottom),
            deadZonePaint,
          );
        }
        // 右边界死区
        if (col < cols - 1) {
          canvas.drawLine(
            Offset(rect.right - deadZoneWidth, rect.top),
            Offset(rect.right - deadZoneWidth, rect.bottom),
            deadZonePaint,
          );
        }
        // 上边界死区
        if (row > 0) {
          canvas.drawLine(
            Offset(rect.left, rect.top + deadZoneHeight),
            Offset(rect.right, rect.top + deadZoneHeight),
            deadZonePaint,
          );
        }
        // 下边界死区
        if (row < rows - 1) {
          canvas.drawLine(
            Offset(rect.left, rect.bottom - deadZoneHeight),
            Offset(rect.right, rect.bottom - deadZoneHeight),
            deadZonePaint,
          );
        }
      }
    }

    // 绘制区域编号
    for (var col = 0; col < cols; col++) {
      for (var row = 0; row < rows; row++) {
        final rect = Rect.fromLTWH(
          col * cellWidth,
          row * cellHeight,
          cellWidth,
          cellHeight,
        );
        final center = rect.center;
        _paintText(
          canvas,
          '$col,$row',
          Offset(center.dx - 10, center.dy - 6),
          color: Colors.white.withValues(alpha: 0.6),
          fontSize: 9,
        );
      }
    }
  }

  void _paintFace(Canvas canvas, Size size, FaceOverlay face) {
    // 关键点网格（仅主脸——嫁接了 MediaPipe 478 点——才有关键点）
    if (settings.showFaceMesh && face.landmarks.isNotEmpty) {
      final dot = Paint()
        ..color = AppTheme.accentTeal.withValues(alpha: 0.75)
        ..style = PaintingStyle.fill;
      for (final p in face.landmarks) {
        canvas.drawCircle(_map(p, size), 1.1, dot);
      }
    }

    final box = _mapRect(face.boundingBox, size);

    if (settings.showFaceBox) {
      final boxPaint = Paint()
        ..color = AppTheme.accentGreen
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2;
      canvas.drawRRect(
        RRect.fromRectAndRadius(box, const Radius.circular(8)),
        boxPaint,
      );
    }

    // 表情标签（框上方）：只在主脸（有真实关键点）显示，避免给仅含包围盒的
    // 副脸显示占位的「中性」造成误导。
    final labels = <_Chip>[];
    if (settings.showExpression && face.landmarks.isNotEmpty) {
      final expr = face.expression;
      labels.add(_Chip(
        '${expr.expression.emoji} ${expr.expression.label}'
        '  ${(expr.score * 100).round()}%',
        expr.expression.color,
      ));
    }
    if (settings.showIdentity && face.identity != null) {
      final m = face.identity!;
      labels.add(_Chip(
        '👤 ${m.person.name}'
        '（${m.person.relation.label}） ${(m.similarity * 100).round()}%',
        AppTheme.accent,
      ));
    }
    _paintChips(canvas, labels, Offset(box.left, box.top - 6), above: true);
  }

  void _paintHand(Canvas canvas, Size size, HandOverlay hand) {
    if (hand.landmarks.length < 21) return;

    if (settings.showHandSkeleton) {
      final bonePaint = Paint()
        ..color = AppTheme.accentOrange
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.4
        ..strokeCap = StrokeCap.round;
      for (final c in handLandmarkConnections) {
        final a = hand.landmarks[c[0].index];
        final b = hand.landmarks[c[1].index];
        canvas.drawLine(_map(a, size), _map(b, size), bonePaint);
      }

      final jointPaint = Paint()
        ..color = AppTheme.accentYellow
        ..style = PaintingStyle.fill;
      for (var i = 0; i < hand.landmarks.length; i++) {
        final pt = _map(hand.landmarks[i], size);
        canvas.drawCircle(pt, 3.2, jointPaint);
        if (settings.showLandmarkIndices) {
          _paintText(canvas, '$i', pt + const Offset(3, -3),
              color: Colors.white, fontSize: 8);
        }
      }
    }

    // 手势 + 左右手标签
    final chips = <_Chip>[];
    final handednessText =
        hand.handedness == Handedness.left ? '左手' : (hand.handedness == Handedness.right ? '右手' : '手');
    if (settings.showGesture && hand.gesture != null &&
        hand.gesture != GestureType.unknown) {
      chips.add(_Chip(
        '${hand.gesture!.emoji} ${hand.gesture!.label}'
        '  ${(hand.gestureConfidence * 100).round()}%',
        AppTheme.accentOrange,
      ));
    } else {
      chips.add(_Chip(handednessText, AppTheme.secondaryBackground));
    }
    final box = _mapRect(hand.boundingBox, size);
    _paintChips(canvas, chips, Offset(box.left, box.top - 6), above: true);
  }

  void _paintChips(Canvas canvas, List<_Chip> chips, Offset anchor,
      {bool above = true}) {
    if (chips.isEmpty) return;
    var y = anchor.dy;
    const lineH = 22.0;
    var top = above ? y - chips.length * lineH : y;
    if (top < 2) top = 2;
    for (final chip in chips) {
      _paintBadge(canvas, chip.text, Offset(anchor.dx, top), chip.color);
      top += lineH;
    }
  }

  void _paintBadge(Canvas canvas, String text, Offset pos, Color color) {
    final tp = TextPainter(
      text: TextSpan(
        text: text,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final rect = Rect.fromLTWH(
      pos.dx,
      pos.dy,
      tp.width + 12,
      tp.height + 6,
    );
    final bg = Paint()..color = color.withValues(alpha: 0.85);
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, const Radius.circular(6)),
      bg,
    );
    tp.paint(canvas, Offset(pos.dx + 6, pos.dy + 3));
  }

  void _paintText(Canvas canvas, String text, Offset pos,
      {Color color = Colors.white, double fontSize = 10}) {
    final tp = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(color: color, fontSize: fontSize),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, pos);
  }

  @override
  bool shouldRepaint(covariant DetectionOverlayPainter old) {
    // 检查result、settings或zoneDetector是否变化
    return old.result != result ||
        old.settings != settings ||
        old.zoneDetector?.changeCount != zoneDetector?.changeCount;
  }
}

class _Chip {
  final String text;
  final Color color;
  const _Chip(this.text, this.color);
}
