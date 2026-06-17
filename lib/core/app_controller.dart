import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:image/image.dart' as img;
import 'package:permission_handler/permission_handler.dart';

import '../face/gaze_zone_detector.dart';
import '../models/detection.dart';
import '../models/expression.dart';
import '../models/person.dart';
import '../services/camera_image_utils.dart';
import '../services/face_engine.dart';
import '../services/face_recognition_service.dart';
import '../services/hand_engine.dart';
import '../services/mlkit_face_engine.dart';
import '../services/person_repository.dart';
import '../services/static_server.dart';
import '../services/voice/llm_config.dart';
import '../services/voice/voice_assistant.dart';

/// 应用整体阶段。
enum AppPhase { loading, ready, error, permissionDenied }

/// 调试 / 显示开关。
class DisplaySettings {
  bool faceEnabled = true;
  bool handEnabled = true;
  bool identityEnabled = true;

  // 调试模式：开启时显示摄像头的人脸/手势识别画面，关闭时显示虚拟宠物网页。
  bool debugMode = false;

  // 调试可视化
  bool showFaceMesh = true;
  bool showFaceBox = true;
  bool showHandSkeleton = true;
  bool showLandmarkIndices = false;
  bool showExpression = true;
  bool showGesture = true;
  bool showIdentity = true;
  bool mirrorFrontCamera = true;

  // —— 语音助手 ——
  /// 语音助手总开关。
  bool voiceEnabled = false;
  /// 是否启用本地唤醒词监听(关闭则只能手动触发对话)。
  bool wakeWordEnabled = true;
  /// 是否启用 TTS 语音播报(关闭则回复仅以文字显示)。
  bool ttsEnabled = true;
  /// 唤醒词(可在设置页修改,运行时生效)。
  String wakeWord = '狗蛋';
  /// 云端服务 API Key(阿里云 ASR/TTS、LLM;阶段 3+ 使用,首版可留空占位)。
  String? asrApiKey;
  String? llmApiKey;
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
  final MlKitFaceEngine mlkitFaceEngine = MlKitFaceEngine();
  final FaceRecognitionService faceRecognition = FaceRecognitionService();
  final PersonRepository personRepository = PersonRepository();
  final DisplaySettings settings = DisplaySettings();
  final StaticServer staticServer = StaticServer();
  final VoiceAssistant voiceAssistant = VoiceAssistant();
  /// LLM 服务配置(DeepSeek 预设,可在设置页修改并持久化)。
  final LlmConfigStore llmConfigStore = LlmConfigStore();

  CameraController? _camera;
  CameraController? get camera => _camera;
  int _sensorOrientation = 90;
  bool _isFrontCamera = true;
  /// 是否为前置摄像头（用于注视水平镜像判断）。
  bool get isFrontCamera => _isFrontCamera;

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

  // 区域检测器：用于调试可视化
  final GazeZoneDetector _gazeZoneDetector = GazeZoneDetector();
  GazeZoneDetector get gazeZoneDetector => _gazeZoneDetector;

  bool _processing = false;
  bool _disposed = false;

  // 身份识别节流
  DateTime _lastIdentityRun = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _identityInterval = Duration(milliseconds: 1200);

  /// 按位置追踪的「身份 slot」：多人脸时为每张脸维持一个稳定身份，避免逐帧
  /// 串脸。每个 slot 记录归一化中心点、命中身份及其最后可见时刻。TTL 内即使
  /// 这一帧未跑识别（节流中），也能凭 slot 给该位置的人脸续上身份。
  final List<_IdentitySlot> _slots = [];
  static const Duration _identityTtl = Duration(seconds: 3);
  // 中心点距离阈值（归一化空间，0..1），超过则视为不同位置的人脸。
  // 收紧到 0.15：两人并排时各自中心间距常 < 0.25，过大会让两张脸塌缩到
  // 同一个 slot，导致串脸。
  static const double _slotMatchDistance = 0.15;

