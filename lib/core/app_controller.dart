import 'dart:async';
import 'dart:io';
import 'dart:isolate';
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
import '../models/hand_gesture.dart';
import '../models/owner_profile.dart';
import '../models/person.dart';
import '../services/camera_image_utils.dart';
import '../services/face_engine.dart';
import '../services/face_recognition_service.dart';
import '../services/hand_engine.dart';
import '../services/mlkit_face_engine.dart';
import '../services/object_engine.dart';
import '../services/owner_profile_store.dart';
import '../services/persona_log_server.dart';
import '../services/persona_logger.dart';
import '../services/person_repository.dart';
import '../services/static_server.dart';
import '../services/base/base_service.dart';
import '../services/voice/llm_config.dart';
import '../services/voice/pophie_client.dart';
import '../services/voice/pophie_config.dart';
import '../services/voice/voice_assistant.dart';
import 'package:hand_detection/hand_detection.dart' show GestureType;

/// 应用整体阶段。
enum AppPhase { loading, onboarding, ready, error, permissionDenied }

/// 调试 / 显示开关。
class DisplaySettings {
  bool faceEnabled = true;
  bool handEnabled = true;
  bool identityEnabled = true;
  bool objectEnabled = true;

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
  bool showObject = true;
  bool mirrorFrontCamera = true;

  /// 使用前置摄像头(true)还是后置摄像头(false)。默认前置(陪伴场景对着用户)。
  /// 后置镜头通常画质更好、对焦更准,可在设置页切换以对比物体识别效果。
  bool useFrontCamera = true;

  // —— 底座控制 ——
  /// 底座控制总开关。
  bool baseControlEnabled = false;
  /// 底座控制模式：manual(手动) / faceFollow(人脸跟随) / demo(演示)。
  String baseControlMode = 'manual';

  // —— 语音助手 ——
  /// 语音助手总开关。
  bool voiceEnabled = false;
  /// 是否启用本地唤醒词监听(关闭则只能手动触发对话)。
  bool wakeWordEnabled = true;
  /// 是否启用 TTS 语音播报(关闭则回复仅以文字显示)。
  bool ttsEnabled = true;
  /// 唤醒词(可在设置页修改,运行时生效)。
  String wakeWord = '你好';
  /// 云端服务 API Key(阿里云 ASR/TTS、LLM;阶段 3+ 使用,首版可留空占位)。
  String? asrApiKey;
  String? llmApiKey;

  // —— 人物日志 ——
  /// 持久化记录开关：开启后按天记录识别到的人物/物体/表情/对话等。默认开。
  bool personaLogEnabled = true;
  /// 日志 HTTP 浏览服务开关：开启后可在同一局域网的电脑上访问查看日志。
  bool personaLogServerEnabled = false;
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
  final ObjectEngine objectEngine = ObjectEngine();
  final FaceRecognitionService faceRecognition = FaceRecognitionService();
  final PersonRepository personRepository = PersonRepository();
  final OwnerProfileStore ownerProfileStore = OwnerProfileStore();
  final DisplaySettings settings = DisplaySettings();
  final StaticServer staticServer = StaticServer();
  final BaseService baseService = BaseService();
  final VoiceAssistant voiceAssistant = VoiceAssistant();

  /// 人物行为日志记录器(按天持久化,供后续分析人物)。
  final PersonaLogger personaLogger = PersonaLogger();

  /// 人物日志 HTTP 浏览服务(局域网电脑端查看,方便调试)。
  late final PersonaLogServer personaLogServer =
      PersonaLogServer(personaLogger);
  /// LLM 服务配置(DeepSeek 预设,可在设置页修改并持久化)。
  final LlmConfigStore llmConfigStore = LlmConfigStore();

  /// Pophie 后端配置(地址/robotId/sessionId/音色,可在设置页修改并持久化)。
  final PophieConfigStore pophieConfigStore = PophieConfigStore();

  CameraController? _camera;
  CameraController? get camera => _camera;
  int _sensorOrientation = 90;
  bool _isFrontCamera = true;
  /// 是否为前置摄像头（用于注视水平镜像判断）。
  bool get isFrontCamera => _isFrontCamera;

  AppPhase _phase = AppPhase.loading;
  AppPhase get phase => _phase;

  // —— 主人档案 ——
  /// 当前主人档案；未注册时为 null。
  OwnerProfile? get ownerProfile => ownerProfileStore.profile;
  /// 是否已完成首次激活注册（owner_profile.json 存在）。
  bool get isOwnerRegistered => ownerProfileStore.isRegistered;
  /// 机器人显示名：已注册用主人起的名字，否则用默认「狗蛋」。
  String get robotDisplayName => ownerProfile?.robotName ?? '狗蛋';

