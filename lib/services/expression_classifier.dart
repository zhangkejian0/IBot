import 'dart:math' as math;

import 'package:kwon_mediapipe_landmarker/kwon_mediapipe_landmarker.dart';

import '../models/expression.dart';

/// 基于 ARKit 52 blendshapes 的表情分类器。
///
/// 参考 Apple ARFaceAnchor.blendShapes 的语义，将若干 blendshape 系数
/// 组合为 7 种常见表情的打分，取最高者；若所有表情得分都很低则判为「中性」。
///
/// 这是规则式分类（非训练模型），便于调试阶段直观验证关键点 → 表情的映射。
class ExpressionClassifier {
  /// 判定为非中性表情所需的最小得分。
  final double activationThreshold;

  const ExpressionClassifier({this.activationThreshold = 0.28});

  ExpressionResult classify(Map<String, double> b) {
    if (b.isEmpty) return ExpressionResult.neutral;

    double v(String k) => (b[k] ?? 0).clamp(0.0, 1.0);
    double avg(String a, String c) => (v(a) + v(c)) / 2.0;

    // 各类基础特征
    final smile = avg(FaceBlendshape.mouthSmileLeft,
        FaceBlendshape.mouthSmileRight);
    final cheekSquint = avg(
        FaceBlendshape.cheekSquintLeft, FaceBlendshape.cheekSquintRight);
    final frown =
        avg(FaceBlendshape.mouthFrownLeft, FaceBlendshape.mouthFrownRight);
    final browInnerUp = v(FaceBlendshape.browInnerUp);
    final browOuterUp = avg(
        FaceBlendshape.browOuterUpLeft, FaceBlendshape.browOuterUpRight);
    final browDown =
        avg(FaceBlendshape.browDownLeft, FaceBlendshape.browDownRight);
    final jawOpen = v(FaceBlendshape.jawOpen);
    final eyeWide = avg(FaceBlendshape.eyeWideLeft, FaceBlendshape.eyeWideRight);
    final eyeSquint =
        avg(FaceBlendshape.eyeSquintLeft, FaceBlendshape.eyeSquintRight);
    final noseSneer =
        avg(FaceBlendshape.noseSneerLeft, FaceBlendshape.noseSneerRight);
    final upperLipUp = avg(
        FaceBlendshape.mouthUpperUpLeft, FaceBlendshape.mouthUpperUpRight);
    final mouthStretch = avg(
        FaceBlendshape.mouthStretchLeft, FaceBlendshape.mouthStretchRight);
    final mouthPress =
        avg(FaceBlendshape.mouthPressLeft, FaceBlendshape.mouthPressRight);

    final scores = <Expression, double>{
      // 高兴：嘴角上扬 + 脸颊上提（杜乡微笑）
      Expression.happy: smile * 0.8 + cheekSquint * 0.2,

      // 伤心：嘴角下垂 + 眉心内侧上抬（八字眉）
      Expression.sad: frown * 0.6 + browInnerUp * 0.4 - smile * 0.3,

      // 惊讶：张嘴 + 眉毛整体上抬 + 睁大眼
      Expression.surprised:
          jawOpen * 0.45 + browOuterUp * 0.25 + browInnerUp * 0.15 +
              eyeWide * 0.15,

      // 愤怒：皱眉（眉下压）+ 抿嘴/眯眼
      Expression.angry:
          browDown * 0.6 + mouthPress * 0.2 + eyeSquint * 0.2 - jawOpen * 0.2,

      // 厌恶：皱鼻 + 上唇上提
      Expression.disgusted: noseSneer * 0.6 + upperLipUp * 0.4,

      // 恐惧：睁大眼 + 眉内抬 + 嘴角横向拉伸
      Expression.fearful:
          eyeWide * 0.4 + browInnerUp * 0.3 + mouthStretch * 0.3,
    };

    Expression best = Expression.neutral;
    double bestScore = 0;
    scores.forEach((expr, s) {
      final clamped = s.clamp(0.0, 1.0);
      if (clamped > bestScore) {
        bestScore = clamped.toDouble();
        best = expr;
      }
    });

    final fullScores = <Expression, double>{
      Expression.neutral: math.max(0.0, 1.0 - bestScore),
      ...scores.map((k, val) => MapEntry(k, val.clamp(0.0, 1.0).toDouble())),
    };

    if (bestScore < activationThreshold) {
      return ExpressionResult(
        expression: Expression.neutral,
        score: fullScores[Expression.neutral] ?? 1.0,
        scores: fullScores,
      );
    }

    return ExpressionResult(
      expression: best,
      score: bestScore,
      scores: fullScores,
    );
  }
}
