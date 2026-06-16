import 'package:flutter/widgets.dart';

import 'app_controller.dart';

/// 通过 InheritedNotifier 向子树提供 [AppController]，并在其变化时重建监听者。
class AppScope extends InheritedNotifier<AppController> {
  const AppScope({
    super.key,
    required AppController controller,
    required super.child,
  }) : super(notifier: controller);

  static AppController of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, '在 widget 树中未找到 AppScope');
    return scope!.notifier!;
  }

  /// 仅读取一次、不建立依赖（用于事件回调中）。
  static AppController read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, '在 widget 树中未找到 AppScope');
    return scope!.notifier!;
  }
}