  AppController() {
    // 转发 voiceAssistant 的变化:唤醒模型后台加载完成等子事件会改变
    // 设置页各语音开关的启用状态,需冒泡到 AppController 才能让 ListenableBuilder
    // 重建(设置页只监听 AppController)。
    voiceAssistant.addListener(_onVoiceAssistantChanged);
    // 转发 baseService 的连接/状态变化到 AppController,供调试页 ListenableBuilder 重建。
    baseService.addListener(_onBaseServiceChanged);
  }

  void _onVoiceAssistantChanged() {
    if (_disposed) return;
    notifyListeners();
  }

  void _onBaseServiceChanged() {
    if (_disposed) return;
    notifyListeners();
  }

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

  // 帧级检测节流:人脸(MediaPipe + ML Kit)/手势默认每帧都跑,~30fps 下
  // 既吃 CPU 又让原生库(TFLite/ML Kit)日志狂刷。这里按时间间隔跳过整帧,
  // 跳过的帧直接复用上一帧 DetectionResult——UI 注视/表情照常驱动,不会闪烁。
  // 200ms ≈ 5fps,显著降低 CPU 负载;注视/表情靠 JS 侧 spring/EMA 平滑补偿,
  // 身份识别另有 _identityInterval 节流。
  DateTime _lastDetectRun = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _detectInterval = Duration(milliseconds: 200);

  // 物体检测独立节流:物体移动远比注视/表情慢,没必要每个检测帧都跑。
  // 用更长的间隔(默认 500ms ≈ 2fps)进一步降低原生调用频率与每帧 RGBA 取字节
  // 的开销;两次物体检测之间复用上一帧 objects(覆盖层/感知不闪烁)。
  DateTime _lastObjectRun = DateTime.fromMillisecondsSinceEpoch(0);
  // YOLO 比 ML Kit 重,且走 JPEG 往返,节流放宽到 700ms(~1.4fps),配合后台执行。
  static const Duration _objectInterval = Duration(milliseconds: 700);
  List<ObjectOverlay> _lastObjects = const [];
  // 后台物体检测是否在跑:避免上一次未完成又触发,导致任务堆积。
  bool _objectBusy = false;

  // 「手持物体」判定:物体框与手框的中心距离阈值(归一化空间,0..1)。
  // 手部 landmarks 包围盒通常较紧,放宽到 0.22 兼顾「手握住物体边缘」的情形。
  static const double _heldDistance = 0.22;

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

  // —— 人物日志：感知节流 ——
  // 感知帧高频(~5fps),不能逐帧落盘。这里做「变化触发 + 最小间隔」节流：
  // 仅当关键信息(人物/表情/手势/物体/手持)相对上次记录发生变化,且距上次
  // 记录已超过最小间隔时,才写一条 perception 记录,避免日志被重复帧刷爆。
  String _lastPerceptionSig = '';
  DateTime _lastPerceptionLog = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _perceptionMinInterval = Duration(seconds: 2);

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

      _setLoading(0.78, '加载物体识别模型…');
      await objectEngine.initialize();

      _setLoading(0.85, '加载身份识别模型…');
      await faceRecognition.initialize();

      _setLoading(0.95, '读取已录入人物…');
      await personRepository.load();

      // 初始化人物日志记录器(按天持久化)。失败不阻断主流程。
      personaLogger.enabled = settings.personaLogEnabled;
      await personaLogger.initialize();

      // 加载 LLM 配置(DeepSeek 预设),并注入语音助手的对话服务。
      // 预置 Key 仅在首次落盘时使用,之后以设置页录入为准。
      await llmConfigStore.load(defaultApiKey: _kPresetLlmApiKey);
      voiceAssistant.chat.configure(llmConfigStore.config);

      // 加载 Pophie 后端配置并注入。语音对话全程走 /api/chat(STT+LLM+TTS)。
      await pophieConfigStore.load();
      voiceAssistant.pophie.configure(pophieConfigStore.config);
      _wireVoicePerception();

      // 加载主人档案（决定是否进入首次激活向导）。文件存在=已注册。
      await ownerProfileStore.load();

      _setLoading(1.0, '准备就绪');
      await _camera?.startImageStream(_onFrame);

      // 固定额外等待：摄像头首帧与各模型推理管道仍需少量时间稳定，
      // 缓冲后再进入下一阶段，避免开场即出现掉帧/未识别。
      await Future<void>.delayed(_readyBuffer);

