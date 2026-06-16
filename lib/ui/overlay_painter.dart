import 'package:flutter/material.dart';
import 'package:hand_detection/hand_detection.dart';

import '../core/app_controller.dart';
import '../models/detection.dart';
import '../models/expression.dart';
import '../models/hand_gesture.dart';
import '../models/person.dart';
import '../theme/app_theme.dart';

/// 调试覆盖层：绘制人脸关键点 / 人脸框 / 表情 / 身份，以及手部骨架 / 手势。
class DetectionOverlayPainter extends CustomPainter {
  final DetectionResult result;
  final DisplaySettings settings;

  DetectionOverlayPainter({required this.result, required this.settings});

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
    for (final face in result.faces) {
      _paintFace(canvas, size, face);
    }
    for (final hand in result.hands) {
      _paintHand(canvas, size, hand);
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
    return old.result != result || old.settings != settings;
  }
}

class _Chip {
  final String text;
  final Color color;
  const _Chip(this.text, this.color);
}
