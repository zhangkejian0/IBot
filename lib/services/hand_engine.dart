import 'dart:io';
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:hand_detection/hand_detection.dart';

import '../models/detection.dart';

/// 手势引擎：封装 [HandDetector]（MediaPipe 手部 + 手势识别，离线 TFLite）。
///
/// 输出每只手的 21 个关键点、左右手、以及 7 种手势之一。
class HandEngine {
  static const int _maxDim = 480;

  HandDetector? _detector;
  bool get isInitialized => _detector?.isReady ?? false;

  Future<void> initialize() async {
    if (isInitialized) return;
    _detector = await HandDetector.create(
      mode: HandMode.boxesAndLandmarks,
      landmarkModel: HandLandmarkModel.full,
      detectorConf: 0.6,
      maxDetections: 2,
      minLandmarkScore: 0.5,
      enableGestures: true,
      gestureMinConfidence: 0.5,
    );
  }

  /// 处理一帧相机图像，返回手部覆盖层列表（坐标归一化到 0..1）。
  Future<List<HandOverlay>> process(
    CameraImage image, {
    required int sensorOrientation,
    required bool isFrontCamera,
    required DeviceOrientation deviceOrientation,
  }) async {
    final detector = _detector;
    if (detector == null || !detector.isReady) return const [];

    final CameraFrameRotation? rotation = rotationForFrame(
      width: image.width,
      height: image.height,
      sensorOrientation: sensorOrientation,
      isFrontCamera: isFrontCamera,
      deviceOrientation: deviceOrientation,
    );

    final Size size = detectionSize(
      width: image.width,
      height: image.height,
      rotation: rotation,
      maxDim: _maxDim,
    );

    final hands = await detector.detectFromCameraImage(
      image,
      rotation: rotation,
      isBgra: Platform.isMacOS,
      maxDim: _maxDim,
    );

    final w = size.width <= 0 ? image.width.toDouble() : size.width;
    final h = size.height <= 0 ? image.height.toDouble() : size.height;

    // iOS 前置取流已由 AVCaptureConnection.isVideoMirrored 水平镜像，
    // MediaPipe 的 handedness 相对真实左右手会颠倒；Android 取流未镜像、
    // 覆盖层单独翻转坐标，handedness 无需校正（见 CameraImageUtils 注释）。
    final swapHandedness = Platform.isIOS && isFrontCamera;

    return hands.map((hand) {
      // 按关键点类型索引摆放，便于按骨架连线绘制。
      final ordered = List<Offset>.filled(21, Offset.zero);
      double minX = 1, minY = 1, maxX = 0, maxY = 0;
      var any = false;
      for (final lm in hand.landmarks) {
        final nx = (lm.x / w).clamp(0.0, 1.0).toDouble();
        final ny = (lm.y / h).clamp(0.0, 1.0).toDouble();
        final idx = lm.type.index;
        if (idx >= 0 && idx < ordered.length) {
          ordered[idx] = Offset(nx, ny);
        }
        any = true;
        if (nx < minX) minX = nx;
        if (ny < minY) minY = ny;
        if (nx > maxX) maxX = nx;
        if (ny > maxY) maxY = ny;
      }
      if (!any) {
        minX = minY = 0;
        maxX = maxY = 0;
      }
      return HandOverlay(
        landmarks: ordered,
        boundingBox: Rect.fromLTRB(minX, minY, maxX, maxY),
        handedness: _correctHandedness(hand.handedness, swap: swapHandedness),
        gesture: hand.gesture?.type,
        gestureConfidence: hand.gesture?.confidence ?? 0,
      );
    }).toList();
  }

  /// 水平镜像帧上 MediaPipe handedness 与真实左右手相反，需对调。
  static Handedness? _correctHandedness(Handedness? h, {required bool swap}) {
    if (!swap || h == null) return h;
    return h == Handedness.left ? Handedness.right : Handedness.left;
  }

  Future<void> dispose() async {
    await _detector?.dispose();
    _detector = null;
  }
}
