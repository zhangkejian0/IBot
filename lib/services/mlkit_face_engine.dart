import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:google_mlkit_face_detection/google_mlkit_face_detection.dart';
import 'package:image/image.dart' as img;

import 'camera_image_utils.dart';

/// ML Kit 多脸检测引擎。
///
/// 与单脸的 [FaceEngine]（MediaPipe，仅返回 1 张脸）互补：ML Kit 天然支持
/// 多人脸检测，本引擎负责返回每张脸的归一化包围盒，供身份识别逐张裁剪比对。
///
/// **坐标空间对齐**：ML Kit 原生仅接受单平面字节（Android nv21 / iOS
/// bgra8888），无法直接吃项目的 YUV420 多平面帧。这里复用已验证可用的
/// [CameraImageUtils.toUprightImage] 先得到摆正后的 RGB [img.Image]，再转成
/// BGRA 单平面、以 `rotation0deg` 喂给 ML Kit。于是 [Face.boundingBox] 直接
/// 落在「摆正图像」像素空间，归一化后与 [CameraImageUtils.cropNormalized]
/// 所期望的 0..1 空间完全一致——避免双引擎坐标基准错位。
class MlKitFaceEngine {
  final int maxFaces;
  FaceDetector? _detector;
  bool _initialized = false;

  MlKitFaceEngine({this.maxFaces = 3});

  bool get isInitialized => _initialized;

  Future<void> initialize() async {
    if (_initialized) return;
    _detector = FaceDetector(
      options: FaceDetectorOptions(
        performanceMode: FaceDetectorMode.accurate,
        minFaceSize: 0.1, // 两人并排时每张脸占比较小，用 ML Kit 默认下限避免漏检。
        // 只需要包围盒做身份识别裁剪；关闭 landmarks/contours/classification
        // 以降低开销。trackingId 在固定单设备场景下意义不大，也关闭。
        enableTracking: false,
      ),
    );
    _initialized = true;
  }

  /// 检测多张人脸，返回归一化（0..1）包围盒列表，按面积降序（主脸在前），
  /// 至多 [maxFaces] 个。[sensorRotation] 为与 [CameraImageUtils.toUprightImage]
  /// 一致的摆正旋转角（度）。
  Future<List<Rect>> process(
    CameraImage image, {
    required int sensorRotation,
    required bool isFrontCamera,
    required DeviceOrientation deviceOrientation,
  }) async {
    final detector = _detector;
    if (detector == null || !_initialized) return const [];

    // toUprightImage 仅按 sensorOrientation 摆正，与本引擎传入的旋转基准保持
    // 一致：二者都用 sensorOrientation，得到同一张竖直图像。
    final upright = CameraImageUtils.toUprightImage(image, sensorRotation);
    if (upright == null) return const [];

    final w = upright.width;
    final h = upright.height;
    if (w <= 0 || h <= 0) return const [];

    final bytes = upright.getBytes(order: img.ChannelOrder.rgba);
    // 用 fromBitmap（RGBA）而非 fromBytes(bgra8888)：ML Kit 在 Android 原生
    // 端的 bytes 分支只接受 NV21/YV12，bgra8888 仅 iOS 支持，会导致
    // "ImageFormat is not supported" 错误。fromBitmap 走 ARGB_8888 Bitmap
    // 路径，跨平台通用。图已摆正，rotation 传 0。
    final input = InputImage.fromBitmap(
      bitmap: Uint8List.fromList(bytes),
      width: w,
      height: h,
      rotation: 0,
    );

    final List<Face> faces;
    try {
      faces = await detector.processImage(input);
    } catch (e) {
      debugPrint('[MlKitFace] detect error: $e');
      return const [];
    }

    // 临时调试日志：定位「多人脸只识别一个」问题。
    debugPrint('[MlKitFace] upright=${w}x$h rotate=$sensorRotation '
        'rawFaces=${faces.length}');

    final boxes = <_Box>[];
    for (final f in faces) {
      final b = f.boundingBox;
      // 归一化到 0..1（相对摆正图像），并 clamp 防越界。
      final nx = (b.left / w).clamp(0.0, 1.0).toDouble();
      final ny = (b.top / h).clamp(0.0, 1.0).toDouble();
      final nx2 = (b.right / w).clamp(0.0, 1.0).toDouble();
      final ny2 = (b.bottom / h).clamp(0.0, 1.0).toDouble();
      final area = (nx2 - nx) * (ny2 - ny);
      if (area <= 0) continue;
      debugPrint('[MlKitFace] box px=(${b.left.round()},${b.top.round()},'
          '${b.right.round()},${b.bottom.round()}) norm=$area');
      boxes.add(_Box(Rect.fromLTRB(nx, ny, nx2, ny2), area));
    }
    // 面积降序：主脸（最大）在前。
    boxes.sort((a, b) => b.area.compareTo(a.area));
    return boxes.take(maxFaces).map((e) => e.rect).toList(growable: false);
  }

  Future<void> dispose() async {
    await _detector?.close();
    _detector = null;
    _initialized = false;
  }
}

class _Box {
  final Rect rect;
  final double area;
  const _Box(this.rect, this.area);
}