  /// 模型/相机加载完成后的固定缓冲，确保首帧前各引擎已彻底就绪。
  static const Duration _readyBuffer = Duration(seconds: 2);

  /// LLM 预置 DeepSeek API Key(仅首次写入配置文件时用;之后以设置页为准)。
  /// 真实生产中应移除此硬编码,改由用户在设置页录入。
  static const String _kPresetLlmApiKey = 'sk-1f6417c0177249de8b02a2a18207de2d';

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

      // 麦克风权限(语音助手用)。失败不阻断主流程:仅禁用语音功能。
      // 这里先请求权限并预热语音子服务,实际 start 延迟到用户在设置页打开总开关。
      final mic = await Permission.microphone.request();
      if (mic.isGranted) {
        voiceAssistant.markAvailable();
      } else {
        voiceAssistant.markUnavailable(reason: '未获得麦克风权限');
      }
      // 后台初始化(加载唤醒模型/配置凭证),不阻塞 ready。
      unawaited(voiceAssistant.initialize());

      _setLoading(0.5, '加载人脸表情模型…');
      await faceEngine.initialize();

      _setLoading(0.6, '加载多脸检测…');
      await mlkitFaceEngine.initialize();

      _setLoading(0.7, '加载手势识别模型…');
      await handEngine.initialize();

      _setLoading(0.85, '加载身份识别模型…');
      await faceRecognition.initialize();

      _setLoading(0.95, '读取已录入人物…');
      await personRepository.load();

      // 加载 LLM 配置(DeepSeek 预设),并注入语音助手的对话服务。
      // 预置 Key 仅在首次落盘时使用,之后以设置页录入为准。
      await llmConfigStore.load(defaultApiKey: _kPresetLlmApiKey);
      voiceAssistant.chat.configure(llmConfigStore.config);

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
      // 提前判断身份识别是否到期（仅依赖时间与开关，不依赖人脸结果），
      // 以便决定是否需要摆正图像，并与 MLKit 复用同一张 upright，避免
      // 同一帧重复做逐像素 YUV→RGB 转换（这是主线程最大的开销之一）。
      final needCapture = _captureCompleter != null;
      final identityDue = settings.identityEnabled &&
          faceRecognition.isAvailable &&
          DateTime.now().difference(_lastIdentityRun) >= _identityInterval;
      // MLKit 检测与身份识别/录入都用同一旋转基准(_detectionRotation)摆正图像，
      // 二者旋转角一致 → 算一次共享。仅当确实需要时才算（惰性）。
      final needsUpright =
          settings.faceEnabled || identityDue || needCapture;
      final upright = needsUpright
          ? CameraImageUtils.toUprightImage(image, _detectionRotation)
          : null;

