import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:kwon_mediapipe_landmarker/kwon_mediapipe_landmarker.dart';

import '../models/detection.dart';
import 'expression_classifier.dart';

/// 人脸引擎：封装 [KwonMediapipeLandmarker]（MediaPipe Face Landmarker）。
///
/// 输出 478 个关键点 + 52 个 ARKit 兼容 blendshapes，并交给
/// [ExpressionClassifier] 得到 7 种表情之一。
class FaceEngine {
  final ExpressionClassifier _classifier;
  bool _initialized = false;

  FaceEngine({ExpressionClassifier? classifier})
      : _classifier = classifier ?? const ExpressionClassifier();

  bool get isInitialized => _initialized;

  Future<void> initialize() async {
    if (_initialized) return;
    await KwonMediapipeLandmarker.initialize(
      face: true,
      pose: false,
      faceOptions: const FaceOptions(
        numFaces: 1,
        minDetectionConfidence: 0.5,
        minTrackingConfidence: 0.5,
        outputBlendshapes: true,
      ),
    );
    _initialized = true;
  }

  /// 处理一帧相机图像，返回人脸覆盖层（含关键点、表情）；无脸时返回 null。
  ///
  /// [sensorOrientation] 为相机传感器朝向（度）。[isFrontCamera] 与
  /// [deviceOrientation] 用于把图像旋转到与预览一致（前置摄像头为
  /// `(sensorOrientation + deviceRotation) % 360`，与 HandEngine 旋转基准一致，
  /// 否则人脸坐标空间会与手势坐标空间错位，导致点位绘制偏移）。
  Future<FaceOverlay?> process(
    CameraImage image,
    int sensorOrientation, {
    bool isFrontCamera = true,
    DeviceOrientation deviceOrientation = DeviceOrientation.landscapeLeft,
  }) async {
    if (!_initialized) return null;

    // 与 HandEngine 的 rotationForFrame 保持同一旋转基准：旋转后的图像朝向
    // 必须一致，两引擎输出的归一化坐标才落在同一空间，overlay 才能正确叠加。
    final deviceRotation = switch (deviceOrientation) {
      DeviceOrientation.portraitUp => 0,
      DeviceOrientation.landscapeLeft => 90,
      DeviceOrientation.portraitDown => 180,
      DeviceOrientation.landscapeRight => 270,
    };
    final rotation = isFrontCamera
        ? (sensorOrientation + deviceRotation) % 360
        : (sensorOrientation - deviceRotation + 360) % 360;

    final result = await KwonMediapipeLandmarker.detectFromCamera(
      planes: image.planes.map((p) => p.bytes).toList(),
      width: image.width,
      height: image.height,
      rotation: rotation,
      format: 'YUV420',
      bytesPerRow: image.planes.map((p) => p.bytesPerRow).toList(),
    );

    final face = result.face;
    if (face == null || face.landmarks.isEmpty) return null;

    final points = <Offset>[];
    double minX = 1, minY = 1, maxX = 0, maxY = 0;
    for (final lm in face.landmarks) {
      final x = lm.x.clamp(0.0, 1.0).toDouble();
      final y = lm.y.clamp(0.0, 1.0).toDouble();
      points.add(Offset(x, y));
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }

    final expression = _classifier.classify(face.blendshapes);

    return FaceOverlay(
      landmarks: points,
      boundingBox: Rect.fromLTRB(minX, minY, maxX, maxY),
      expression: expression,
    );
  }

  Future<void> dispose() async {
    if (!_initialized) return;
    await KwonMediapipeLandmarker.dispose();
    _initialized = false;
  }
}
