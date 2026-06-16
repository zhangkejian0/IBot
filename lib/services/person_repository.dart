import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/person.dart';

/// 人物身份的本地持久化仓库（JSON 文件 + 头像图片目录）。
class PersonRepository {
  static const String _fileName = 'people.json';

  final List<Person> _people = [];
  bool _loaded = false;
  Directory? _docsDir;

  List<Person> get people => List.unmodifiable(_people);

  Future<Directory> _dir() async {
    return _docsDir ??= await getApplicationDocumentsDirectory();
  }

  Future<File> _file() async {
    final dir = await _dir();
    return File('${dir.path}/$_fileName');
  }

  /// 头像保存目录。
  Future<Directory> avatarsDir() async {
    final dir = await _dir();
    final avatars = Directory('${dir.path}/avatars');
    if (!await avatars.exists()) {
      await avatars.create(recursive: true);
    }
    return avatars;
  }

  Future<void> load() async {
    if (_loaded) return;
    try {
      final file = await _file();
      if (await file.exists()) {
        final raw = await file.readAsString();
        final list = jsonDecode(raw) as List<dynamic>;
        _people
          ..clear()
          ..addAll(list
              .map((e) => Person.fromJson(Map<String, dynamic>.from(e as Map))));
      }
    } catch (_) {
      // 读取失败时以空库启动，避免阻塞应用。
    }
    _loaded = true;
  }

  Future<void> _save() async {
    final file = await _file();
    final data = jsonEncode(_people.map((p) => p.toJson()).toList());
    await file.writeAsString(data);
  }

  Future<void> upsert(Person person) async {
    final idx = _people.indexWhere((p) => p.id == person.id);
    if (idx >= 0) {
      _people[idx] = person;
    } else {
      _people.add(person);
    }
    await _save();
  }

  Future<void> delete(String id) async {
    final person = _people.where((p) => p.id == id).cast<Person?>().firstOrNull;
    _people.removeWhere((p) => p.id == id);
    // 顺带删除头像文件
    final path = person?.avatarPath;
    if (path != null) {
      try {
        final f = File(path);
        if (await f.exists()) await f.delete();
      } catch (_) {}
    }
    await _save();
  }
}

extension _FirstOrNull<E> on Iterable<E> {
  E? get firstOrNull {
    final it = iterator;
    if (it.moveNext()) return it.current;
    return null;
  }
}
