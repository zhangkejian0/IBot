import 'dart:math' as math;

import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show DeviceOrientation;
import 'package:webview_flutter/webview_flutter.dart';

import '../core/app_controller.dart';
import '../core/app_scope.dart';
import '../face/emotion_mapper.dart';
import '../face/gaze_zone_detector.dart';
import '../models/expression.dart';
import '../services/camera_image_utils.dart';
import '../services/voice/voice_assistant.dart';
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
                  if (isFront &&
                      CameraImageUtils.shouldFlipFrontCameraHorizontal) {
                    x = -x;
                  }
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

          // —— 双击进入聆听的「App 层」手势捕获层 ——
          // WebView 在 Android 上是原生平台视图(platform view),会在到达
          // Flutter GestureDetector 之前吞掉触摸事件,导致包裹整个 Stack 的
          // 手势识别收不到虚拟人物画面上的双击。把一个不透明的手势层「叠」在
          // WebView 之上(z 序更高),双击就在 Flutter 层被捕获,不再受加载页面/
          // WebView 影响。虚拟人物本身不需要触摸交互,故拦截单击不影响功能。
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onDoubleTap: () async {
                final v = controller.voiceAssistant;
                debugPrint('[DoubleTap] running=${v.isRunning} '
                    'available=${v.isAvailable} state=${v.state.name}');
                // 无麦克风权限等不可用情况:无法聆听,直接忽略。
                if (!v.isAvailable) return;
                // App 层「双击进入聆听」:即使语音总开关未开,只要可用就自动
                // 启动语音助手,再触发一轮聆听 —— 不依赖设置页的总开关状态。
                if (!v.isRunning) {
                  await v.start();
                }
                // 仅在 idle 时触发,避免聆听/思考/播报中重复触发。
                if (v.isRunning && v.state == VoiceState.idle) {
                  await v.triggerManually();
                }
              },
            ),
          ),

          // 顶部状态面板（仅调试/摄像头模式显示）
          if (showDebug)
            Positioned(
              top: 12,
              left: 12,
              child: SafeArea(child: _StatusPanel(controller: controller)),
            ),

          // 右上角设置入口（位于手势层之上,保证可点击）
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

          // —— 聆听态暖色调跑马灯(苹果风格) ——
          // 仅在进入聆听(waking/listening)时沿屏幕一圈呈现旋转的暖色光晕,
          // 并随麦克风音量轻微呼吸。IgnorePointer 保证不拦截任何手势。
          Positioned.fill(
            child: IgnorePointer(
              child: _ListeningMarquee(voice: controller.voiceAssistant),
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

  // 上一帧语音是否活跃:用于检测"语音刚结束"的过渡,强制把前端表情
  // 切回 idle。否则当对话结束且摄像头没检测到人脸时,_pushAll 的视觉态
  // 分支会直接 return,前端表情卡在 listening(无人通知它切回)。
  bool _lastVoiceActive = false;

  // 区域检测器：使用AppController中的实例，与调试可视化共享。
  GazeZoneDetector get _zoneDetector => widget.controller.gazeZoneDetector;

  // JS 推送节流：检测每帧（~30fps）都触发监听，但 runJavaScript 是异步且
  // WebView 单线程串行执行 JS，若每帧都发会堆积排队造成卡顿。用一个时间窗
  // 合并多帧、且把 setState+setGazeTarget 合成一次 JS 调用，大幅减少 JS 队列。
  // 合并后的 JS 调用很小（一行 setGazeTarget/setState），把窗口收紧到
  // ~33ms(~30fps)：在不堆积 JS 队列的前提下，让注视跟随尽量贴近检测帧率，
  // 降低「慢半拍」的感知延迟。
  static const Duration _pushInterval = Duration(milliseconds: 33); // ~30fps
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

    // —— 语音刚结束的过渡:强制把前端表情切回 idle + 嘴部归零 ——
    // 对话结束时 voice_assistant 的 finally 会置 idle,voiceActive 变 false。
    // 若此时摄像头没检测到人脸,下方的视觉态分支会 return 不推送任何指令,
    // 导致前端表情卡在 listening。这里在过渡瞬间强制推一次清空。
    final justEnded = _lastVoiceActive && !voiceActive;
    _lastVoiceActive = voiceActive;
    if (justEnded) {
      const js = 'var f=window.__face;if(f){f.setState(\'idle\');'
          'f.setListeningLoudness(0);}';
      _controller.runJavaScript(js).catchError((e) {
        debugPrint('[VirtualPet] JS push(voice-end) failed: $e');
      });
      // 重置视觉态去抖缓存,让下一帧能正常驱动。
      _lastSent = null;
    }

    // —— 语音优先级最高:活跃时接管表情 + 嘴部张合,跳过视觉情绪态 ——
    if (voiceActive) {
      // 优先用后端回传的 robot_state(精确 FSM 态,见文档 §2.6);
      // 缺省(聆听/思考阶段)回退到本地阶段映射。
      final fs = voice.robotState ?? voice.state.faceState;
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

    // 注视方向：人脸中心 → 九宫格量化 → 瞳孔极限位置。
    //
    // 把人脸中心归一化坐标量化到 3×3 九宫格之一，推送该格中心(-1/0/1 的组合)
    // 作为瞳孔目标。格内移动脸眼睛不动(只跨格才变),配合 JS 侧 spring 平滑过渡,
    // 避免了连续映射在低帧率(5fps)下的抖动。GazeZoneDetector 自带死区(边界±5%)
    // 防止人脸在格边界抖动时反复跳格。
    String gazeJs = '';
    if (face != null) {
      // 计算人脸中心归一化坐标（-1..1，画面中心为原点）
      var x = 2 * face.boundingBox.center.dx - 1; // 画面中心→0，右→+1
      var y = 2 * face.boundingBox.center.dy - 1; // 画面中心→0，下→+1
      if (widget.controller.isFrontCamera &&
          CameraImageUtils.shouldFlipFrontCameraHorizontal) {
        x = -x;
      }

      // 量化到九宫格:跨格时推送新格中心(-1/0/1),JS 侧 spring 自然过渡。
      _zoneDetector.update(x, y);
      final zone = _zoneDetector.currentZoneCenter;
      if (zone != null) {
        gazeJs =
            'f.setGazeTarget(${zone.dx.toStringAsFixed(1)},${zone.dy.toStringAsFixed(1)});';
      }
    } else {
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

    // 开发模式：使用Vite开发服务器（支持热更新）
    // 正式模式：使用本地静态服务器
    const useDevServer = false; // 开发时设为true，发布时设为false
    final url = useDevServer
        ? 'http://localhost:5174/?style=ambient'
        : '$base?style=ambient';

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

        // 物体识别：优先展示手持物体，其次列出可见物体名。
        final held = r.heldObject;
        final objNames = <String>[];
        for (final o in r.objects) {
          final l = o.label;
          if (l != null && l.isNotEmpty && !objNames.contains(l)) {
            objNames.add(l);
          }
        }
        String objText;
        Color objColor;
        if (held?.label != null && held!.label!.isNotEmpty) {
          objText = '手持：${held.label}';
          objColor = AppTheme.accentOrange;
        } else if (objNames.isNotEmpty) {
          objText = '物体：${objNames.take(3).join('、')}';
          objColor = AppTheme.accentTeal;
        } else if (r.objects.isNotEmpty) {
          // 检测到包围盒但无可用标签（base 模型常见）：仍提示已检测到。
          objText = '物体：${r.objects.length} 个（未分类）';
          objColor = AppTheme.accentTeal;
        } else if (!controller.objectEngine.isInitialized) {
          objText = '物体：未加载模型';
          objColor = AppTheme.tertiaryLabel;
        } else {
          objText = '未识别到物体';
          objColor = AppTheme.tertiaryLabel;
        }
        rows.add(_line(
          icon: CupertinoIcons.cube_box_fill,
          text: objText,
          color: objColor,
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

/// 聆听态「暖色调跑马灯」边框(苹果 Siri 风格)。
///
/// 进入聆听(waking/listening)时显示:沿全屏一圈绘制一条会旋转的暖色
/// SweepGradient 描边 + 高斯模糊光晕,并随麦克风音量(voice.level)做轻微的
/// 亮度/粗细呼吸。idle/thinking/speaking 等非聆听态完全隐藏且停掉动画。
class _ListeningMarquee extends StatefulWidget {
  const _ListeningMarquee({required this.voice});
  final VoiceAssistant voice;

  @override
  State<_ListeningMarquee> createState() => _ListeningMarqueeState();
}

class _ListeningMarqueeState extends State<_ListeningMarquee>
    with TickerProviderStateMixin {
  late final AnimationController _rotate;
  // 出现/消失的整体不透明度,做柔和淡入淡出而非生硬切换。
  late final AnimationController _fade;

  @override
  void initState() {
    super.initState();
    _rotate = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2600),
    );
    _fade = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 280),
    );
    widget.voice.addListener(_onVoiceChanged);
    _onVoiceChanged();
  }

  /// 是否处于「聆听」可视态:刚唤醒过渡(waking)与正式聆听(listening)。
  bool get _isListening {
    final v = widget.voice;
    if (!v.isRunning) return false;
    return v.state == VoiceState.waking || v.state == VoiceState.listening;
  }

  void _onVoiceChanged() {
    if (!mounted) return;
    if (_isListening) {
      if (!_rotate.isAnimating) _rotate.repeat();
      _fade.forward();
    } else {
      _fade.reverse().whenComplete(() {
        if (!_isListening && _rotate.isAnimating) _rotate.stop();
      });
    }
  }

  @override
  void dispose() {
    widget.voice.removeListener(_onVoiceChanged);
    _rotate.dispose();
    _fade.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([_rotate, _fade]),
      builder: (context, _) {
        if (_fade.value <= 0.001) return const SizedBox.shrink();
        return ValueListenableBuilder<double>(
          valueListenable: widget.voice.level,
          builder: (context, level, _) {
            return CustomPaint(
              size: Size.infinite,
              painter: _MarqueePainter(
                rotation: _rotate.value,
                opacity: Curves.easeOut.transform(_fade.value),
                level: level.clamp(0.0, 1.0),
              ),
            );
          },
        );
      },
    );
  }
}

class _MarqueePainter extends CustomPainter {
  _MarqueePainter({
    required this.rotation,
    required this.opacity,
    required this.level,
  });

  /// 0..1 的归一化旋转相位。
  final double rotation;
  /// 整体淡入淡出不透明度。
  final double opacity;
  /// 麦克风实时音量 0..1,驱动光晕的「呼吸」。
  final double level;

  // 暖色调跑马灯配色:琥珀→珊瑚→蜜橘→玫瑰金,首尾相接保证旋转无缝。
  static const List<Color> _warm = [
    Color(0xFFFFC078), // 蜜橘
    Color(0xFFFF8A5B), // 珊瑚
    Color(0xFFFF6F91), // 玫瑰
    Color(0xFFFFB26B), // 暖橘
    Color(0xFFFFD79A), // 浅琥珀
    Color(0xFFFFC078),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    // 音量越大,光晕越亮、越粗,形成 Siri 式呼吸感。
    final pulse = 0.78 + 0.22 * level;
    final shader = SweepGradient(
      colors: _warm,
      transform: GradientRotation(rotation * 2 * math.pi),
    ).createShader(rect);

    // 多层描边:外层宽而模糊(光晕)→ 内层细而清晰(亮边),叠出发光质感。
    void stroke(double width, double blur, double alpha) {
      final inset = width / 2;
      final rrect = RRect.fromRectAndRadius(
        rect.deflate(inset),
        const Radius.circular(38),
      );
      final paint = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = width
        ..shader = shader
        ..color = Colors.white.withValues(alpha: (alpha * opacity).clamp(0.0, 1.0));
      if (blur > 0) {
        paint.maskFilter = MaskFilter.blur(BlurStyle.normal, blur);
      }
      canvas.drawRRect(rrect, paint);
    }

    stroke(34 * pulse, 26 * pulse, 0.55); // 外层弥散光晕
    stroke(16 * pulse, 10, 0.85);         // 中层
    stroke(5, 1.5, 1.0);                  // 内层亮边
  }

  @override
  bool shouldRepaint(covariant _MarqueePainter old) =>
      old.rotation != rotation ||
      old.opacity != opacity ||
      old.level != level;
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
