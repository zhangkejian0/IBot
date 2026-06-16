import 'dart:math' as math;
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:image/image.dart' as img;

/// 相机帧像素转换工具。
///
/// 将 [CameraImage]（Android 通常为 YUV420、iOS 为 BGRA8888）转换为
/// `package:image` 的 [img.Image]，并按传感器朝向摆正，便于裁剪人脸做身份识别。
class CameraImageUtils {
  CameraImageUtils._();

  /// 转换并按 [sensorOrientation] 摆正为竖直图像。失败返回 null。
  static img.Image? toUprightImage(CameraImage image, int sensorOrientation) {
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
    if (sensorOrientation % 360 != 0) {
      rgb = img.copyRotate(rgb, angle: sensorOrientation);
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

  static img.Image _fromYuv420(CameraImage image) {
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
