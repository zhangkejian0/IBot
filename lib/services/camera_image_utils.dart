import 'dart:io' show Platform;
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:hand_detection/hand_detection.dart'
    show CameraFrameRotation, rotationForFrame;
import 'package:image/image.dart' as img;

/// 一帧相机图像的「可发送」快照：仅含原始字节与元数据，不含 [CameraImage]
/// 这类不可跨 isolate 传递的对象。用于把昂贵的 YUV→RGB + 旋转放到后台 isolate
/// （[CameraImageUtils.toUprightImageAsync]）执行，避免阻塞 UI 线程。
class UprightFrame {
  final int width;
  final int height;
  final int rotationDegrees;

  /// [CameraImage.format].group.name，如 `yuv420` / `bgra8888`。
  final String formatGroup;

  final List<Uint8List> planeBytes;
  final List<int> bytesPerRow;
  final List<int?> bytesPerPixel;

  const UprightFrame({
    required this.width,
    required this.height,
    required this.rotationDegrees,
    required this.formatGroup,
    required this.planeBytes,
    required this.bytesPerRow,
    required this.bytesPerPixel,
  });
}

/// 把 [UprightFrame] 转成摆正后的 RGB [img.Image]。失败返回 null。
///
/// 顶层函数（非类成员）以便作为 [compute] 的入口在独立 isolate 中运行。
img.Image? buildUpright(UprightFrame f) {
  img.Image? rgb;
  switch (f.formatGroup) {
    case 'yuv420':
      if (f.planeBytes.length == 2) {
        rgb = _fromNv12(f);
      } else if (f.planeBytes.length >= 3) {
        rgb = _fromYuv3Plane(f);
      }
      break;
    case 'bgra8888':
      rgb = _fromBgra8888(f);
      break;
    default:
      rgb = null;
  }
  if (rgb == null) return null;
  if (f.rotationDegrees % 360 != 0) {
    rgb = img.copyRotate(rgb, angle: f.rotationDegrees);
  }
  return rgb;
}

/// NV12：plane0=Y，plane1=交错 UV（iOS 默认）。
img.Image _fromNv12(UprightFrame f) {
  final width = f.width;
  final height = f.height;
  final out = img.Image(width: width, height: height);

  final yBytes = f.planeBytes[0];
  final uvBytes = f.planeBytes[1];
  final yRowStride = f.bytesPerRow[0];
  final uvRowStride = f.bytesPerRow[1];

  for (var y = 0; y < height; y++) {
    final yRow = y * yRowStride;
    final uvRow = (y >> 1) * uvRowStride;
    for (var x = 0; x < width; x++) {
      final yIndex = yRow + x;
      final uvIndex = uvRow + (x & ~1);
      if (yIndex >= yBytes.length || uvIndex + 1 >= uvBytes.length) {
        continue;
      }
      final yp = yBytes[yIndex];
      final u = uvBytes[uvIndex] - 128;
      final v = uvBytes[uvIndex + 1] - 128;
      out.setPixelRgb(
        x,
        y,
        (yp + 1.402 * v).round().clamp(0, 255),
        (yp - 0.344136 * u - 0.714136 * v).round().clamp(0, 255),
        (yp + 1.772 * u).round().clamp(0, 255),
      );
    }
  }
  return out;
}

/// 3 平面 YUV420（Android 常见 I420 / NV21 布局）。
img.Image _fromYuv3Plane(UprightFrame f) {
  final width = f.width;
  final height = f.height;
  final out = img.Image(width: width, height: height);

  final yBytes = f.planeBytes[0];
  final uBytes = f.planeBytes[1];
  final vBytes = f.planeBytes[2];

  final yRowStride = f.bytesPerRow[0];
  final uvRowStride = f.bytesPerRow[1];
  final uvPixelStride = f.bytesPerPixel[1] ?? 1;

  for (var y = 0; y < height; y++) {
    final yRow = y * yRowStride;
    final uvRow = (y >> 1) * uvRowStride;
    for (var x = 0; x < width; x++) {
      final yIndex = yRow + x;
      final uvIndex = uvRow + (x >> 1) * uvPixelStride;
      if (yIndex >= yBytes.length ||
          uvIndex >= uBytes.length ||
          uvIndex >= vBytes.length) {
        continue;
      }
      final yp = yBytes[yIndex];
      final up = uBytes[uvIndex] - 128;
      final vp = vBytes[uvIndex] - 128;

      final r = (yp + 1.402 * vp).round().clamp(0, 255);
      final g = (yp - 0.344136 * up - 0.714136 * vp).round().clamp(0, 255);
      final b = (yp + 1.772 * up).round().clamp(0, 255);
      out.setPixelRgb(x, y, r, g, b);
    }
  }
  return out;
}

