import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 强制横屏
  await SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);

  // 全屏沉浸式（隐藏状态栏/导航栏）
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

  // 进入应用保持屏幕常亮
  await WakelockPlus.enable();

  runApp(const XBotApp());
}
