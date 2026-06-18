import 'dart:io';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/services.dart' show AssetManifest, rootBundle;
import 'package:path_provider/path_provider.dart';

import '../core/desktop_config.dart';

/// 本地静态文件服务器，用于托管 Flutter assets 中的 HTML 页面。
///
/// 因为 WebView 无法直接解析 assets 中的相对路径 JS/CSS，
/// 需要先把文件复制到临时目录，再通过 HTTP 服务提供给 WebView。
class StaticServer {
  HttpServer? _server;
  Directory? _distDir;
  VirtualDesktop? _servingDesktop;

  /// 服务端口，启动后可用。
  int? get port => _server?.port;

  VirtualDesktop? get servingDesktop => _servingDesktop;

  /// 启动服务器，返回可访问的 URL。
  ///
  /// [desktop] 决定从哪套 assets 复制静态文件；切换桌面时需先 [stop] 再重新 start。
  Future<String> start({required VirtualDesktop desktop}) async {
    if (_server != null && _servingDesktop == desktop) {
      return 'http://localhost:${_server!.port}';
    }
    if (_server != null) {
      await stop();
    }

    final info = desktopInfo(desktop);
    _distDir = await _prepareAssets(info);
    _servingDesktop = desktop;
    debugPrint('[StaticServer] dist dir: ${_distDir!.path} (${info.label})');

    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server!.listen(_handleRequest);

    final url = 'http://localhost:${_server!.port}';
    debugPrint('[StaticServer] started at $url');
    return url;
  }

  /// 停止服务器并清理临时文件。
  Future<void> stop() async {
    await _server?.close(force: true);
    _server = null;
    _servingDesktop = null;
    if (_distDir != null) {
      try {
        await _distDir!.delete(recursive: true);
      } catch (_) {}
      _distDir = null;
    }
  }

  /// 将指定桌面的 assets 复制到临时目录。
  Future<Directory> _prepareAssets(VirtualDesktopInfo info) async {
    final temp = await getTemporaryDirectory();
    final dist = Directory('${temp.path}/${info.tempDirName}');

    if (await dist.exists()) {
      await dist.delete(recursive: true);
    }
    await dist.create(recursive: true);

    final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final allAssets = manifest.listAssets();
    final prefix = info.assetPrefix;
    final desktopAssets = allAssets
        .where((a) => a.startsWith(prefix))
        .where(_isCopyableAsset)
        .toList();
    debugPrint(
        '[StaticServer] ${info.label} assets found: ${desktopAssets.length}');

    var copied = 0;
    for (final assetPath in desktopAssets) {
      final relativePath = assetPath.replaceFirst(prefix, '');
      final destPath = '${dist.path}/$relativePath';
      await File(destPath).parent.create(recursive: true);
      if (await _copyAsset(assetPath, destPath)) {
        copied++;
      }
    }
    debugPrint('[StaticServer] ${info.label} assets copied: $copied');

    final htmlFile = File('${dist.path}/index.html');
    debugPrint('[StaticServer] index.html exists: ${htmlFile.existsSync()}');

    if (info.id == VirtualDesktop.defaultSvg) {
      final jsDir = Directory('${dist.path}/assets');
      final jsFiles = jsDir.existsSync()
          ? jsDir
              .listSync()
              .whereType<File>()
              .where((f) => f.path.endsWith('.js'))
              .toList()
          : const <File>[];
      debugPrint('[StaticServer] assets/*.js count: ${jsFiles.length}');
      if (jsFiles.isEmpty) {
        debugPrint('[StaticServer] WARNING: no JS bundle found. '
            'Check pubspec.yaml includes assets/desktop/default/assets/');
      }
    }

    if (info.id == VirtualDesktop.monv) {
      final modelJson = File('${dist.path}/models/monv/魔女.model3.json');
      debugPrint(
          '[StaticServer] monv model3.json exists: ${modelJson.existsSync()}');
      if (!modelJson.existsSync()) {
        debugPrint('[StaticServer] WARNING: monv model missing. '
            'Check pubspec.yaml includes assets/desktop/monv/models/monv/');
      }
    }

    return dist;
  }

  /// 跳过 macOS 元数据等隐藏文件；manifest 可能列出它们但实际无法 load。
  bool _isCopyableAsset(String assetPath) {
    final segments = assetPath.split('/');
    return segments.every((s) => s.isNotEmpty && !s.startsWith('.'));
  }

  /// 返回是否复制成功。单文件失败不阻断整桌面的启动。
  Future<bool> _copyAsset(String assetPath, String destPath) async {
    try {
      final data = await rootBundle.load(assetPath);
      final bytes = data.buffer.asUint8List();
      if (bytes.isEmpty) {
        debugPrint('[StaticServer] skip empty asset: $assetPath');
        return false;
      }
      await File(destPath).writeAsBytes(bytes);
      return true;
    } catch (e) {
      debugPrint('[StaticServer] skip $assetPath: $e');
      return false;
    }
  }

  /// HTTP 请求 path 常为百分号编码（如 %E9%AD%94%E5%A5%B3），
  /// 复制到磁盘的 assets 则是 UTF-8 中文路径，需解码后再查找文件。
  String _resolveRelativePath(String uriPath) {
    if (uriPath == '/' || uriPath.isEmpty) return 'index.html';
    return uriPath
        .substring(1)
        .split('/')
        .map(Uri.decodeComponent)
        .join('/');
  }

  Future<void> _handleRequest(HttpRequest request) async {
    final uriPath = request.uri.path;
    final relativePath = _resolveRelativePath(uriPath);
    final file = File('${_distDir!.path}/$relativePath');

    if (file.existsSync() && !FileSystemEntity.isDirectorySync(file.path)) {
      final ext = file.uri.pathSegments.last.split('.').last.toLowerCase();
      final contentType = switch (ext) {
        'html' => 'text/html; charset=utf-8',
        'js' => 'application/javascript; charset=utf-8',
        'mjs' => 'application/javascript; charset=utf-8',
        'css' => 'text/css; charset=utf-8',
        'json' => 'application/json',
        'wasm' => 'application/wasm',
        'png' => 'image/png',
        'jpg' || 'jpeg' => 'image/jpeg',
        'svg' => 'image/svg+xml',
        'ico' => 'image/x-icon',
        'moc3' => 'application/octet-stream',
        _ => 'application/octet-stream',
      };
      try {
        final response = request.response;
        response.headers.contentType = ContentType.parse(contentType);
        response.contentLength = await file.length();
        await response.addStream(file.openRead());
        await response.close();
      } catch (e) {
        debugPrint('[StaticServer] write failed $uriPath: $e');
        try {
          await request.response.close();
        } catch (_) {}
      }
    } else {
      request.response
        ..statusCode = HttpStatus.notFound
        ..write('404 Not Found')
        ..close();
      debugPrint('[StaticServer] 404 $uriPath -> $relativePath');
    }
  }
}
