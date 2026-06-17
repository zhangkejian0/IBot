import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:webview_flutter/webview_flutter.dart';

import '../core/app_controller.dart';
import '../core/app_scope.dart';
import '../face/emotion_mapper.dart';
import '../face/gaze_smoother.dart';
import '../face/gaze_zone_detector.dart';
import '../models/expression.dart';
import '../services/voice/voice_state.dart';
import '../theme/app_theme.dart';
import 'overlay_painter.dart';
import 'settings/settings_screen.dart';

class CameraScreen extends StatelessWidget {
  const CameraScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    final cam = controller.cameraController;
    // 调试模式：开启显示摄像头识别画面，关闭显示虚拟宠物网页（默认关闭）。
    final showDebug = controller.settings.debugMode;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (showDebug)
            if (cam != null && cam.value.isInitialized)
              _CameraPreviewCover(controller: cam)
            else
              const ColoredBox(color: Colors.black)
          else
            _VirtualPetWebView(controller: controller),

          // 识别结果覆盖层（仅调试/摄像头模式显示）
          if (showDebug)
            ListenableBuilder(
              listenable: controller,
              builder: (context, _) {
                // 调试模式下也更新区域检测器
                final face = controller.result.face;
                if (face != null) {
                  final isFront = controller.isFrontCamera;
                  var x = 2 * face.boundingBox.center.dx - 1;
                  var y = 2 * face.boundingBox.center.dy - 1;
                  if (isFront) x = -x;
                  controller.gazeZoneDetector.update(x, y);
                } else {
                  controller.gazeZoneDetector.reset();
                }
                return CustomPaint(
                  painter: DetectionOverlayPainter(
                    result: controller.result,
                    settings: controller.settings,
                    zoneDetector: controller.gazeZoneDetector,
                  ),
                  size: Size.infinite,
                );
              },
            ),

          // 顶部状态面板（仅调试/摄像头模式显示）
          if (showDebug)
            Positioned(
              top: 12,
              left: 12,
              child: SafeArea(child: _StatusPanel(controller: controller)),
            ),

