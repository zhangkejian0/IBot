import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:image/image.dart' as img;
import 'package:permission_handler/permission_handler.dart';

import '../models/detection.dart';
import '../models/person.dart';
import '../services/camera_image_utils.dart';
import '../services/face_engine.dart';
import '../services/face_recognition_service.dart';
import '../services/hand_engine.dart';
import '../services/person_repository.dart';

/// 应用整体阶段。
enum AppPhase { loading, ready, error, permissionDenied }

/// 调试 / 显示开关。
class DisplaySettings {
  bool faceEnabled = true;
  bool handEnabled = true;
  bool identityEnabled = true;

  // 调试可视化
  bool showFaceMesh = true;
  bool showFaceBox = true;
  bool showHandSkeleton = true;
  bool showLandmarkIndices = false;
  bool showExpression = true;
  bool showGesture = true;
  bool showIdentity = true;
  bool mirrorFrontCamera = true;
}

/// 一次人脸采样的结果（用于「认识我」录入）。
class EnrollCapture {
  final Uint8List jpgBytes;
  final List<double>? embedding;
  const EnrollCapture({required this.jpgBytes, this.embedding});

  bool get hasEmbedding => embedding != null && embedding!.isNotEmpty;
}

/// 应用核心控制器：负责启动加载、相机取流，并把每帧分发给三个识别引擎。
class AppController extends ChangeNotifier {
  final FaceEngine faceEngine = FaceEngine();
  final HandEngine handEngine = HandEngine();
  final FaceRecognitionService faceRecognition = FaceRecognitionService();
  final PersonRepository personRepository = PersonRepository();
  final DisplaySettings settings = DisplaySettings();

  CameraController? _camera;
  CameraController? get camera => _camera;
  int _sensorOrientation = 90;
  bool _isFrontCamera = true;

  AppPhase _phase = AppPhase.loading;
  AppPhase get phase => _phase;

  double _loadingProgress = 0;
  double get loadingProgress => _loadingProgress;
  String _loadingMessage = '准备中…';
  String get loadingMessage => _loadingMessage;
  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  DetectionResult _result = const DetectionResult();
  DetectionResult get result => _result;

  bool _processing = false;
  bool _disposed = false;

  // 身份识别节流
  DateTime _lastIdentityRun = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _identityInterval = Duration(milliseconds: 1200);
  IdentityMatch? _stickyIdentity;
  DateTime _stickyIdentityTime = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _identityTtl = Duration(seconds: 3);

  /// 模型/相机加载完成后的固定缓冲，确保首帧前各引擎已彻底就绪。
  static const Duration _readyBuffer = Duration(seconds: 2);

  // 录入采样请求
  Completer<EnrollCapture?>? _captureCompleter;

  CameraController? get cameraController => _camera;

  void _setLoading(double progress, String message) {
    _loadingProgress = progress;
    _loadingMessage = message;
    notifyListeners();
  }

  /// 启动加载流程：权限 → 相机 → 模型 → 数据。
  Future<void> initialize() async {
    try {
      _setLoading(0.05, '请求摄像头权限…');
      final status = await Permission.camera.request();
      if (!status.isGranted) {
        _phase = AppPhase.permissionDenied;
        _errorMessage = '未获得摄像头权限，请在系统设置中开启后重试。';
        notifyListeners();
        return;
      }

      _setLoading(0.2, '初始化摄像头…');
      await _initCamera();

      _setLoading(0.5, '加载人脸表情模型…');
      await faceEngine.initialize();

      _setLoading(0.7, '加载手势识别模型…');
      await handEngine.initialize();

      _setLoading(0.85, '加载身份识别模型…');
      await faceRecognition.initialize();

      _setLoading(0.95, '读取已录入人物…');
      await personRepository.load();

      _setLoading(1.0, '准备就绪');
      await _camera?.startImageStream(_onFrame);

      // 固定额外等待：摄像头首帧与各模型推理管道仍需少量时间稳定，
      // 缓冲后再进入 ready，避免开场即出现掉帧/未识别。
      await Future<void>.delayed(_readyBuffer);

      _phase = AppPhase.ready;
      notifyListeners();
    } catch (e) {
      _phase = AppPhase.error;
      _errorMessage = '初始化失败：$e';
      notifyListeners();
    }
  }

  Future<void> _initCamera() async {
    final cameras = await availableCameras();
    if (cameras.isEmpty) {
      throw StateError('未检测到可用摄像头');
    }
    final selected = cameras.firstWhere(
      (c) => c.lensDirection == CameraLensDirection.front,
      orElse: () => cameras.first,
    );
    _sensorOrientation = selected.sensorOrientation;
    _isFrontCamera = selected.lensDirection == CameraLensDirection.front;

    final controller = CameraController(
      selected,
      ResolutionPreset.medium,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );
    await controller.initialize();
    // 强制横屏全屏 app：锁定预览/取流朝向，使 CameraValue.deviceOrientation
    // 从首帧起即为 landscape，避免 portraitUp→landscape 的 1~2 秒旋转闪烁。
    await controller.lockCaptureOrientation(DeviceOrientation.landscapeLeft);
    _camera = controller;
  }

  DeviceOrientation get _deviceOrientation =>
      _camera?.value.deviceOrientation ?? DeviceOrientation.landscapeLeft;

