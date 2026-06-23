import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../models/owner_profile.dart';

/// 主人档案的本地持久化仓库（JSON 文件，范式同 [PophieConfigStore]）。
///
/// 文件名 `owner_profile.json`，落在 `getApplicationDocumentsDirectory()`。
/// **文件存在与否即「是否已注册」的判据**：未注册（文件缺失或读取失败）时
/// [profile] 为 null，应用进入首次激活向导（`AppPhase.onboarding`）。
class OwnerProfileStore {
  static const String _fileName = 'owner_profile.json';

  OwnerProfile? _profile;
  bool _loaded = false;
  File? _file;

  /// 当前主人档案；未注册时为 null。
  OwnerProfile? get profile => _profile;
  bool get isLoaded => _loaded;
  bool get isRegistered => _profile != null;

  Future<File> _resolveFile() async {
    return _file ??= File(
        '${(await getApplicationDocumentsDirectory()).path}/$_fileName');
  }

  /// 加载主人档案；文件不存在或读取失败视为未注册（返回 null），不抛异常。
  Future<void> load() async {
    if (_loaded) return;
    try {
      final f = await _resolveFile();
      if (await f.exists()) {
        _profile = OwnerProfile.fromJson(
            Map<String, dynamic>.from(jsonDecode(await f.readAsString()) as Map));
      }
    } catch (e) {
      debugPrint('[OwnerProfileStore] load failed, treat as unregistered: $e');
      _profile = null;
    }
    _loaded = true;
  }

  /// 保存主人档案并标记为已注册。
  Future<void> save(OwnerProfile profile) async {
    _profile = profile;
    await _save();
  }

  /// 清除主人档案（标记为未注册，重新进入向导）。删除本地文件，并清空内存。
  Future<void> clear() async {
    _profile = null;
    try {
      final f = await _resolveFile();
      if (await f.exists()) await f.delete();
    } catch (e) {
      debugPrint('[OwnerProfileStore] clear failed: $e');
    }
  }

  Future<void> _save() async {
    try {
      final f = await _resolveFile();
      final p = _profile;
      if (p == null) {
        if (await f.exists()) await f.delete();
        return;
      }
      await f.writeAsString(jsonEncode(p.toJson()));
    } catch (e) {
      debugPrint('[OwnerProfileStore] save failed: $e');
    }
  }
}