          // 右上角设置入口
          Positioned(
            top: 12,
            right: 12,
            child: SafeArea(
              child: _RoundIconButton(
                icon: CupertinoIcons.gear_alt_fill,
                onTap: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const SettingsScreen()),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _VirtualPetWebView extends StatefulWidget {
  const _VirtualPetWebView({required this.controller});
  final AppController controller;

  @override
  State<_VirtualPetWebView> createState() => _VirtualPetWebViewState();
}

class _VirtualPetWebViewState extends State<_VirtualPetWebView> {
  static const EmotionMapper _mapper = EmotionMapper();
  // 同一状态至少保持这么久再允许切换，避免表情抖动导致 FSM 频繁跳变。
  static const Duration _minDwell = Duration(milliseconds: 600);

  late final WebViewController _controller;
  bool _loaded = false;
  bool _loading = false;

  // 上一次推给网页的状态及其确认时刻（用于 dwell 判断）。
  FaceState? _lastSent;
  DateTime _lastSentAt = DateTime.fromMillisecondsSinceEpoch(0);

  // 注视方向平滑器：消除 eyeLook blendshape 帧间抖动。
  final GazeSmoother _gaze = GazeSmoother();
  // 是否为前置摄像头（决定水平 gaze 是否镜像）。
  bool _isFrontCamera = true;

  // 区域检测器：使用AppController中的实例，与调试可视化共享。
  GazeZoneDetector get _zoneDetector => widget.controller.gazeZoneDetector;
  // 目标区域中心坐标（用于平滑插值）
  Offset _targetGaze = Offset.zero;

  // JS 推送节流：检测每帧（~30fps）都触发监听，但 runJavaScript 是异步且
  // WebView 单线程串行执行 JS，若每帧都发会堆积排队造成卡顿。用一个时间窗
  // 合并多帧、且把 setState+setGazeTarget 合成一次 JS 调用，大幅减少 JS 队列。
  static const Duration _pushInterval = Duration(milliseconds: 50); // ~20fps
  DateTime _lastPushAt = DateTime.fromMillisecondsSinceEpoch(0);

  @override
  void initState() {
    super.initState();
    debugPrint('[VirtualPet] initState');
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..setNavigationDelegate(NavigationDelegate(
        onPageStarted: (url) {
          debugPrint('[VirtualPet] onPageStarted: $url');
        },
        onPageFinished: (url) {
          debugPrint('[VirtualPet] onPageFinished: $url');
          if (mounted) {
            setState(() => _loaded = true);
            // 页面就绪后立即把当前状态 + 注视推一次，避免首屏停留。
            _lastPushAt = DateTime.now();
            _pushAll();
          }
        },
        onWebResourceError: (error) {
          debugPrint('[VirtualPet] error: ${error.errorType} ${error.description}');
        },
      ))
      ..enableZoom(false);

    widget.controller.addListener(_onControllerChanged);
    // 语音助手是独立的 ChangeNotifier:活跃时(聆听/思考/说话)需独立驱动
    // 虚拟宠物表情与嘴部张合,即使没有摄像头帧也要能刷新。
    widget.controller.voiceAssistant.addListener(_onControllerChanged);
    _isFrontCamera = widget.controller.isFrontCamera;
  }

  @override
  void dispose() {
    widget.controller.voiceAssistant.removeListener(_onControllerChanged);
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  /// 控制器每帧刷新检测结果时触发：映射情绪→FSM 状态并推给网页，
  /// 同时把注视方向推给网页驱动瞳孔跟随。
  ///
  /// 检测约 30fps，但 runJavaScript 是异步且 WebView JS 单线程串行执行，
  /// 若每帧都发会堆积排队造成卡顿。这里用一个时间窗（~20fps）合并多帧，
  /// 并把 setState + setGazeTarget 合成一次 JS 调用，显著减少 JS 队列压力。
  void _onControllerChanged() {
    if (!mounted || !_loaded) return;
    final now = DateTime.now();
    if (now.difference(_lastPushAt) < _pushInterval) {
      return; // 窗口内跳过，避免每帧堆积 JS 调用
    }
    _lastPushAt = now;
    _pushAll();
  }

  /// 把情绪状态 + 注视合并成一次 JS 调用推给网页。
  void _pushAll() {
    final face = widget.controller.result.face;
    final voice = widget.controller.voiceAssistant;
    final voiceActive = voice.isRunning && voice.state.isActive;

    // —— 语音优先级最高:活跃时接管表情 + 嘴部张合,跳过视觉情绪态 ——
    if (voiceActive) {
      final fs = voice.state.faceState;
      String voiceJs = '';
      if (fs != null) {
        voiceJs = "f.setState('$fs');";
      }
      // 嘴部张合:listening 用麦克风音量,speaking 用 TTS 音量,
      // 二者都通过 voice.level 暴露(controller.ts:147-150 驱动嘴部)。
      final lvl = voice.level.value;
      voiceJs += 'f.setListeningLoudness(${lvl.toStringAsFixed(3)});';
      final js = 'var f=window.__face;if(f){$voiceJs}';
      _controller.runJavaScript(js).catchError((e) {
        debugPrint('[VirtualPet] JS push(voice) failed: $e');
      });
      return;
    }

    // 注视方向：使用区域检测，映射到九宫格极限位置。
    String gazeJs = '';
    if (face != null) {
      // 计算人脸中心归一化坐标（-1..1，画面中心为原点）
      var x = 2 * face.boundingBox.center.dx - 1; // 画面中心→0，右→+1
      var y = 2 * face.boundingBox.center.dy - 1; // 画面中心→0，下→+1
      if (_isFrontCamera) x = -x; // 前置水平镜像

      // 使用区域检测器判断是否需要发送JS更新
      final zoneChanged = _zoneDetector.update(x, y);
      if (zoneChanged) {
        // 区域变化，获取对应宫格的极限位置
        final col = _zoneDetector.currentCol ?? 1;
        final row = _zoneDetector.currentRow ?? 1;
        // 九宫格映射到极限位置：col 0,1,2 -> x -1,0,1；row 0,1,2 -> y -1,0,1
        final targetX = (col - 1).toDouble(); // -1, 0, 1
        final targetY = (row - 1).toDouble(); // -1, 0, 1
        _targetGaze = Offset(targetX, targetY);
        debugPrint('[VirtualPet] 区域变化: ${_zoneDetector.currentZoneName} -> ($targetX, $targetY)');
      }
      // 平滑插值：眼睛从当前位置平滑移动到目标极限位置
      final smooth = _gaze.update(_targetGaze.dx, _targetGaze.dy);
      gazeJs =
          'f.setGazeTarget(${smooth.dx.toStringAsFixed(3)},${smooth.dy.toStringAsFixed(3)});';
    } else {
      _gaze.reset();
      _zoneDetector.reset();
    }

    // 情绪状态（带 dwell 去抖）。
    String stateJs = '';
    if (face != null) {
      final state = _mapper.map(face.expression.expression);
      final now = DateTime.now();
      final changed = state != _lastSent;
      final dwellOk =
          _lastSent == null || now.difference(_lastSentAt) >= _minDwell;
      if (changed && dwellOk) {
        _lastSent = state;
        _lastSentAt = now;
        stateJs = "f.setState('${state.name}');";
      }
    }

    if (gazeJs.isEmpty && stateJs.isEmpty) return;
    // 合并成一次 JS 调用：取出 __face 引用，依次 setState / setGazeTarget。
    final js = 'var f=window.__face;if(f){$stateJs$gazeJs}';
    _controller.runJavaScript(js).catchError((e) {
      debugPrint('[VirtualPet] JS push failed: $e');
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    debugPrint('[VirtualPet] didChangeDependencies _loading=$_loading _loaded=$_loaded');
    if (!_loading && !_loaded) {
      _loading = true;
      _loadUrl();
    }
  }

  Future<void> _loadUrl() async {
    final appController = AppScope.of(context);
    debugPrint('[VirtualPet] starting server...');
    final base = await appController.startVirtualPetServer();
    // 默认显示模式为 ambient（暗背景 + 环境光晕 + 卡哇伊珍珠眼）。
    // 其它可选：?style=neon（霓虹机器人）/ 留空（默认写实脸）。
    final url = '$base?style=ambient';
    debugPrint('[VirtualPet] server url: $url');
    if (mounted) {
      debugPrint('[VirtualPet] loading request...');
      _controller.loadRequest(Uri.parse(url));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        WebViewWidget(controller: _controller),
        if (!_loaded)
          const Center(
            child: CircularProgressIndicator(color: Colors.white),
          ),
      ],
    );
  }
}

class _CameraPreviewCover extends StatelessWidget {
  const _CameraPreviewCover({required this.controller});
  final CameraController controller;

  @override
  Widget build(BuildContext context) {
    // CameraPreview 内部已按 deviceOrientation 做 AspectRatio + RotatedBox。
    // 这里只用一个 cover-fit 把它铺满整屏、保持原始比例，不再手动对调宽高
    // （旧实现手动对调 + FittedBox 双重变换，会与内置 AspectRatio 叠加导致画面变形）。
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.cover,
        alignment: Alignment.center,
        child: SizedBox(
          // aspectRatio = previewSize.width / height（传感器空间，通常宽<高）。
          // 横屏取正常值，竖屏取倒数，与 CameraPreview 源码的 _isLandscape 翻转一致。
          width: _isLandscape ? controller.value.aspectRatio * _basis : _basis,
          height:
              _isLandscape ? _basis : _basis / controller.value.aspectRatio,
          child: CameraPreview(controller),
        ),
      ),
    );
  }

  static const double _basis = 1000;

  bool get _isLandscape =>
      controller.value.deviceOrientation == DeviceOrientation.landscapeLeft ||
      controller.value.deviceOrientation == DeviceOrientation.landscapeRight;
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final r = controller.result;
        final faces = r.faces;
        final mainFace = r.face; // 主脸（面积最大，且含表情/网格）
        final rows = <Widget>[];

        // 人脸数量 + 主脸表情
        if (faces.isEmpty) {
          rows.add(_line(
            icon: CupertinoIcons.smiley,
            text: '未检测到人脸',
            color: AppTheme.tertiaryLabel,
          ));
        } else {
          final exprText = (mainFace != null && mainFace.landmarks.isNotEmpty)
              ? '  ${mainFace.expression.expression.emoji}'
                  '${mainFace.expression.expression.label}'
              : '';
          rows.add(_line(
            icon: CupertinoIcons.smiley,
            text: '人脸：${faces.length} 张$exprText',
            color: mainFace?.expression.expression.color ?? AppTheme.accentGreen,
          ));
        }

        // 身份：列出所有脸的身份（命中者显示姓名，未识别/无模型显示占位）
        if (faces.isEmpty) {
          rows.add(_line(
            icon: CupertinoIcons.person_fill,
            text: controller.faceRecognition.isAvailable ? '身份：未识别' : '身份：未加载模型',
            color: AppTheme.tertiaryLabel,
          ));
        } else if (!controller.faceRecognition.isAvailable) {
          rows.add(_line(
            icon: CupertinoIcons.person_fill,
            text: '身份：未加载模型',
            color: AppTheme.tertiaryLabel,
          ));
        } else {
          final parts = <String>[];
          var unknownCount = 0;
          for (final f in faces) {
            final m = f.identity;
            if (m != null) {
              parts.add(m.person.name);
            } else {
              unknownCount++;
            }
          }
          var text = parts.join('、');
          if (unknownCount > 0) {
            text = text.isEmpty ? '$unknownCount 人未识别' : '$text、$unknownCount 人未识别';
          }
          final anyKnown = parts.isNotEmpty;
          rows.add(_line(
            icon: CupertinoIcons.person_fill,
            text: '身份：$text',
            color: anyKnown ? AppTheme.accent : AppTheme.tertiaryLabel,
          ));
        }

        rows.add(_line(
          icon: CupertinoIcons.hand_raised_fill,
          text: r.hands.isEmpty
              ? '未检测到手'
              : '手势：${r.hands.length} 只手',
          color: r.hands.isEmpty
              ? AppTheme.tertiaryLabel
              : AppTheme.accentOrange,
        ));

        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.42),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: rows,
          ),
        );
      },
    );
  }

  Widget _line({
    required IconData icon,
    required String text,
    required Color color,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 15, color: color),
          const SizedBox(width: 6),
          Text(
            text,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 13,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

class _RoundIconButton extends StatelessWidget {
  const _RoundIconButton({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.42),
          shape: BoxShape.circle,
          border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
        ),
        child: Icon(icon, color: Colors.white, size: 22),
      ),
    );
  }
}