      final faceFuture = settings.faceEnabled
          ? faceEngine.process(
              image,
              _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<FaceOverlay?>.value(null);
      final mlkitFuture = settings.faceEnabled
          ? mlkitFaceEngine.process(
              image,
              sensorRotation: _detectionRotation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
              upright: upright, // 复用上面算好的摆正图，不再重复 YUV 转换
            )
          : Future<List<Rect>>.value(const []);
      final handFuture = settings.handEnabled
          ? handEngine.process(
              image,
              sensorOrientation: _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<List<HandOverlay>>.value(const []);

      final mediapipeFace = await faceFuture; // 主脸：478 点 + 表情
      final mlkitBoxes = await mlkitFuture; // 多脸归一化框（主脸在前）
      final handResult = await handFuture;

      // 构建多脸列表：以 ML Kit 框为主，MediaPipe 主脸（网格/表情）按 IoU
      // 嫁接到最匹配的框上；其余脸仅含包围盒。
      final faces = <FaceOverlay>[];
      if (mlkitBoxes.isNotEmpty) {
        for (final box in mlkitBoxes) {
          faces.add(FaceOverlay(
            landmarks: const [],
            boundingBox: box,
            expression: ExpressionResult.neutral,
          ));
        }
        if (mediapipeFace != null) {
          // 选 IoU 最高的脸嫁接网格与表情。
          var bestIdx = -1;
          var bestIou = 0.0;
          for (var i = 0; i < faces.length; i++) {
            final iou = _iou(faces[i].boundingBox, mediapipeFace.boundingBox);
            if (iou > bestIou) {
              bestIou = iou;
              bestIdx = i;
            }
          }
          if (bestIdx >= 0) {
            faces[bestIdx] = FaceOverlay(
              landmarks: mediapipeFace.landmarks,
              // 以 MediaPipe 框为准（关键点空间一致），网格更贴合。
              boundingBox: mediapipeFace.boundingBox,
              expression: mediapipeFace.expression,
              // 注视只主脸（MediaPipe）携带，随嫁接一并保留。
              gazeX: mediapipeFace.gazeX,
              gazeY: mediapipeFace.gazeY,
            );
          }
        }
      } else if (mediapipeFace != null) {
        // ML Kit 漏检但 MediaPipe 命中：降级为单脸。
        faces.add(mediapipeFace);
      }

      // 身份识别（节流）+ 录入采样。
      // needCapture / identityDue / upright 已在帧开头算好并复用，
      // 这里直接用预计算结果，避免重复 YUV 转换。

      // 本帧每张脸的位置与识别结果（未识别为 null），用于一次性 slot 分配。
      final frameIdentities = <Rect, IdentityMatch?>{};
      if (faces.isNotEmpty && (needCapture || identityDue) && upright != null) {
        if (identityDue) {
            _lastIdentityRun = DateTime.now();
            // 逐脸裁剪 + 识别，先收集不写 slot（避免第一帧两张脸塌缩到同一 slot）。
            for (final f in faces) {
              final embedding =
                  faceRecognition.embed(CameraImageUtils.cropNormalized(
                upright,
                f.boundingBox,
              ));
              IdentityMatch? match;
              if (embedding != null) {
                match = faceRecognition.identify(
                    embedding, personRepository.people);
              }
              frameIdentities[f.boundingBox] = match;
            }
            // 用本帧内独占的贪心匹配把识别结果绑定到 slot。
            _assignSlots(frameIdentities);
          }

          if (needCapture) {
            // 录入只取主脸（面积最大，即 faces[0]）。
            final crop = CameraImageUtils.cropNormalized(
                upright, faces.first.boundingBox);
            final embedding = faceRecognition.embed(crop);
            final jpg = Uint8List.fromList(img.encodeJpg(crop, quality: 90));
            _completeCapture(
                EnrollCapture(jpgBytes: jpg, embedding: embedding));
          }
      }

      // 附着 slot 身份（TTL 内有效），并把结果写回各脸。
      // 本帧内独占查询：每张脸只匹配一个 slot，每个 slot 只被一张脸取用，
      // 避免两张脸都拿到同一个 slot 的身份（串脸）。
      final now = DateTime.now();
      final slotForFace = _matchSlotsToBoxes(faces.map((f) => f.boundingBox).toList(), now);
      final resultFaces = <FaceOverlay>[];
      for (var i = 0; i < faces.length; i++) {
        final f = faces[i];
        final identity = slotForFace[i]?.identity;
        resultFaces.add(identity == null
            ? f
            : FaceOverlay(
                landmarks: f.landmarks,
                boundingBox: f.boundingBox,
                expression: f.expression,
                identity: identity,
                gazeX: f.gazeX,
                gazeY: f.gazeY,
              ));
      }

      final mirror = settings.mirrorFrontCamera && _isFrontCamera;
      _result =
          DetectionResult(faces: resultFaces, hands: handResult, mirror: mirror);
      if (!_disposed) notifyListeners();
    } catch (e) {
      debugPrint('processFrame error: $e');
    }
  }

