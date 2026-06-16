import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// 全局 iOS 苹果风格暗色主题。
///
/// 主画面以全屏摄像头为主，使用纯黑背景；设置等页面采用 iOS 系统暗色风格
/// （接近 systemBackground / secondarySystemBackground 的层级灰度）。
class AppTheme {
  AppTheme._();

  // iOS 暗色系统色板
  static const Color background = Color(0xFF000000);
  static const Color groupedBackground = Color(0xFF1C1C1E);
  static const Color secondaryBackground = Color(0xFF2C2C2E);
  static const Color tertiaryBackground = Color(0xFF3A3A3C);
  static const Color separator = Color(0x5C545458);

  static const Color accent = Color(0xFF0A84FF); // iOS systemBlue (dark)
  static const Color accentGreen = Color(0xFF30D158);
  static const Color accentOrange = Color(0xFFFF9F0A);
  static const Color accentRed = Color(0xFFFF453A);
  static const Color accentPurple = Color(0xFFBF5AF2);
  static const Color accentYellow = Color(0xFFFFD60A);
  static const Color accentTeal = Color(0xFF64D2FF);

  static const Color label = Color(0xFFFFFFFF);
  static const Color secondaryLabel = Color(0x99EBEBF5);
  static const Color tertiaryLabel = Color(0x4DEBEBF5);

  /// Cupertino 暗色主题（用于 CupertinoApp 风格的页面）。
  static const CupertinoThemeData cupertino = CupertinoThemeData(
    brightness: Brightness.dark,
    primaryColor: accent,
    scaffoldBackgroundColor: background,
    barBackgroundColor: Color(0xF01C1C1E),
    textTheme: CupertinoTextThemeData(
      primaryColor: accent,
      textStyle: TextStyle(
        color: label,
        fontSize: 17,
        letterSpacing: -0.4,
        fontFamily: '.SF Pro Text',
      ),
      navTitleTextStyle: TextStyle(
        color: label,
        fontSize: 17,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.4,
      ),
      navLargeTitleTextStyle: TextStyle(
        color: label,
        fontSize: 34,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.4,
      ),
    ),
  );

  /// Material 暗色主题（作为 MaterialApp 的底色，与 Cupertino 风格保持一致）。
  static ThemeData material() {
    final base = ThemeData.dark(useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: background,
      colorScheme: base.colorScheme.copyWith(
        brightness: Brightness.dark,
        primary: accent,
        secondary: accentTeal,
        surface: groupedBackground,
        error: accentRed,
      ),
      splashFactory: NoSplash.splashFactory,
      highlightColor: Colors.transparent,
      cupertinoOverrideTheme: cupertino,
      textSelectionTheme: const TextSelectionThemeData(cursorColor: accent),
    );
  }
}