img.Image _fromBgra8888(UprightFrame f) {
  return img.Image.fromBytes(
    width: f.width,
    height: f.height,
    bytes: f.planeBytes.first.buffer,
    rowStride: f.bytesPerRow.first,
    order: img.ChannelOrder.bgra,
  );
}

/// 相机帧像素转换工具。
///
/// 将 [CameraImage]（Android 通常为 YUV420、iOS 为 BGRA8888）转换为
/// `package:image` 的 [img.Image]，并按传感器朝向摆正，便于裁剪人脸做身份识别。
class CameraImageUtils {
  CameraImageUtils._();

  /// 计算送入检测模型的旋转角度（度）。
  ///
  /// iOS 横屏时 `camera` 插件已通过 `AVCaptureConnection.videoOrientation`
  /// 预旋转图像流，无需再转；Android 使用 sensor±device 合成角。
  /// 与 [HandEngine] / `hand_detection` 的 [rotationForFrame] 保持一致。
  static int detectionRotationDegrees({
    required int width,
    required int height,
    required int sensorOrientation,
    required bool isFrontCamera,
    required DeviceOrientation deviceOrientation,
  }) {
    final frameRotation = rotationForFrame(
      width: width,
      height: height,
      sensorOrientation: sensorOrientation,
      isFrontCamera: isFrontCamera,
      deviceOrientation: deviceOrientation,
    );
    return switch (frameRotation) {
      CameraFrameRotation.cw90 => 90,
      CameraFrameRotation.cw180 => 180,
      CameraFrameRotation.cw270 => 270,
      null => 0,
    };
  }

  /// 前置摄像头下是否对水平坐标取反（覆盖层镜像 / 注视跟随）。
  ///
  /// Android 取流未水平镜像，需翻转以贴合自拍预览（与 hand_detection 示例一致）。
  /// iOS 取流已由 `AVCaptureConnection.isVideoMirrored` 镜像，覆盖层不再翻转；
  /// 人脸 MediaPipe 插件多余的 X 翻转在 [FaceEngine] 中单独撤销。
  static bool get shouldFlipFrontCameraHorizontal => !Platform.isIOS;

  /// 从 [CameraImage] 抽取可发送的帧快照。
  static UprightFrame _frameOf(CameraImage image, int rotationDegrees) =>
      UprightFrame(
        width: image.width,
        height: image.height,
        rotationDegrees: rotationDegrees,
        formatGroup: image.format.group.name,
        planeBytes: image.planes.map((p) => p.bytes).toList(),
        bytesPerRow: image.planes.map((p) => p.bytesPerRow).toList(),
        bytesPerPixel: image.planes.map((p) => p.bytesPerPixel).toList(),
      );

  /// 同步转换并按 [rotationDegrees] 摆正为竖直图像。失败返回 null。
  ///
  /// 注意：这是主线程上的逐像素 YUV→RGB，开销很大。需要离屏识别（身份/录入）
  /// 时应优先用 [toUprightImageAsync] 把转换放到后台 isolate，避免卡顿。
  static img.Image? toUprightImage(CameraImage image, int rotationDegrees) =>
      buildUpright(_frameOf(image, rotationDegrees));

  /// 在后台 isolate 中转换并摆正，不阻塞 UI 线程。失败返回 null。
  static Future<img.Image?> toUprightImageAsync(
    CameraImage image,
    int rotationDegrees,
  ) =>
      compute(buildUpright, _frameOf(image, rotationDegrees));

  /// 按归一化矩形（0..1）从竖直图像中裁剪人脸区域，并向外扩展 [paddingRatio]。
  static img.Image cropNormalized(
    img.Image upright,
    Rect box, {
    double paddingRatio = 0.2,
  }) {
    final w = upright.width;
    final h = upright.height;
    final padW = box.width * paddingRatio;
    final padH = box.height * paddingRatio;
    final left = ((box.left - padW) * w).round().clamp(0, w - 1);
    final top = ((box.top - padH) * h).round().clamp(0, h - 1);
    final right = ((box.right + padW) * w).round().clamp(0, w - 1);
    final bottom = ((box.bottom + padH) * h).round().clamp(0, h - 1);
    final cropW = math.max(1, right - left);
    final cropH = math.max(1, bottom - top);
    return img.copyCrop(upright, x: left, y: top, width: cropW, height: cropH);
  }
}
