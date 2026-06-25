import 'dart:io' show Platform;
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:kwon_mediapipe_landmarker/kwon_mediapipe_landmarker.dart';

import '../models/detection.dart';
import 'camera_image_utils.dart';
import 'expression_classifier.dart';

/// 人脸引擎 + 人体姿态引擎的聚合输出。
///
/// [face] 为 MediaPipe 478 点人脸(含表情/注视),[poses] 为人体 33 点骨骼。
/// 二者共享同一次 [KwonMediapipeLandmarker.detectFromCamera] 调用(全局单例
/// 插件同时开 face+pose),故不产生额外推理开销。无脸/无人时对应字段为
/// null / 空列表。
class FaceEngineResult {
  final FaceOverlay? face;
  final List<PoseOverlay> poses;

  const FaceEngineResult({this.face, this.poses = const []});

  bool get isEmpty => face == null && poses.isEmpty;
}

/// 人脸引擎：封装 [KwonMediapipeLandmarker]（MediaPipe Face Landmarker）。
///
/// 输出 478 个关键点 + 52 个 ARKit 兼容 blendshapes，并交给
/// [ExpressionClassifier] 得到 7 种表情之一。
///
/// 同时启用 **人体姿态(Pose)**:一次 `detectFromCamera` 既取人脸又取人体 33
/// 点骨骼,经 [process] 一并返回 [FaceEngineResult]。MediaPipe 插件是全局
/// 单例,face/pose 必须在同一次 `initialize` 开启,故人体检测合并进本引擎。
///
/// **健壮性**:[initialize] 先试 face+pose,若 pose 模型缺失(未把
/// `pose_landmarker_lite.task` 打进 `android/app/src/main/assets/`)或设备
/// 不支持导致初始化失败,自动回退为 face-only([isPoseAvailable]=false),
/// 保证人脸功能在任何设备上都可用。
class FaceEngine {
  final ExpressionClassifier _classifier;
  bool _initialized = false;

  /// 人体姿态(Pose)是否实际可用。
  /// native 层 face 与 pose 在同一次 initialize 里创建;若 pose 模型缺失
  /// (`pose_landmarker_lite.task` 未打进 Android assets)或某些设备初始化失败,
  /// initialize 会回退为 face-only 并把此标志置 false,确保人脸功能不受影响。
  /// [process] 据此决定是否解析 pose 结果。
  bool _poseAvailable = false;
  bool get isPoseAvailable => _poseAvailable;

  FaceEngine({ExpressionClassifier? classifier})
      : _classifier = classifier ?? const ExpressionClassifier();

  bool get isInitialized => _initialized;

  Future<void> initialize() async {
    if (_initialized) return;
    // 先尝试同时开 face+pose(一次 detectFromCamera 即可两得,零额外推理开销)。
    // native 层 face/pose 在同一个 try 里创建,pose 失败会让整个 initialize
    // 失败(face 也用不了)。故此处包一层:pose 失败则回退为 face-only,保证
    // 人脸功能在任何设备上都可用。
    try {
      await KwonMediapipeLandmarker.initialize(
        face: true,
        pose: true,
        faceOptions: const FaceOptions(
          numFaces: 1,
          minDetectionConfidence: 0.5,
          minTrackingConfidence: 0.5,
          outputBlendshapes: true,
        ),
        poseOptions: const PoseOptions(
          numPoses: 1,
          minDetectionConfidence: 0.5,
          minTrackingConfidence: 0.5,
        ),
      );
      _poseAvailable = true;
    } catch (e) {
      // face+pose 初始化失败(通常是 pose 模型缺失或设备不支持)。
      // dispose 释放失败尝试的 native 资源后,回退为仅 face。
      debugPrint('[FaceEngine] face+pose init failed ($e), 回退 face-only');
      try {
        await KwonMediapipeLandmarker.dispose();
      } catch (_) {}
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
      _poseAvailable = false;
    }
    _initialized = true;
  }

  /// 处理一帧相机图像，返回人脸覆盖层 + 人体骨骼覆盖层（聚合）；均无时返回 null。
  ///
  /// 一次 `detectFromCamera` 同时跑人脸(478 点)与人体姿态(33 点)(插件已同时
  /// 开启 face+pose),零额外调用开销。
  ///
  /// [sensorOrientation] 为相机传感器朝向（度）。[isFrontCamera] 与
  /// [deviceOrientation] 用于把图像旋转到与预览一致（与 HandEngine 共用
  /// [CameraImageUtils.detectionRotationDegrees]，否则人脸/手势坐标空间错位）。
  ///
  /// 返回 [FaceEngineResult]:[face] 含主脸关键点/表情/注视,[poses] 含检测到的
  /// 人体骨骼(MediaPipe 原生只回传第一个 pose,故列表至多 1 个)。
  Future<FaceEngineResult?> process(
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

    // iOS 原生插件对 X 做了 1-x「镜像补偿」，但 camera_avfoundation 取流
    // 已通过 isVideoMirrored 水平镜像，再翻一次会与预览左右相反。
    // face/pose 共用此补偿(同一帧、同一插件实例)。
    final undoPluginMirrorX = Platform.isIOS;

    // —— 人脸(478 点)——
    FaceOverlay? faceOverlay;
    final face = result.face;
    if (face != null && face.landmarks.isNotEmpty) {
      final points = <Offset>[];
      double minX = 1, minY = 1, maxX = 0, maxY = 0;
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

      faceOverlay = FaceOverlay(
        landmarks: points,
        boundingBox: Rect.fromLTRB(minX, minY, maxX, maxY),
        expression: expression,
        gazeX: gazeX,
        gazeY: gazeY,
        eyeBlink: eyeBlink,
      );
    }

    // —— 人体姿态(33 点)——
    // 仅当 pose 实际可用时解析(初始化时若 pose 失败已回退 face-only)。
    // 按 PoseLandmarkIndex 顺序摆位,便于按 poseConnections 骨架连线绘制
    // (范式同 HandEngine)。归一化坐标系已是 0..1,直接取用。
    final poses = <PoseOverlay>[];
    if (_poseAvailable) {
      final pose = result.pose;
      if (pose != null && pose.landmarks.isNotEmpty) {
        final ordered = List<Offset>.filled(33, Offset.zero);
        double minX = 1, minY = 1, maxX = 0, maxY = 0;
        var any = false;
        for (final lm in pose.landmarks) {
          final rawX = lm.x.clamp(0.0, 1.0).toDouble();
          final x = undoPluginMirrorX ? (1.0 - rawX) : rawX;
          final y = lm.y.clamp(0.0, 1.0).toDouble();
          final idx = lm.index;
          if (idx >= 0 && idx < ordered.length) {
            ordered[idx] = Offset(x, y);
          }
          any = true;
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
        if (any) {
          poses.add(PoseOverlay(
            landmarks: ordered,
            boundingBox: Rect.fromLTRB(minX, minY, maxX, maxY),
          ));
        }
      }
    }

    if (faceOverlay == null && poses.isEmpty) return null;
    return FaceEngineResult(face: faceOverlay, poses: poses);
  }

  Future<void> dispose() async {
    if (!_initialized) return;
    await KwonMediapipeLandmarker.dispose();
    _initialized = false;
    _poseAvailable = false;
  }
}
