import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'services/static_server.dart';

/// 独立的 WebView 测试入口 - 仅加载 HTML 页面，不启动其他进程。
///
/// 使用方式：flutter run -t lib/webview_only.dart
void main() {
  runApp(const WebViewOnlyApp());
}

class WebViewOnlyApp extends StatelessWidget {
  const WebViewOnlyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WebView Test',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: const WebViewOnlyPage(),
    );
  }
}

class WebViewOnlyPage extends StatefulWidget {
  const WebViewOnlyPage({super.key});

  @override
  State<WebViewOnlyPage> createState() => _WebViewOnlyPageState();
}

class _WebViewOnlyPageState extends State<WebViewOnlyPage> {
  final StaticServer _server = StaticServer();
  late final WebViewController _controller;
  bool _loaded = false;
  bool _serverStarted = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..setNavigationDelegate(NavigationDelegate(
        onPageStarted: (url) {
          debugPrint('[WebView] onPageStarted: $url');
        },
        onPageFinished: (url) {
          debugPrint('[WebView] onPageFinished: $url');
          if (mounted) {
            setState(() => _loaded = true);
          }
        },
        onWebResourceError: (error) {
          debugPrint('[WebView] error: ${error.errorType} ${error.description}');
          if (mounted) {
            setState(() => _error = error.description);
          }
        },
      ))
      ..enableZoom(false);

    _startServer();
  }

  Future<void> _startServer() async {
    try {
      debugPrint('[WebView] Starting server...');
      final url = await _server.start();
      debugPrint('[WebView] Server started at: $url');

      if (mounted) {
        setState(() => _serverStarted = true);
        final loadUrl = '$url?style=ambient';
        debugPrint('[WebView] Loading: $loadUrl');
        _controller.loadRequest(Uri.parse(loadUrl));
      }
    } catch (e) {
      debugPrint('[WebView] Server start failed: $e');
      if (mounted) {
        setState(() => _error = 'Server start failed: $e');
      }
    }
  }

  @override
  void dispose() {
    _server.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          if (_serverStarted)
            WebViewWidget(controller: _controller),

          // 加载指示器
          if (!_loaded && _error == null)
            const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircularProgressIndicator(color: Colors.white),
                  SizedBox(height: 16),
                  Text(
                    'Loading WebView...',
                    style: TextStyle(color: Colors.white),
                  ),
                ],
              ),
            ),

          // 错误显示
          if (_error != null)
            Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.error, color: Colors.red, size: 48),
                  const SizedBox(height: 16),
                  Text(
                    'Error: $_error',
                    style: const TextStyle(color: Colors.red),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton(
                    onPressed: () {
                      setState(() {
                        _error = null;
                        _loaded = false;
                        _serverStarted = false;
                      });
                      _startServer();
                    },
                    child: const Text('Retry'),
                  ),
                ],
              ),
            ),

          // 状态信息
          Positioned(
            bottom: 16,
            left: 16,
            child: SafeArea(
              child: Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  'Server: ${_serverStarted ? "Running" : "Starting"}\n'
                  'Loaded: $_loaded',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
