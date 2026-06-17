import 'dart:io' show Platform;
import 'dart:math' as math;
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:hand_detection/hand_detection.dart'
    show CameraFrameRotation, rotationForFrame;
import 'package:image/image.dart' as img;

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

  /// 转换并按 [rotationDegrees] 摆正为竖直图像。失败返回 null。
  static img.Image? toUprightImage(CameraImage image, int rotationDegrees) {
    img.Image? rgb;
    switch (image.format.group) {
      case ImageFormatGroup.yuv420:
        rgb = _fromYuv420(image);
        break;
      case ImageFormatGroup.bgra8888:
        rgb = _fromBgra8888(image);
        break;
      default:
        rgb = null;
    }
    if (rgb == null) return null;
    if (rotationDegrees % 360 != 0) {
      rgb = img.copyRotate(rgb, angle: rotationDegrees);
    }
    return rgb;
  }

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

  static img.Image? _fromYuv420(CameraImage image) {
    // iOS camera 插件 yuv420 为 NV12（2 平面）；Android 多为 3 平面 NV21/I420。
    if (image.planes.length == 2) {
      return _fromNv12(image);
    }
    if (image.planes.length >= 3) {
      return _fromYuv3Plane(image);
    }
    return null;
  }

  /// NV12：plane0=Y，plane1=交错 UV（iOS 默认）。
  static img.Image _fromNv12(CameraImage image) {
    final width = image.width;
    final height = image.height;
    final out = img.Image(width: width, height: height);

    final yPlane = image.planes[0];
    final uvPlane = image.planes[1];
    final yRowStride = yPlane.bytesPerRow;
    final uvRowStride = uvPlane.bytesPerRow;
    final yBytes = yPlane.bytes;
    final uvBytes = uvPlane.bytes;

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
  static img.Image _fromYuv3Plane(CameraImage image) {
    final width = image.width;
    final height = image.height;
    final out = img.Image(width: width, height: height);

    final yPlane = image.planes[0];
    final uPlane = image.planes[1];
    final vPlane = image.planes[2];

    final yRowStride = yPlane.bytesPerRow;
    final uvRowStride = uPlane.bytesPerRow;
    final uvPixelStride = uPlane.bytesPerPixel ?? 1;

    final yBytes = yPlane.bytes;
    final uBytes = uPlane.bytes;
    final vBytes = vPlane.bytes;

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

  static img.Image _fromBgra8888(CameraImage image) {
    final plane = image.planes.first;
    return img.Image.fromBytes(
      width: image.width,
      height: image.height,
      bytes: plane.bytes.buffer,
      rowStride: plane.bytesPerRow,
      order: img.ChannelOrder.bgra,
    );
  }
}