  /// 两个归一化矩形的交并比。
  double _iou(Rect a, Rect b) {
    final left = math.max(a.left, b.left);
    final top = math.max(a.top, b.top);
    final right = math.min(a.right, b.right);
    final bottom = math.min(a.bottom, b.bottom);
    final interW = right - left;
    final interH = bottom - top;
    if (interW <= 0 || interH <= 0) return 0;
    final inter = interW * interH;
    final union = a.width * a.height + b.width * b.height - inter;
    return union <= 0 ? 0 : inter / union;
  }

  /// 识别帧：把本帧各脸的识别结果绑定到位置 slot。
  ///
  /// 采用贪心最近邻匹配（按距离升序），每张脸与每个 slot 至多用一次，避免：
  /// 1. 第一帧两张脸塌缩到同一个 slot（旧 _updateSlot 逐脸独立查询的缺陷）；
  /// 2. 已有 slot 被两张脸同时更新导致身份互相覆盖。
  void _assignSlots(Map<Rect, IdentityMatch?> frameIdentities) {
    final now = DateTime.now();
    // 清理过期 slot。
    _slots.removeWhere((s) => now.difference(s.lastSeen) > _identityTtl);

    // 候选配对：(距离, 脸框, slot)，仅保留距离 < 阈值者。
    final pairs = <_Pair>[];
    for (final entry in frameIdentities.entries) {
      final c = _center(entry.key);
      for (final s in _slots) {
        final d = _dist(s.center, c);
        if (d < _slotMatchDistance) {
          pairs.add(_Pair(d, entry.key, s));
        }
      }
    }
    // 距离升序：优先配最近的。
    pairs.sort((a, b) => a.dist.compareTo(b.dist));
    final usedBoxes = <Rect>{};
    final usedSlots = <_IdentitySlot>{};
    for (final p in pairs) {
      if (usedBoxes.contains(p.box) || usedSlots.contains(p.slot)) continue;
      usedBoxes.add(p.box);
      usedSlots.add(p.slot);
      // 更新该 slot 的位置与身份。
      p.slot.center = _center(p.box);
      p.slot.lastSeen = now;
      final match = frameIdentities[p.box];
      if (match != null) p.slot.identity = match;
    }
    // 未匹配的脸：新建 slot。
    for (final entry in frameIdentities.entries) {
      if (usedBoxes.contains(entry.key)) continue;
      final s = _IdentitySlot(center: _center(entry.key), lastSeen: now);
      if (entry.value != null) s.identity = entry.value;
      _slots.add(s);
    }
  }

  /// 非识别帧：把本帧各脸框匹配到 TTL 内的 slot（独占），返回与 [boxes]
  /// 顺序对应的 slot 列表（未匹配为 null）。每张脸只取一个 slot、每个 slot
  /// 只被一张脸占用，避免两张脸显示同一身份。
  List<_IdentitySlot?> _matchSlotsToBoxes(List<Rect> boxes, DateTime now) {
    final pairs = <_Pair>[];
    for (var i = 0; i < boxes.length; i++) {
      final c = _center(boxes[i]);
      for (final s in _slots) {
        if (now.difference(s.lastSeen) > _identityTtl) continue;
        final d = _dist(s.center, c);
        if (d < _slotMatchDistance) {
          pairs.add(_Pair(d, boxes[i], s, faceIndex: i));
        }
      }
    }
    pairs.sort((a, b) => a.dist.compareTo(b.dist));
    final result = List<_IdentitySlot?>.filled(boxes.length, null);
    final usedSlots = <_IdentitySlot>{};
    for (final p in pairs) {
      if (result[p.faceIndex] != null || usedSlots.contains(p.slot)) continue;
      result[p.faceIndex] = p.slot;
      usedSlots.add(p.slot);
    }
    return result;
  }

  Offset _center(Rect r) => Offset(r.left + r.width / 2, r.top + r.height / 2);

  double _dist(Offset a, Offset b) => math.sqrt(
      (a.dx - b.dx) * (a.dx - b.dx) + (a.dy - b.dy) * (a.dy - b.dy));

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

  /// 虚拟宠物 HTTP 服务地址，启动后可用。
  String? _virtualPetUrl;
  Future<String>? _virtualPetStarting;
  String? get virtualPetUrl => _virtualPetUrl;

