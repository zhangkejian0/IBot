import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:webview_flutter/webview_flutter.dart';

import '../core/app_controller.dart';
import '../core/app_scope.dart';
import '../face/emotion_mapper.dart';
import '../models/expression.dart';
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
                return CustomPaint(
                  painter: DetectionOverlayPainter(
                    result: controller.result,
                    settings: controller.settings,
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
            // 页面就绪后立即把当前状态推一次，避免首屏停留在 idle。
            _pushCurrentState(force: true);
          }
        },
        onWebResourceError: (error) {
          debugPrint('[VirtualPet] error: ${error.errorType} ${error.description}');
        },
      ))
      ..enableZoom(false);

    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  /// 控制器每帧刷新检测结果时触发：映射情绪→FSM 状态并推给网页。
  void _onControllerChanged() {
    if (!mounted || !_loaded) return;
    _pushCurrentState();
  }

  void _pushCurrentState({bool force = false}) {
    final face = widget.controller.result.face;
    // 无人脸：保持上一次状态，不强切（避免一没人脸就回 idle 抖动）。
    if (face == null) return;
    final state = _mapper.map(face.expression.expression);

    final now = DateTime.now();
    if (!force && state == _lastSent) return;
    if (!force &&
        _lastSent != null &&
        now.difference(_lastSentAt) < _minDwell) {
      return; // dwell 未满，跳过本次切换
    }

    _lastSent = state;
    _lastSentAt = now;
    _sendState(state);
  }

  Future<void> _sendState(FaceState state) async {
    final js = "window.__face && window.__face.setState('${state.name}');";
    try {
      await _controller.runJavaScript(js);
      debugPrint('[VirtualPet] setState(${state.name})');
    } catch (e) {
      debugPrint('[VirtualPet] setState failed: $e');
    }
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
