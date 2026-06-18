import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// 可选的虚拟桌面类型。
enum VirtualDesktop {
  /// 默认 SVG 表情桌面（React 构建产物）。
  defaultSvg,

  /// Live2D 魔女桌面。
  monv,
}

/// 单个桌面的静态资源与服务配置。
@immutable
class VirtualDesktopInfo {
  const VirtualDesktopInfo({
    required this.id,
    required this.label,
    required this.assetPrefix,
    this.styleQuery,
  });

  final VirtualDesktop id;
  final String label;

  /// Flutter assets 前缀，如 `assets/desktop/default/`。
  final String assetPrefix;

  /// 加载 URL 时附加的查询串，如 `?style=ambient`。
  final String? styleQuery;

  /// 临时目录名（避免多桌面切换时冲突）。
  String get tempDirName => 'xbot_desktop_${id.name}';
}

const Map<VirtualDesktop, VirtualDesktopInfo> kVirtualDesktops = {
  VirtualDesktop.defaultSvg: VirtualDesktopInfo(
    id: VirtualDesktop.defaultSvg,
    label: '默认',
    assetPrefix: 'assets/desktop/default/',
    styleQuery: '?style=ambient',
  ),
  VirtualDesktop.monv: VirtualDesktopInfo(
    id: VirtualDesktop.monv,
    label: '魔女',
    assetPrefix: 'assets/desktop/monv/',
  ),
};

VirtualDesktopInfo desktopInfo(VirtualDesktop id) => kVirtualDesktops[id]!;

@immutable
class DesktopConfig {
  final VirtualDesktop desktop;

  const DesktopConfig({this.desktop = VirtualDesktop.defaultSvg});

  DesktopConfig copyWith({VirtualDesktop? desktop}) =>
      DesktopConfig(desktop: desktop ?? this.desktop);

  Map<String, dynamic> toJson() => {'desktop': desktop.name};

  factory DesktopConfig.fromJson(Map<String, dynamic> json) {
    final raw = json['desktop'] as String?;
    for (final d in VirtualDesktop.values) {
      if (d.name == raw) return DesktopConfig(desktop: d);
    }
    return const DesktopConfig();
  }
}

/// 虚拟桌面选择的本地持久化。
class DesktopConfigStore {
  static const String _fileName = 'desktop_config.json';

  DesktopConfig? _config;
  bool _loaded = false;
  File? _file;

  DesktopConfig get config => _config ?? const DesktopConfig();
  bool get isLoaded => _loaded;

  Future<void> load() async {
    if (_loaded) return;
    final dir = await getApplicationDocumentsDirectory();
    _file = File('${dir.path}/$_fileName');
    if (await _file!.exists()) {
      try {
        final json =
            jsonDecode(await _file!.readAsString()) as Map<String, dynamic>;
        _config = DesktopConfig.fromJson(json);
      } catch (e) {
        debugPrint('[DesktopConfig] load failed: $e');
        _config = const DesktopConfig();
      }
    } else {
      _config = const DesktopConfig();
    }
    _loaded = true;
  }

  Future<void> save(DesktopConfig config) async {
    _config = config;
    _file ??= File(
      '${(await getApplicationDocumentsDirectory()).path}/$_fileName',
    );
    await _file!.writeAsString(
      const JsonEncoder.withIndent('  ').convert(config.toJson()),
    );
  }
}
