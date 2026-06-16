import 'dart:io';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/services.dart' show AssetManifest, rootBundle;
import 'package:path_provider/path_provider.dart';

/// 本地静态文件服务器，用于托管 Flutter assets 中的 HTML 页面。
///
/// 因为 WebView 无法直接解析 assets 中的相对路径 JS/CSS，
/// 需要先把文件复制到临时目录，再通过 HTTP 服务提供给 WebView。
class StaticServer {
  HttpServer? _server;
  Directory? _distDir;

  /// 服务端口，启动后可用。
  int? get port => _server?.port;

  /// 启动服务器，返回可访问的 URL。
  Future<String> start() async {
    if (_server != null) return 'http://localhost:${_server!.port}';

    // 复制 assets 到临时目录
    _distDir = await _prepareAssets();
    debugPrint('[StaticServer] dist dir: ${_distDir!.path}');

    // 启动 HTTP 服务
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
    if (_distDir != null) {
      try {
        await _distDir!.delete(recursive: true);
      } catch (_) {}
      _distDir = null;
    }
  }

  /// 将 assets/html/dist/ 下的文件复制到临时目录。
  Future<Directory> _prepareAssets() async {
    final temp = await getTemporaryDirectory();
    final dist = Directory('${temp.path}/xbot_html_dist');

    // 清理旧文件后重建
    if (await dist.exists()) {
      await dist.delete(recursive: true);
    }
    await dist.create(recursive: true);

    // 列出所有 assets，找到 html/dist 相关的
    final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final allAssets = manifest.listAssets();
    final htmlAssets = allAssets.where((a) => a.contains('html/dist')).toList();
    debugPrint('[StaticServer] html assets found: $htmlAssets');

    // 复制每个 asset 到临时目录，保持相对路径
    for (final assetPath in htmlAssets) {
      // assetPath 形如 "assets/html/dist/index.html" 或 "assets/html/dist/assets/xxx.js"
      // 去掉 "assets/html/dist/" 前缀得到相对路径
      final relativePath = assetPath.replaceFirst('assets/html/dist/', '');
      final destPath = '${dist.path}/$relativePath';

      // 确保目标目录存在
      final destFile = File(destPath);
      await destFile.parent.create(recursive: true);

      await _copyAsset(assetPath, destPath);
    }

    // 验证关键文件：index.html 必在，且至少应有一个 index-*.js（vite 产物）。
    // 若 js 缺失，通常是 pubspec.yaml 未声明 dist/assets/ 子目录导致打包遗漏，
    // 此时即使服务起来，WebView 也会因 JS 404 而黑屏，故在此显式告警。
    final htmlFile = File('${dist.path}/index.html');
    debugPrint('[StaticServer] index.html exists: ${htmlFile.existsSync()}');
    final jsDir = Directory('${dist.path}/assets');
    final jsFiles = jsDir.existsSync()
        ? jsDir
            .listSync()
            .whereType<File>()
            .where((f) => f.path.endsWith('.js'))
            .toList()
        : const <File>[];
    debugPrint('[StaticServer] assets/*.js count: ${jsFiles.length}');
    for (final f in jsFiles) {
      debugPrint('[StaticServer]   ${f.path} (${f.lengthSync()} bytes)');
    }
    if (jsFiles.isEmpty) {
      debugPrint('[StaticServer] WARNING: no JS bundle found. '
          'Check pubspec.yaml includes assets/html/dist/assets/');
    }

    return dist;
  }

  /// 从 Flutter asset bundle 复制文件到文件系统。
  Future<void> _copyAsset(String assetPath, String destPath) async {
    try {
      final data = await rootBundle.load(assetPath);
      final bytes = data.buffer.asUint8List();
      await File(destPath).writeAsBytes(bytes);
      debugPrint('[StaticServer] copied $assetPath -> $destPath (${bytes.length} bytes)');
    } catch (e) {
      debugPrint('[StaticServer] FAILED to copy $assetPath: $e');
      rethrow;
    }
  }

  /// 处理 HTTP 请求，返回对应的静态文件。
  /// 根路径 "/" 返回 index.html，其他路径直接映射到临时目录中的文件。
  ///
  /// 注意：必须 `await` [IOSink.addStream] 完成后再 [IOSink.close]。
  /// 旧实现用级联 `..addStream()..close()` 丢弃了 addStream 返回的 Future，
  /// 导致 close 与流写入并发，响应体经常被截断——WebView 永远收不到完整的
  /// index.html / JS，onPageFinished 不触发，前端一直停留在加载动画。
  Future<void> _handleRequest(HttpRequest request) async {
    final uriPath = request.uri.path;
    // "/" -> "index.html", "/assets/xxx.js" -> "assets/xxx.js"
    final relativePath = uriPath == '/' ? 'index.html' : uriPath.substring(1);
    final file = File('${_distDir!.path}/$relativePath');
    debugPrint('[StaticServer] request: $uriPath -> ${file.path}');

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
        _ => 'application/octet-stream',
      };
      try {
        final response = request.response;
        response.headers.contentType = ContentType.parse(contentType);
        response.contentLength = await file.length();
        // 必须等待流写完再 close，否则 close 与写入竞态会截断响应体。
        await response.addStream(file.openRead());
        await response.close();
        debugPrint('[StaticServer] 200 $uriPath');
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
      debugPrint('[StaticServer] 404 $uriPath');
    }
  }
}
