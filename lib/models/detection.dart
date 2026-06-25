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

  /// 注视方向（来自 eyeLook blendshape，-1..1）。
  /// gazeX 正=向右看，gazeY 正=向下看；这里为检测原始值，未做前置镜像。
  /// 仅主脸（MediaPipe）携带，其余脸为 0。
  final double gazeX;
  final double gazeY;

  /// 眼睛闭合程度（eyeBlink 左右均值，0..1，1=完全闭眼）。
  /// 供时序行为聚合判定「困倦」（长时间近乎闭眼）。仅主脸携带，其余脸为 0。
  final double eyeBlink;

  const FaceOverlay({
    required this.landmarks,
    required this.boundingBox,
    required this.expression,
    this.identity,
    this.gazeX = 0,
    this.gazeY = 0,
    this.eyeBlink = 0,
  });
}

/// 单个被识别物体的覆盖层数据。坐标归一化到 0..1（相对竖直摆正后的画面）。
///
/// 由 ML Kit 物体检测（[ObjectEngine]）产出：包围盒给「大概位置」，[label]
/// 为分类标签（挂自定义模型时为「水杯/瓶子/手机」等细类，否则为粗类或空），
/// [trackingId] 为跨帧稳定的跟踪 ID（同一物体多帧保持不变）。
class ObjectOverlay {
  final Rect boundingBox;

  /// 分类标签（中文或模型给出的文本）；无可用标签时为 null。
  final String? label;

  /// 标签置信度（0..1）；无标签时为 0。
  final double confidence;

  /// ML Kit 跨帧跟踪 ID；未启用跟踪或不可用时为 null。
  final int? trackingId;

  /// 是否被判定为「正被手持」（与某只手的包围盒重叠/贴近）。
  /// 由 AppController 的空间推理写入，引擎本身不产出。
  final bool heldByHand;

  const ObjectOverlay({
    required this.boundingBox,
    this.label,
    this.confidence = 0,
    this.trackingId,
    this.heldByHand = false,
  });

  ObjectOverlay copyWith({bool? heldByHand}) {
    return ObjectOverlay(
      boundingBox: boundingBox,
      label: label,
      confidence: confidence,
      trackingId: trackingId,
      heldByHand: heldByHand ?? this.heldByHand,
    );
  }

  /// 归一化中心点（0..1）。
  Offset get center =>
      Offset(boundingBox.center.dx, boundingBox.center.dy);
}

/// 单个人体的覆盖层绘制数据(33 关键点)。坐标已归一化到 0..1
/// (相对竖直摆正后的画面)。
///
/// 关键点顺序与 MediaPipe [PoseLandmarkIndex] 一致(0-10 头部、11-22 上肢、
/// 23-32 下肢),骨架连线见 `pose_landmarks.dart` 的 [poseConnections]。
/// 由 [FaceEngine] 产出(与 [FaceOverlay] 共享同一次 MediaPipe 调用)。
class PoseOverlay {
  /// 33 个归一化关键点(0..1),按 [PoseLandmarkIndex] 顺序摆放。
  final List<Offset> landmarks;

  /// 所有关键点的归一化外接框。
  final Rect boundingBox;

  const PoseOverlay({
    required this.landmarks,
    required this.boundingBox,
  });
}

/// 一帧聚合的全部识别结果，用于驱动覆盖层与信息面板。
///
/// 人脸为列表（[faces]）以支持多人脸：第 0 个为主脸（面积最大、且携带
/// MediaPipe 478 点网格与表情），其余脸仅有包围盒与身份。手势为列表
/// （[hands]）支持多只手。物体为列表（[objects]）支持多目标。所有覆盖层
/// 坐标均归一化到 0..1。
class DetectionResult {
  final List<FaceOverlay> faces;
  final List<HandOverlay> hands;
  final List<ObjectOverlay> objects;
  final List<PoseOverlay> poses;

  /// 画面是否需要水平镜像（前置摄像头）。
  final bool mirror;

  const DetectionResult({
    this.faces = const [],
    this.hands = const [],
    this.objects = const [],
    this.poses = const [],
    this.mirror = false,
  });

  /// 便捷访问：主脸（列表首个，可能为空）。
  FaceOverlay? get face => faces.isEmpty ? null : faces.first;

  /// 便捷访问：被手持的物体（若有多个取置信度最高者，否则 null）。
  ObjectOverlay? get heldObject {
    ObjectOverlay? best;
    for (final o in objects) {
      if (!o.heldByHand) continue;
      if (best == null || o.confidence > best.confidence) best = o;
    }
    return best;
  }

  DetectionResult copyWith({
    List<FaceOverlay>? faces,
    List<HandOverlay>? hands,
    List<ObjectOverlay>? objects,
    List<PoseOverlay>? poses,
    bool? mirror,
  }) {
    return DetectionResult(
      faces: faces ?? this.faces,
      hands: hands ?? this.hands,
      objects: objects ?? this.objects,
      poses: poses ?? this.poses,
      mirror: mirror ?? this.mirror,
    );
  }
}