  /// 启动虚拟宠物静态文件服务，返回可访问的 URL。
  /// 多次调用会等待同一启动过程，避免重复启动。
  Future<String> startVirtualPetServer() async {
    if (_virtualPetUrl != null) return _virtualPetUrl!;
    _virtualPetStarting ??= _doStartVirtualPetServer();
    return _virtualPetStarting!;
  }

  Future<String> _doStartVirtualPetServer() async {
    _virtualPetUrl = await staticServer.start();
    _virtualPetStarting = null;
    notifyListeners();
    return _virtualPetUrl!;
  }

  /// 停止虚拟宠物服务。
  Future<void> stopVirtualPetServer() async {
    await staticServer.stop();
    _virtualPetUrl = null;
    _virtualPetStarting = null;
  }

  /// 修改显示/识别设置后刷新监听者（用于设置页开关）。
  void updateSettings(VoidCallback change) {
    change();
    // 关闭调试模式（即显示虚拟宠物网页）时按需启动本地服务；
    // 开启调试模式（即显示摄像头画面）时释放服务资源。
    if (!settings.debugMode && _virtualPetUrl == null) {
      startVirtualPetServer();
    } else if (settings.debugMode && _virtualPetUrl != null) {
      stopVirtualPetServer();
    }
    // 语音助手总开关 / 唤醒 / TTS 变更时按需启停(仿虚拟宠物服务管理模式)。
    _syncVoiceAssistant();
    notifyListeners();
  }

  /// 根据当前设置同步语音助手运行态。
  /// voiceEnabled 开则 start,关则 stop;运行中改唤醒/TTS 配置实时生效。
  void _syncVoiceAssistant() {
    final v = voiceAssistant;
    if (!v.isAvailable) return; // 无麦克风权限或未就绪,跳过
    v.wakeWordEnabled = settings.wakeWordEnabled;
    v.ttsEnabled = settings.ttsEnabled;
    // 唤醒词变更即时下发(开放词表支持运行时改词)。
    if (settings.wakeWord != v.wakeWord.keyword) {
      v.wakeWord.setKeyword(settings.wakeWord);
    }
    if (settings.voiceEnabled && !v.isRunning) {
      v.start();
    } else if (!settings.voiceEnabled && v.isRunning) {
      v.stop();
    }
  }

  /// 更新 LLM 配置:持久化到磁盘 + 实时下发到对话服务 + 重置对话历史。
  /// 由设置页的 AI 服务编辑入口调用。
  Future<void> updateLlmConfig(LlmConfig config) async {
    await llmConfigStore.save(config);
    voiceAssistant.chat.configure(config);
    voiceAssistant.chat.reset();
    notifyListeners();
  }

  Future<void> savePerson(Person person) async {
    await personRepository.upsert(person);
    notifyListeners();
  }

  Future<void> deletePerson(String id) async {
    await personRepository.delete(id);
    // 删除后清除可能残留的身份命中（遍历所有 slot）。
    for (final s in _slots) {
      if (s.identity?.person.id == id) s.identity = null;
    }
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
    mlkitFaceEngine.dispose();
    faceRecognition.dispose();
    voiceAssistant.dispose();
    staticServer.stop();
    super.dispose();
  }
}

/// 一个「位置身份槽」：在归一化坐标空间追踪某张脸的位置，并维持其身份在
/// 节流间隔内连续。多人脸时靠它把识别结果稳定绑定到对应位置，避免串脸。
class _IdentitySlot {
  Offset center;
  IdentityMatch? identity;
  DateTime lastSeen;
  _IdentitySlot({required this.center, required this.lastSeen});
}

/// 贪心匹配用的（脸框, slot）候选配对，按距离升序排序后逐个独占分配。
class _Pair {
  final double dist;
  final Rect box;
  final _IdentitySlot slot;
  final int faceIndex;
  const _Pair(this.dist, this.box, this.slot, {this.faceIndex = -1});
}