      // 已注册主人 → 直接进入主界面；未注册 → 进入首次激活向导。
      // 向导的人脸录入依赖常驻相机与识别模型，故必须在就绪缓冲之后才分流。
      _phase = isOwnerRegistered ? AppPhase.ready : AppPhase.onboarding;
      notifyListeners();
    } catch (e) {
      _phase = AppPhase.error;
      _errorMessage = '初始化失败：$e';
      notifyListeners();
    }
  }

  /// 取流分辨率。物体识别(YOLO)对输入分辨率敏感:过低(如 low≈240p)时
  /// 细节不足,杯子/书/手机这类相似小物体极易被误判。medium(≈480p)在
  /// 准确率与 CPU/内存(逐像素 YUV→RGB 在主 isolate)之间取得平衡;若设备
  /// 算力不足出现卡顿,可下调回 low;追求更高精度可上调到 high(720p)。
  static const ResolutionPreset _captureResolution = ResolutionPreset.medium;

  Future<void> _initCamera() async {
    final cameras = await availableCameras();
    if (cameras.isEmpty) {
      throw StateError('未检测到可用摄像头');
    }
    final wantFront = settings.useFrontCamera;
    final preferred =
        wantFront ? CameraLensDirection.front : CameraLensDirection.back;
    final fallback =
        wantFront ? CameraLensDirection.back : CameraLensDirection.front;
    final selected = cameras.firstWhere(
      (c) => c.lensDirection == preferred,
      orElse: () => cameras.firstWhere(
        (c) => c.lensDirection == fallback,
        orElse: () => cameras.first,
      ),
    );
    _sensorOrientation = selected.sensorOrientation;
    _isFrontCamera = selected.lensDirection == CameraLensDirection.front;
    // 与实际选中的镜头保持一致(请求的镜头不存在时已回退)。
    settings.useFrontCamera = _isFrontCamera;

    final controller = CameraController(
      selected,
      _captureResolution,
      enableAudio: false,
      // 从源头限定 5fps 采集,与 _detectInterval=200ms 检测节流对齐:
      // 硬件不再以 30fps 回调再被丢弃 25 次,而是直接只产 5 帧/秒,
      // 省掉冗余的 _onFrame 回调与 YUV 缓冲分配/释放开销。
      // 虚拟宠物场景不显示摄像头预览,5fps 不会影响观感。
      fps: 5,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );
    await controller.initialize();
    // 强制横屏全屏 app：锁定预览/取流朝向，使 CameraValue.deviceOrientation
    // 从首帧起即为 landscape，避免 portraitUp→landscape 的 1~2 秒旋转闪烁。
    await controller.lockCaptureOrientation(DeviceOrientation.landscapeLeft);
    _camera = controller;
  }

  /// 是否正在切换摄像头(切换期间预览置黑、设置项禁用)。
  bool _switchingCamera = false;
  bool get isSwitchingCamera => _switchingCamera;

  /// 在前/后摄像头之间切换(供设置页对比不同镜头的识别效果)。
  Future<void> switchCamera() => setCamera(useFront: !_isFrontCamera);

  /// 切换到指定镜头:停旧流 → 释放旧控制器 → 用新镜头重建并重启取流。
  /// 切换期间复位与上一镜头相关的缓存(身份槽/物体/感知签名),避免错位残留。
  Future<void> setCamera({required bool useFront}) async {
    if (_switchingCamera) return;
    if (_camera != null && _isFrontCamera == useFront) return;
    _switchingCamera = true;
    settings.useFrontCamera = useFront;
    notifyListeners();
    final old = _camera;
    // 预览先置黑,避免 UI 继续引用即将释放的控制器。
    _camera = null;
    notifyListeners();
    try {
      if (old != null) {
        if (old.value.isStreamingImages) {
          await old.stopImageStream().catchError((_) {});
        }
        await old.dispose();
      }
      // 复位上一镜头遗留的识别缓存。
      _slots.clear();
      _lastObjects = const [];
      _lastPerceptionSig = '';
      await _initCamera();
      await _camera?.startImageStream(_onFrame);
    } catch (e) {
      debugPrint('[AppController] switchCamera failed: $e');
      _errorMessage = '切换摄像头失败：$e';
    } finally {
      _switchingCamera = false;
      notifyListeners();
    }
  }

  DeviceOrientation get _deviceOrientation =>
      _camera?.value.deviceOrientation ?? DeviceOrientation.landscapeLeft;

  /// 覆盖层是否水平镜像检测坐标以贴合预览（见 [CameraImageUtils.shouldFlipFrontCameraHorizontal]）。
  bool _effectiveMirrorOverlay() {
    if (!_isFrontCamera || !settings.mirrorFrontCamera) return false;
    return CameraImageUtils.shouldFlipFrontCameraHorizontal;
  }

  /// 与 FaceEngine / HandEngine 完全一致的旋转基准：前置摄像头为
  /// `(sensorOrientation + deviceRotation) % 360`，后置为相减。
  /// 身份识别裁剪必须用同一旋转，否则裁剪框与归一化坐标空间错位。
  void _onFrame(CameraImage image) {
    // 帧处理在 ready（主界面）与 onboarding（激活向导的人脸录入）阶段都需运行：
    // 向导的人脸录入依赖 captureFaceSample，它靠 _onFrame 内的 needCapture 分支满足。
    if (_disposed || _processing) return;
    if (_phase != AppPhase.ready && _phase != AppPhase.onboarding) return;
    // 帧级检测节流:未到间隔的帧跳过整帧检测。不重置图像(直接 return),
    // 上一帧 DetectionResult 保留,UI/虚拟宠物继续用旧值驱动注视与表情,
    // 视觉上不会闪烁,只是检测频率降到 ~15fps。
    final now = DateTime.now();
    if (now.difference(_lastDetectRun) < _detectInterval) return;
    _lastDetectRun = now;
    _processing = true;
    _processFrame(image).whenComplete(() {
      if (!_disposed) _processing = false;
    });
  }

  Future<void> _processFrame(CameraImage image) async {
    try {
      final detectionRotation = CameraImageUtils.detectionRotationDegrees(
        width: image.width,
        height: image.height,
        sensorOrientation: _sensorOrientation,
        isFrontCamera: _isFrontCamera,
        deviceOrientation: _deviceOrientation,
      );

      // 提前判断身份识别是否到期（仅依赖时间与开关，不依赖人脸结果），
      // 以便决定是否需要摆正图像，并与 MLKit 复用同一张 upright，避免
      // 同一帧重复做逐像素 YUV→RGB 转换（这是主线程最大的开销之一）。
      final needCapture = _captureCompleter != null;
      final identityDue = settings.identityEnabled &&
          faceRecognition.isAvailable &&
          DateTime.now().difference(_lastIdentityRun) >= _identityInterval;
      // 物体检测独立节流：到期才跑（间隔比检测帧更长，进一步省原生调用）。
      final objectDue = settings.objectEnabled &&
          objectEngine.isInitialized &&
          DateTime.now().difference(_lastObjectRun) >= _objectInterval;
      // MLKit 人脸/物体检测与身份识别/录入都用同一旋转基准(_detectionRotation)
      // 摆正图像，旋转角一致 → 算一次共享。仅当确实需要时才算（惰性）。
      final needsUpright = settings.faceEnabled ||
          identityDue ||
          needCapture ||
          objectDue;
      img.Image? upright;
      if (needsUpright) {
        try {
          upright = CameraImageUtils.toUprightImage(image, detectionRotation);
        } catch (e, st) {
          debugPrint('[Frame] toUprightImage error: $e\n$st');
        }
      }

      // ML Kit 人脸与物体引擎吃的都是「摆正后的 RGBA 字节」：同帧只取一次字节，
      // 两个引擎共享，避免重复的 getBytes 分配（降低主 isolate 负载/卡顿）。
      Uint8List? uprightRgba;
      int upW = 0, upH = 0;
      if (upright != null && (settings.faceEnabled || objectDue)) {
        upW = upright.width;
        upH = upright.height;
        uprightRgba = upright.getBytes(order: img.ChannelOrder.rgba);
      }

      final faceFuture = settings.faceEnabled
          ? faceEngine.process(
              image,
              _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<FaceOverlay?>.value(null);
      final mlkitFuture = settings.faceEnabled && uprightRgba != null
          ? mlkitFaceEngine.processRgba(uprightRgba, upW, upH)
          : Future<List<Rect>>.value(const []);
      final handFuture = settings.handEnabled
          ? handEngine.process(
              image,
              sensorOrientation: _sensorOrientation,
              isFrontCamera: _isFrontCamera,
              deviceOrientation: _deviceOrientation,
            )
          : Future<List<HandOverlay>>.value(const []);

      // 物体检测（YOLO11）：后台 fire-and-forget,**不**并入本帧 await。
      // 原因:predict 走「JPEG 编码 + 原生解码 + 推理」,耗时远高于人脸/手势;
      // 若 await 进本帧会把注视/表情每次拖慢数百毫秒。这里只在到期且空闲时
      // 触发一次后台任务,完成后更新 _lastObjects 并 notifyListeners。本帧结果
      // 直接复用 _lastObjects（覆盖层/感知不闪烁,只是物体刷新频率为 ~1-2fps）。
      if (objectDue && !_objectBusy && uprightRgba != null) {
        _objectBusy = true;
        _lastObjectRun = DateTime.now();
        unawaited(_runObjectDetection(uprightRgba, upW, upH));
      }

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

      // 手持物体推理：把本帧物体框与手框做就近匹配，命中者标记 heldByHand。
      // 用于回答「我手里拿着什么」——结合手部检测 + 物体识别的端侧空间叠加。
      // 关闭物体识别时清空缓存并输出空列表，避免旧物体框残留。
      if (!settings.objectEnabled) _lastObjects = const [];
      final objects = settings.objectEnabled
          ? _markHeldObjects(_lastObjects, handResult)
          : const <ObjectOverlay>[];

      final mirror = _effectiveMirrorOverlay();
      _result = DetectionResult(
        faces: resultFaces,
        hands: handResult,
        objects: objects,
        mirror: mirror,
      );
      _maybeLogPerception();
      if (!_disposed) notifyListeners();
    } catch (e) {
      debugPrint('processFrame error: $e');
    }
  }

  /// 变化触发的感知日志：把当前画面的人物/表情/手势/物体等写入人物日志。
  /// 仅在关键信息相对上次记录发生变化、且超过最小间隔时落盘(见节流字段说明)。
  /// 空场景(无人脸、无物体、无手势)不记录,避免无意义噪声。
  void _maybeLogPerception() {
    if (!settings.personaLogEnabled || !personaLogger.enabled) return;

    final face = _result.face;
    final identity = face?.identity;
    final person = identity?.person.name;
    final expression = face?.expression.expression.label;
    final gesture =
        _result.hands.isNotEmpty ? _result.hands.first.gesture?.label : null;
    final objects = <Map<String, dynamic>>[];
    for (final o in _result.objects) {
      final l = o.label;
      if (l != null && l.isNotEmpty) {
        // 去重：按名称去重，保留置信度最高的
        final existing = objects.where((m) => m['name'] == l).firstOrNull;
        if (existing == null) {
          objects.add({'name': l, 'confidence': o.confidence});
        } else if (o.confidence > (existing['confidence'] as double)) {
          existing['confidence'] = o.confidence;
        }
      }
    }
    objects.sort((a, b) => (a['name'] as String).compareTo(b['name'] as String));
    final held = _result.heldObject?.label;
    final faceCount = _result.faces.length;

    // 收集所有识别到的人物（多人脸场景）。
    final persons = <String>[];
    for (final f in _result.faces) {
      final name = f.identity?.person.name;
      if (name != null && name.isNotEmpty && !persons.contains(name)) {
        persons.add(name);
      }
    }

    // 空场景：什么都没识别到时不记录(减少噪声)。
    if (faceCount == 0 && objects.isEmpty && gesture == null) return;

    final objectNames = objects.map((o) => o['name'] as String).toList();
    final sig = '$person|${persons.join(",")}|$expression|$gesture'
        '|${objectNames.join(',')}|$held|$faceCount';
    final now = DateTime.now();
    final changed = sig != _lastPerceptionSig;
    final elapsedOk = now.difference(_lastPerceptionLog) >= _perceptionMinInterval;
    // 变化才记;但同一状态长时间持续也无需重复记(仅在变化时落盘)。
    if (!changed || !elapsedOk) return;

    _lastPerceptionSig = sig;
    _lastPerceptionLog = now;

    final scene = _buildSceneDescription(
      heldObject: _result.heldObject,
      objectNames: objectNames,
      identity: person,
    );

    personaLogger.log(PersonaLogEntry(
      timestamp: now,
      type: 'perception',
      person: person,
      persons: persons,
      relation: identity?.person.relation.label,
      expression: expression,
      gesture: gesture,
      objects: objects,
      heldObject: held,
      scene: scene,
      faceCount: faceCount,
    ));
  }

  /// 后台执行一次 YOLO 物体检测：在 Isolate 里把摆正图编码为 JPEG（重计算,
  /// 不占主 isolate）,再交给原生 YOLO 推理；完成后更新 _lastObjects 并通知 UI。
  /// 全程不阻塞 _processFrame 的人脸/注视主循环。
  Future<void> _runObjectDetection(Uint8List rgba, int w, int h) async {
    try {
      if (w <= 0 || h <= 0) return;
      final jpg = await Isolate.run(() => _encodeJpgFromRgba(rgba, w, h));
      if (_disposed) return;
      final objs = await objectEngine.predictJpeg(jpg, w, h);
      if (_disposed) return;
      _lastObjects = objs;
      notifyListeners();
    } catch (e) {
      debugPrint('[Frame] object detection error: $e');
    } finally {
      _objectBusy = false;
    }
  }

  /// 把物体标记为「正被手持」：物体框与任一手框重叠（IoU>0）或中心距离够近
  /// 即判定为手持。返回带 heldByHand 标记的新物体列表（不改原对象）。
  List<ObjectOverlay> _markHeldObjects(
    List<ObjectOverlay> objects,
    List<HandOverlay> hands,
  ) {
    if (objects.isEmpty || hands.isEmpty) return objects;
    return objects.map((o) {
      var held = false;
      for (final h in hands) {
        final overlap = _iou(o.boundingBox, h.boundingBox) > 0 ||
            o.boundingBox.overlaps(h.boundingBox);
        final near = _dist(o.center, h.boundingBox.center) < _heldDistance;
        if (overlap || near) {
          held = true;
          break;
        }
      }
      return held ? o.copyWith(heldByHand: true) : o;
    }).toList(growable: false);
  }

  /// 把归一化中心点量化成「左/中/右·上/中/下」的大概位置描述（中文）。
  /// 前置镜像生效时水平方向取反，使「左右」与用户自拍视角一致。
  String _locationLabel(Offset center) {
    var x = center.dx;
    if (_effectiveMirrorOverlay()) x = 1 - x;
    final h = x < 0.34 ? '左' : (x > 0.66 ? '右' : '中');
    final v = center.dy < 0.34 ? '上' : (center.dy > 0.66 ? '下' : '中');
    if (h == '中' && v == '中') return '画面中央';
    return '画面$v${h == '中' ? '' : h}方';
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

  /// 人物日志 HTTP 浏览服务地址(局域网),启动后可用。
  String? _personaLogUrl;
  Future<String>? _personaLogStarting;
  String? get personaLogUrl => _personaLogUrl;

  /// 启动人物日志 HTTP 服务并返回局域网访问地址。多次调用等待同一启动过程。
  Future<String> startPersonaLogServer() async {
    if (_personaLogUrl != null) return _personaLogUrl!;
    _personaLogStarting ??= _doStartPersonaLogServer();
    return _personaLogStarting!;
  }

  Future<String> _doStartPersonaLogServer() async {
    _personaLogUrl = await personaLogServer.start();
    _personaLogStarting = null;
    notifyListeners();
    return _personaLogUrl!;
  }

  /// 停止人物日志 HTTP 服务。
  Future<void> stopPersonaLogServer() async {
    await personaLogServer.stop();
    _personaLogUrl = null;
    _personaLogStarting = null;
    notifyListeners();
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
    // 人物日志记录开关实时生效。
    personaLogger.enabled = settings.personaLogEnabled;
    // 日志 HTTP 服务按开关启停。
    if (settings.personaLogServerEnabled && _personaLogUrl == null) {
      startPersonaLogServer();
    } else if (!settings.personaLogServerEnabled && _personaLogUrl != null) {
      stopPersonaLogServer();
    }
    notifyListeners();
  }

  /// 把端侧多模态感知(表情/身份/手势)接到语音助手:每轮对话上传后端,
  /// 并把后端回传的 session_id 持久化以延续会话记忆(文档 §2.4 / §1.2)。
  void _wireVoicePerception() {
    voiceAssistant.perceptionProvider = _buildPerception;
    voiceAssistant.userIdProvider = _currentUserId;
    voiceAssistant.onSessionPersist = (sessionId) async {
      final cfg = pophieConfigStore.config;
      if (cfg.sessionId == sessionId) return;
      await pophieConfigStore.save(cfg.copyWith(sessionId: sessionId));
    };
    // 每轮对话完成后把交互内容连同当前感知上下文写入人物日志。
    voiceAssistant.onInteraction = ({
      required String userText,
      required String replyText,
      String? robotState,
    }) =>
        _logConversation(
          userText: userText,
          replyText: replyText,
          robotState: robotState,
        );
  }

  /// 把一轮语音对话写入人物日志,附带当前画面的人物/表情/物体上下文,
  /// 便于后续把「说了什么」与「当时在场的人和场景」关联分析。
  void _logConversation({
    required String userText,
    required String replyText,
    String? robotState,
  }) {
    if (!settings.personaLogEnabled) return;
    final face = _result.face;
    final identity = face?.identity;
    final objects = <Map<String, dynamic>>[];
    for (final o in _result.objects) {
      final l = o.label;
      if (l != null && l.isNotEmpty) {
        // 去重：按名称去重，保留置信度最高的
        final existing = objects.where((m) => m['name'] == l).firstOrNull;
        if (existing == null) {
          objects.add({'name': l, 'confidence': o.confidence});
        } else if (o.confidence > (existing['confidence'] as double)) {
          existing['confidence'] = o.confidence;
        }
      }
    }
    personaLogger.log(PersonaLogEntry(
      timestamp: DateTime.now(),
      type: 'conversation',
      person: identity?.person.name,
      relation: identity?.person.relation.label,
      expression: face?.expression.expression.label,
      gesture: _result.hands.isNotEmpty
          ? _result.hands.first.gesture?.label
          : null,
      objects: objects,
      heldObject: _result.heldObject?.label,
      faceCount: _result.faces.length,
      userText: userText.isEmpty ? null : userText,
      replyText: replyText.isEmpty ? null : replyText,
      robotState: robotState,
    ));
  }

  /// 由当前识别结果构造 Pophie 感知上下文(表情/身份/手势/物体)。
  PophiePerception _buildPerception() {
    final face = _result.face;
    final expr = face != null ? _expressionApiKey(face.expression.expression) : null;
    final identity = face?.identity?.person.name;
    String? gesture;
    if (_result.hands.isNotEmpty) {
      gesture = _gestureApiKey(_result.hands.first.gesture);
    }

    // 物体感知：去重收集有标签的物体名；提取手持物体；拼装自然语言场景。
    final objects = _result.objects;
    final names = <String>[];
    for (final o in objects) {
      final l = o.label;
      if (l != null && l.isNotEmpty && !names.contains(l)) names.add(l);
    }
    final held = _result.heldObject;
    final heldName = held?.label;
    final scene = _buildSceneDescription(
      heldObject: held,
      objectNames: names,
      identity: identity,
    );

    return PophiePerception(
      facialExpression: expr,
      identity: identity,
      gestureType: gesture,
      objects: names.isEmpty ? null : names,
      heldObject: (heldName != null && heldName.isNotEmpty) ? heldName : null,
      scene: scene,
    );
  }

  /// 拼装端侧自然语言场景描述（最利于大模型理解「我手里拿着什么」）。
  /// 无任何物体信息时返回 null（不污染感知上下文）。
  String? _buildSceneDescription({
    required ObjectOverlay? heldObject,
    required List<String> objectNames,
    required String? identity,
  }) {
    final who = (identity != null && identity.isNotEmpty) ? identity : '用户';
    if (heldObject?.label != null && heldObject!.label!.isNotEmpty) {
      final loc = _locationLabel(heldObject.center);
      return '$who手里拿着${heldObject.label}（位于$loc）';
    }
    if (objectNames.isNotEmpty) {
      return '画面中可见：${objectNames.join('、')}';
    }
    return null;
  }

  /// 当前用户身份(认识我命中的主脸人名),作为 user_id 回显/溯源。
  String? _currentUserId() => _result.face?.identity?.person.name;

  /// 本端 [Expression] 枚举 → 后端 7 类表情 key(文档 §2.1)。
  static String _expressionApiKey(Expression e) {
    switch (e) {
      case Expression.neutral:
        return 'neutral';
      case Expression.happy:
        return 'happy';
      case Expression.sad:
        return 'sad';
      case Expression.surprised:
        return 'surprise';
      case Expression.angry:
        return 'angry';
      case Expression.disgusted:
        return 'disgust';
      case Expression.fearful:
        return 'fear';
    }
  }

  /// 手势类型 → 后端手势 key(文档 §2.4);无明确对应时返回 null。
  static String? _gestureApiKey(GestureType? g) {
    switch (g) {
      case GestureType.thumbUp:
        return 'thumbs_up';
      case GestureType.victory:
        return 'victory';
      case GestureType.closedFist:
        return 'fist';
      case GestureType.openPalm:
        return 'open_palm';
      case GestureType.pointingUp:
        return 'point';
      case GestureType.iLoveYou:
        return 'heart';
      case GestureType.thumbDown:
      case GestureType.unknown:
      case null:
        return null;
    }
  }

  /// 更新 Pophie 后端配置(设置页调用):持久化 + 实时下发到客户端。
  Future<void> updatePophieConfig(PophieConfig config) async {
    await pophieConfigStore.save(config);
    voiceAssistant.pophie.configure(config);
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

  // —— 主人档案：首次激活 / 重置 / 同步 ——

  /// 完成首次激活向导。
  ///
  /// 1. 若带有人脸样本：构造主脸 Person（relation=owner），填 embeddings，
  ///    存头像 + 存人物，回填 profile.personId / faceRegistered。
  /// 2. 本地档案立即生效并进入主界面（ready）——不阻塞于网络。
  /// 3. 后台 best-effort 同步到 Pophie：成功置 syncedToServer 并落盘，失败静默
  ///    （后续可由 [retryOwnerSync] 重试）。陪伴机器人离线必须可用。
  ///
  /// [faceSamples] 由向导人脸步骤采集的样本（jpg 缩略图 + embedding）。
  Future<void> completeOnboarding({
    required OwnerProfile profile,
    List<EnrollCapture>? faceSamples,
  }) async {
    var p = profile.copyWith(syncedToServer: false);

    if (faceSamples != null && faceSamples.isNotEmpty) {
      final valid = faceSamples.where((c) => c.hasEmbedding).toList();
      if (valid.isNotEmpty) {
        final person = Person(
          id: DateTime.now().microsecondsSinceEpoch.toString(),
          name: profile.nickname,
          relation: FamilyRelation.owner,
        );
        person.embeddings.addAll(
            valid.map((c) => c.embedding!).whereType<List<double>>());
        final avatarPath = await saveAvatar(person.id, valid.first.jpgBytes);
        person.avatarPath = avatarPath;
        await personRepository.upsert(person);
        p = p.copyWith(personId: person.id, faceRegistered: true);
      }
    }

    await ownerProfileStore.save(p);
    _phase = AppPhase.ready;
    notifyListeners();

    // 后台同步，不阻塞 UI。不 await：用户已进入主界面。
    unawaited(_syncOwnerToServer(p));
  }

  /// 重置主人：重新进入向导。
  ///
  /// best-effort 通知后端删除（失败不阻断本地重置）→ 清本地档案 → 删除主脸
  /// Person → 回到 onboarding 阶段（AppScope 会驱动根路由重建切回向导）。
  Future<void> resetOwner() async {
    final ownerPersonId = ownerProfile?.personId;
    // 后台删除后端档案，不阻塞。
    unawaited(voiceAssistant.pophie.deleteOwner());

    await ownerProfileStore.clear();
    if (ownerPersonId != null) {
      await deletePerson(ownerPersonId);
    }
    _phase = AppPhase.onboarding;
    notifyListeners();
  }

  /// 重试主人档案同步到 Pophie（供后台/设置页触发）。仅在未同步时实际发起。
  /// 返回是否已成功同步（含本就同步成功的情况）。
  Future<bool> retryOwnerSync() async {
    final p = ownerProfile;
    if (p == null || p.syncedToServer) return p?.syncedToServer ?? false;
    final ok = await _syncOwnerToServer(p);
    return ok;
  }

  /// 实际执行同步：成功则置 syncedToServer=true 并落盘。返回是否成功。
  Future<bool> _syncOwnerToServer(OwnerProfile profile) async {
    final ok = await voiceAssistant.pophie.registerOwner(profile);
    if (ok && !profile.syncedToServer) {
      final current = ownerProfile;
      // 避免覆盖期间 profile 已被改动（如重置）。
      if (current != null && current.nickname == profile.nickname) {
        await ownerProfileStore.save(current.copyWith(syncedToServer: true));
        if (!_disposed) notifyListeners();
      }
    }
    return ok;
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
    objectEngine.dispose();
    faceRecognition.dispose();
    voiceAssistant.removeListener(_onVoiceAssistantChanged);
    voiceAssistant.dispose();
    baseService.removeListener(_onBaseServiceChanged);
    baseService.dispose();
    staticServer.stop();
    personaLogServer.stop();
    personaLogger.dispose();
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

/// 在 Isolate 中把摆正图的 RGBA 字节编码为 JPEG。
///
/// YOLO 插件的单图 predict 需要编码图像字节,而 `img.encodeJpg` 是 CPU 密集的
/// 主 isolate 阻塞点。放到 [Isolate.run] 里执行,彻底避免编码引发的掉帧。
/// 顶层函数 + 仅传可发送的 [rgba]/[w]/[h],满足 isolate 入参约束。
Uint8List _encodeJpgFromRgba(Uint8List rgba, int w, int h) {
  final image = img.Image.fromBytes(
    width: w,
    height: h,
    bytes: rgba.buffer,
    order: img.ChannelOrder.rgba,
  );
  // 质量 92:YOLO 对压缩伪影敏感,过低的 JPEG 质量会进一步劣化相似小物体
  // (杯子/书/手机)的纹理特征。略提质量换取识别准确率,编码在 Isolate 中
  // 执行不阻塞主循环。
  return Uint8List.fromList(img.encodeJpg(image, quality: 92));
}
