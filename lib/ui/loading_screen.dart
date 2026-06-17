import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../core/app_controller.dart';
import '../core/app_scope.dart';
import '../theme/app_theme.dart';

class LoadingScreen extends StatelessWidget {
  const LoadingScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    return Scaffold(
      backgroundColor: AppTheme.background,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(vertical: 24),
            child: switch (controller.phase) {
              AppPhase.permissionDenied =>
                _PermissionView(controller: controller),
              AppPhase.error => _ErrorView(controller: controller),
              _ => _ProgressView(controller: controller),
            },
          ),
        ),
      ),
    );
  }
}

class _ProgressView extends StatefulWidget {
  const _ProgressView({required this.controller});
  final AppController controller;

  @override
  State<_ProgressView> createState() => _ProgressViewState();
}

class _ProgressViewState extends State<_ProgressView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _breath;

  @override
  void initState() {
    super.initState();
    _breath = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _breath.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ScaleTransition(
          scale: Tween<double>(begin: 1.0, end: 1.08).animate(
            CurvedAnimation(parent: _breath, curve: Curves.easeInOut),
          ),
          child: const _Logo(),
        ),
        const SizedBox(height: 36),
        SizedBox(
          width: 280,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: widget.controller.loadingProgress,
              minHeight: 6,
              backgroundColor: AppTheme.tertiaryBackground,
              valueColor:
                  const AlwaysStoppedAnimation<Color>(AppTheme.accent),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          widget.controller.loadingMessage,
          style: const TextStyle(
            color: AppTheme.secondaryLabel,
            fontSize: 15,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          '${(widget.controller.loadingProgress * 100).round()}%',
          style: const TextStyle(
            color: AppTheme.tertiaryLabel,
            fontSize: 13,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
      ],
    );
  }
}

class _Logo extends StatelessWidget {
  const _Logo();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(22),
          child: Image.asset(
            'assets/images/logo.png',
            width: 88,
            height: 88,
            fit: BoxFit.cover,
          ),
        ),
        const SizedBox(height: 18),
        const Text(
          '狗蛋',
          style: TextStyle(
            color: AppTheme.label,
            fontSize: 30,
            fontWeight: FontWeight.w700,
            letterSpacing: 1.0,
          ),
        ),
        const SizedBox(height: 4),
        const Text(
          '表情 · 手势 · 身份 识别',
          style: TextStyle(color: AppTheme.tertiaryLabel, fontSize: 13),
        ),
      ],
    );
  }
}

class _PermissionView extends StatelessWidget {
  const _PermissionView({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 48),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(CupertinoIcons.camera_fill,
              color: AppTheme.accentOrange, size: 56),
          const SizedBox(height: 20),
          Text(
            controller.errorMessage ?? '需要摄像头权限',
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppTheme.label, fontSize: 16),
          ),
          const SizedBox(height: 24),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              CupertinoButton(
                color: AppTheme.secondaryBackground,
                onPressed: () => openAppSettings(),
                child: const Text('打开系统设置'),
              ),
              const SizedBox(width: 12),
              CupertinoButton.filled(
                onPressed: () => controller.initialize(),
                child: const Text('重试'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 48),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(CupertinoIcons.exclamationmark_triangle_fill,
              color: AppTheme.accentRed, size: 56),
          const SizedBox(height: 20),
          Text(
            controller.errorMessage ?? '发生未知错误',
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppTheme.label, fontSize: 16),
          ),
          const SizedBox(height: 24),
          CupertinoButton.filled(
            onPressed: () => controller.initialize(),
            child: const Text('重试'),
          ),
        ],
      ),
    );
  }
}
