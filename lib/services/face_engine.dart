import 'dart:io' show Platform;
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:kwon_mediapipe_landmarker/kwon_mediapipe_landmarker.dart';

import '../models/detection.dart';
import 'camera_image_utils.dart';
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
  /// [deviceOrientation] 用于把图像旋转到与预览一致（与 HandEngine 共用
  /// [CameraImageUtils.detectionRotationDegrees]，否则人脸/手势坐标空间错位）。
  Future<FaceOverlay?> process(
    CameraImage image,
    int sensorOrientation, {
    bool isFrontCamera = true,
    DeviceOrientation deviceOrientation = DeviceOrientation.landscapeLeft,
  }) async {
    if (!_initialized) return null;

    final rotation = CameraImageUtils.detectionRotationDegrees(
      width: image.width,
      height: image.height,
      sensorOrientation: sensorOrientation,
      isFrontCamera: isFrontCamera,
      deviceOrientation: deviceOrientation,
    );

    final result = await KwonMediapipeLandmarker.detectFromCamera(
      planes: image.planes.map((p) => p.bytes).toList(),
      width: image.width,
      height: image.height,
      rotation: rotation,
      format: image.format.group.name,
      bytesPerRow: image.planes.map((p) => p.bytesPerRow).toList(),
    );

    final face = result.face;
    if (face == null || face.landmarks.isEmpty) return null;

    final points = <Offset>[];
    double minX = 1, minY = 1, maxX = 0, maxY = 0;
    // iOS 原生插件对 X 做了 1-x「镜像补偿」，但 camera_avfoundation 取流
    // 已通过 isVideoMirrored 水平镜像，再翻一次会与预览左右相反。
    final undoPluginMirrorX = Platform.isIOS;
    for (final lm in face.landmarks) {
      final rawX = lm.x.clamp(0.0, 1.0).toDouble();
      final x = undoPluginMirrorX ? (1.0 - rawX) : rawX;
      final y = lm.y.clamp(0.0, 1.0).toDouble();
      points.add(Offset(x, y));
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }

    final expression = _classifier.classify(face.blendshapes);

    // 注视方向：复用插件的 FaceResultHelper（来自 8 个 eyeLook blendshape）。
    // -1..1，正=右/下。主脸携带，供虚拟人物注视跟随使用。
    final gazeX = face.horizontalGazeDirection;
    final gazeY = face.verticalGazeDirection;

    // 眼睛闭合：取左右 eyeBlink 均值（0..1）。供时序聚合判定「困倦」。
    final b = face.blendshapes;
    final blinkL = (b[FaceBlendshape.eyeBlinkLeft] ?? 0).clamp(0.0, 1.0);
    final blinkR = (b[FaceBlendshape.eyeBlinkRight] ?? 0).clamp(0.0, 1.0);
    final eyeBlink = (blinkL + blinkR) / 2.0;

    return FaceOverlay(
      landmarks: points,
      boundingBox: Rect.fromLTRB(minX, minY, maxX, maxY),
      expression: expression,
      gazeX: gazeX,
      gazeY: gazeY,
      eyeBlink: eyeBlink,
    );
  }

  Future<void> dispose() async {
    if (!_initialized) return;
    await KwonMediapipeLandmarker.dispose();
    _initialized = false;
  }
}
