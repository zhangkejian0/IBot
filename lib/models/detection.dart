import 'dart:ui';

import 'package:hand_detection/hand_detection.dart';

import 'expression.dart';
import 'person.dart';

/// 单只手的覆盖层绘制数据。所有坐标已归一化到 0..1（相对竖直摆正后的画面）。
class HandOverlay {
  final List<Offset> landmarks; // 21 点，按 HandLandmarkType 顺序
  final Rect boundingBox;
  final Handedness? handedness;
  final GestureType? gesture;
  final double gestureConfidence;

  const HandOverlay({
    required this.landmarks,
    required this.boundingBox,
    this.handedness,
    this.gesture,
    this.gestureConfidence = 0,
  });
}

/// 人脸覆盖层数据。坐标归一化到 0..1。
class FaceOverlay {
  final List<Offset> landmarks; // 最多 478 点
  final Rect boundingBox;
  final ExpressionResult expression;
  final IdentityMatch? identity; // 命中身份则非空

  const FaceOverlay({
    required this.landmarks,
    required this.boundingBox,
    required this.expression,
    this.identity,
  });
}

/// 一帧聚合的全部识别结果，用于驱动覆盖层与信息面板。
///
/// 人脸为列表（[faces]）以支持多人脸：第 0 个为主脸（面积最大、且携带
/// MediaPipe 478 点网格与表情），其余脸仅有包围盒与身份。手势为列表
/// （[hands]）支持多只手。所有覆盖层坐标均归一化到 0..1。
class DetectionResult {
  final List<FaceOverlay> faces;
  final List<HandOverlay> hands;

  /// 画面是否需要水平镜像（前置摄像头）。
  final bool mirror;

  const DetectionResult({
    this.faces = const [],
    this.hands = const [],
    this.mirror = false,
  });

  /// 便捷访问：主脸（列表首个，可能为空）。
  FaceOverlay? get face => faces.isEmpty ? null : faces.first;

  DetectionResult copyWith({
    List<FaceOverlay>? faces,
    List<HandOverlay>? hands,
    bool? mirror,
  }) {
    return DetectionResult(
      faces: faces ?? this.faces,
      hands: hands ?? this.hands,
      mirror: mirror ?? this.mirror,
    );
  }
}