  /// 与 FaceEngine / HandEngine 完全一致的旋转基准：前置摄像头为
  /// `(sensorOrientation + deviceRotation) % 360`，后置为相减。
  /// 身份识别裁剪必须用同一旋转，否则裁剪框与归一化坐标空间错位。
  int get _detectionRotation {
    final deviceRotation = switch (_deviceOrientation) {
      DeviceOrientation.portraitUp => 0,
      DeviceOrientation.landscapeLeft => 90,
      DeviceOrientation.portraitDown => 180,
      DeviceOrientation.landscapeRight => 270,
    };
    return _isFrontCamera
        ? (_sensorOrientation + deviceRotation) % 360
        : (_sensorOrientation - deviceRotation + 360) % 360;
  }

  void _onFrame(CameraImage image) {
    if (_disposed || _processing || _phase != AppPhase.ready) return;
    _processing = true;
    _processFrame(image).whenComplete(() {
      if (!_disposed) _processing = false;
    });
  }

  Future<void> _processFrame(CameraImage image) async {
    try {
      final faceFuture = settings.faceEnabled
          ? faceEngine.process(
              image,
              _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<FaceOverlay?>.value(null);
      final handFuture = settings.handEnabled
          ? handEngine.process(
              image,
              sensorOrientation: _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<List<HandOverlay>>.value(const []);

      final faceResult = await faceFuture;
      final handResult = await handFuture;

      FaceOverlay? face = faceResult;

      // 身份识别（节流）+ 录入采样
      final needCapture = _captureCompleter != null;
      final identityDue = settings.identityEnabled &&
          faceRecognition.isAvailable &&
          DateTime.now().difference(_lastIdentityRun) >= _identityInterval;

      if (face != null && (needCapture || identityDue)) {
        final upright =
            CameraImageUtils.toUprightImage(image, _detectionRotation);
        if (upright != null) {
          final crop =
              CameraImageUtils.cropNormalized(upright, face.boundingBox);
          final embedding = faceRecognition.embed(crop);

          if (identityDue) {
            _lastIdentityRun = DateTime.now();
            if (embedding != null) {
              final match = faceRecognition.identify(
                  embedding, personRepository.people);
              if (match != null) {
                _stickyIdentity = match;
                _stickyIdentityTime = DateTime.now();
              }
            }
          }

          if (needCapture) {
            final jpg = Uint8List.fromList(img.encodeJpg(crop, quality: 90));
            _completeCapture(
                EnrollCapture(jpgBytes: jpg, embedding: embedding));
          }
        }
      }

      // 附着 sticky 身份（TTL 内有效）
      IdentityMatch? identity;
      if (DateTime.now().difference(_stickyIdentityTime) < _identityTtl) {
        identity = _stickyIdentity;
      }
      if (face != null && identity != null) {
        face = FaceOverlay(
          landmarks: face.landmarks,
          boundingBox: face.boundingBox,
          expression: face.expression,
          identity: identity,
        );
      }

      final mirror = settings.mirrorFrontCamera && _isFrontCamera;
      _result = DetectionResult(face: face, hands: handResult, mirror: mirror);
      if (!_disposed) notifyListeners();
    } catch (e) {
      debugPrint('processFrame error: $e');
    }
  }

  void _completeCapture(EnrollCapture capture) {
    final c = _captureCompleter;
    _captureCompleter = null;
    if (c != null && !c.isCompleted) c.complete(capture);
  }

  /// 请求一次人脸采样（用于录入）。若超时未捕获到人脸返回 null。
  Future<EnrollCapture?> captureFaceSample({
    Duration timeout = const Duration(seconds: 4),
  }) {
    if (!faceRecognition.isAvailable) {
      return Future.value(null);
    }
    final existing = _captureCompleter;
    if (existing != null) return existing.future;
    final c = Completer<EnrollCapture?>();
    _captureCompleter = c;
    return c.future.timeout(timeout, onTimeout: () {
      if (_captureCompleter == c) _captureCompleter = null;
      return null;
    });
  }

  /// 判断某特征向量是否已命中人脸库（供录入页逐帧去重）。
  /// 命中返回对应人物，未命中返回 null。底层调用 [FaceRecognitionService.identify]。
  IdentityMatch? findExistingIdentity(List<double> embedding) =>
      faceRecognition.identify(embedding, personRepository.people);

  /// 修改显示/识别设置后刷新监听者（用于设置页开关）。
  void updateSettings(VoidCallback change) {
    change();
    notifyListeners();
  }

  Future<void> savePerson(Person person) async {
    await personRepository.upsert(person);
    notifyListeners();
  }

  Future<void> deletePerson(String id) async {
    await personRepository.delete(id);
    // 删除后清除可能残留的身份命中
    if (_stickyIdentity?.person.id == id) _stickyIdentity = null;
    notifyListeners();
  }

  Future<String> saveAvatar(String personId, Uint8List jpg) async {
    final dir = await personRepository.avatarsDir();
    final path = '${dir.path}/$personId.jpg';
    await File(path).writeAsBytes(jpg);
    return path;
  }

  @override
  void dispose() {
    _disposed = true;
    final cam = _camera;
    _camera = null;
    if (cam != null) {
      if (cam.value.isStreamingImages) {
        cam.stopImageStream().catchError((_) {});
      }
      cam.dispose();
    }
    faceEngine.dispose();
    handEngine.dispose();
    faceRecognition.dispose();
    super.dispose();
  }
}
