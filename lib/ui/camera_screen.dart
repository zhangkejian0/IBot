import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show DeviceOrientation;

import '../core/app_controller.dart';
import '../core/app_scope.dart';
import '../models/expression.dart';
import '../models/person.dart';
import '../theme/app_theme.dart';
import 'overlay_painter.dart';
import 'settings/settings_screen.dart';

class CameraScreen extends StatelessWidget {
  const CameraScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    final cam = controller.cameraController;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (cam != null && cam.value.isInitialized)
            _CameraPreviewCover(controller: cam)
          else
            const ColoredBox(color: Colors.black),

          // 识别结果覆盖层
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

          // 顶部状态面板
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
        final face = r.face;
        final rows = <Widget>[];

        rows.add(_line(
          icon: CupertinoIcons.smiley,
          text: face == null
              ? '未检测到人脸'
              : '表情：${face.expression.expression.emoji} '
                  '${face.expression.expression.label}',
          color: face == null
              ? AppTheme.tertiaryLabel
              : face.expression.expression.color,
        ));

        rows.add(_line(
          icon: CupertinoIcons.person_fill,
          text: face?.identity != null
              ? '身份：${face!.identity!.person.name}'
                  '（${face.identity!.person.relation.label}）'
              : (controller.faceRecognition.isAvailable
                  ? '身份：未识别'
                  : '身份：未加载模型'),
          color: face?.identity != null
              ? AppTheme.accent
              : AppTheme.tertiaryLabel,
        ));

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
