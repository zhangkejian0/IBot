import 'package:flutter/material.dart';

import 'core/app_controller.dart';
import 'core/app_scope.dart';
import 'theme/app_theme.dart';
import 'ui/camera_screen.dart';
import 'ui/loading_screen.dart';
import 'ui/onboarding/onboarding_screen.dart';

class XBotApp extends StatefulWidget {
  const XBotApp({super.key});

  @override
  State<XBotApp> createState() => _XBotAppState();
}

class _XBotAppState extends State<XBotApp> {
  late final AppController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AppController();
    _controller.initialize();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AppScope(
      controller: _controller,
      child: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return MaterialApp(
            // 机器人昵称随主人档案变化：未注册用默认「狗蛋」。
            title: _controller.robotDisplayName,
            debugShowCheckedModeBanner: false,
            theme: AppTheme.material(),
            themeMode: ThemeMode.dark,
            home: const _Root(),
          );
        },
      ),
    );
  }
}

class _Root extends StatelessWidget {
  const _Root();

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    switch (controller.phase) {
      case AppPhase.ready:
        return const CameraScreen();
      case AppPhase.onboarding:
        return const OnboardingScreen();
      case AppPhase.loading:
      case AppPhase.error:
      case AppPhase.permissionDenied:
        return const LoadingScreen();
    }
  }
}
