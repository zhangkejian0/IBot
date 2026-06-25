import 'dart:io' show Platform;
import 'dart:math' as math;
import 'dart:typed_data';
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

  /// 把相机帧抽取成可跨 isolate 发送的载荷（只含原始平面字节 + 元数据）。
  ///
  /// **必须在主 isolate、且在任何 `await` 之前**同步调用：相机底层缓冲会在
  /// 帧回调返回（或 await 让出事件循环）后被原生层回收，过后再读 [image] 的
  /// 平面字节会得到失效数据。抽取后即可安全交给 [Isolate.run] 做转换。
  static CameraConvertPayload payloadFrom(CameraImage image, int rotationDegrees) {
    return CameraConvertPayload(
      planes: image.planes.map((p) => p.bytes).toList(growable: false),
      bytesPerRow: image.planes.map((p) => p.bytesPerRow).toList(growable: false),
      bytesPerPixel:
          image.planes.map((p) => p.bytesPerPixel ?? 1).toList(growable: false),
      width: image.width,
      height: image.height,
      formatGroup: image.format.group,
      rotation: rotationDegrees,
    );
  }

  /// 转换并按 [rotationDegrees] 摆正为竖直图像。失败返回 null。
  ///
  /// 注意：这是 **CPU 密集的逐像素 YUV→RGB 转换**，在主 isolate 直接调用会
  /// 卡 UI 线程。需要在主 isolate 调用时，应改用 [payloadFrom] +
  /// [uprightRgbaFromPayload] 配合 [Isolate.run] 放到后台执行。
  static img.Image? toUprightImage(CameraImage image, int rotationDegrees) {
    return _toUpright(payloadFrom(image, rotationDegrees));
  }

  /// 在后台 isolate 执行：把相机载荷转为「摆正后的 RGBA 字节」+ 尺寸。
  /// 纯函数、只依赖可发送入参，可安全用于 [Isolate.run]。
  static UprightRgba? uprightRgbaFromPayload(CameraConvertPayload payload) {
    final rgb = _toUpright(payload);
    if (rgb == null) return null;
    final bytes = rgb.getBytes(order: img.ChannelOrder.rgba);
    return UprightRgba(bytes: bytes, width: rgb.width, height: rgb.height);
  }

  static img.Image? _toUpright(CameraConvertPayload p) {
    img.Image? rgb;
    switch (p.formatGroup) {
      case ImageFormatGroup.yuv420:
        if (p.planes.length == 2) {
          rgb = _fromNv12(p);
        } else if (p.planes.length >= 3) {
          rgb = _fromYuv3Plane(p);
        }
        break;
      case ImageFormatGroup.bgra8888:
        rgb = _fromBgra8888(p);
        break;
      default:
        rgb = null;
    }
    if (rgb == null) return null;
    if (p.rotation % 360 != 0) {
      rgb = img.copyRotate(rgb, angle: p.rotation);
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

  /// NV12：plane0=Y，plane1=交错 UV（iOS 默认）。
  static img.Image _fromNv12(CameraConvertPayload image) {
    final width = image.width;
    final height = image.height;
    final out = img.Image(width: width, height: height);

    final yRowStride = image.bytesPerRow[0];
    final uvRowStride = image.bytesPerRow[1];
    final yBytes = image.planes[0];
    final uvBytes = image.planes[1];

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
  static img.Image _fromYuv3Plane(CameraConvertPayload image) {
    final width = image.width;
    final height = image.height;
    final out = img.Image(width: width, height: height);

    final yRowStride = image.bytesPerRow[0];
    final uvRowStride = image.bytesPerRow[1];
    final uvPixelStride = image.bytesPerPixel[1];

    final yBytes = image.planes[0];
    final uBytes = image.planes[1];
    final vBytes = image.planes[2];

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

  static img.Image _fromBgra8888(CameraConvertPayload image) {
    return img.Image.fromBytes(
      width: image.width,
      height: image.height,
      bytes: image.planes.first.buffer,
      rowStride: image.bytesPerRow.first,
      order: img.ChannelOrder.bgra,
    );
  }
}

/// 可跨 isolate 发送的相机帧载荷：只含原始平面字节与必要元数据。
///
/// 由 [CameraImageUtils.payloadFrom] 在主 isolate 同步抽取，
/// 再交给 [Isolate.run] + [CameraImageUtils.uprightRgbaFromPayload] 后台转换。
class CameraConvertPayload {
  final List<Uint8List> planes;
  final List<int> bytesPerRow;
  final List<int> bytesPerPixel;
  final int width;
  final int height;
  final ImageFormatGroup formatGroup;
  final int rotation;

  const CameraConvertPayload({
    required this.planes,
    required this.bytesPerRow,
    required this.bytesPerPixel,
    required this.width,
    required this.height,
    required this.formatGroup,
    required this.rotation,
  });
}

/// 摆正后的 RGBA 字节 + 尺寸（[CameraImageUtils.uprightRgbaFromPayload] 的产物）。
class UprightRgba {
  final Uint8List bytes;
  final int width;
  final int height;

  const UprightRgba({
    required this.bytes,
    required this.width,
    required this.height,
  });
}
