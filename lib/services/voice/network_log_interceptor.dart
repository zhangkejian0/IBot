import 'package:dio/dio.dart';

import '../network_logger.dart';

/// Dio 拦截器：把每次与后端的 HTTP 交互记录进 [NetworkLogger]。
///
/// 记录常规 network 信息：method / url / 请求头 / 请求体 / 状态码 / 响应头 /
/// 响应体 / 端到端耗时 / 错误。请求体/响应体经 [NetworkLogger.sanitize] 折叠
/// 超长字段(base64 音频等)，既看得清「提交了哪些信息」，又不会撑爆日志。
///
/// 流式响应(`ResponseType.stream`，如 `/api/tts/stream`)的响应体是未消费的
/// 字节流，读取会破坏播放，故只记录元信息，响应体标注为 `<stream>`。
class NetworkLogInterceptor extends Interceptor {
  NetworkLogInterceptor(this.logger);

  final NetworkLogger logger;

  /// 在 options.extra 里暂存请求发起时间戳的键。
  static const _startKey = '_netlog_start_ms';

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.extra[_startKey] = DateTime.now().millisecondsSinceEpoch;
    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) {
    _record(response.requestOptions, response: response);
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    _record(err.requestOptions, response: err.response, error: err);
    handler.next(err);
  }

  void _record(
    RequestOptions options, {
    Response? response,
    DioException? error,
  }) {
    if (!logger.enabled) return;
    try {
      final startMs = options.extra[_startKey] as int?;
      final now = DateTime.now();
      final durationMs = startMs == null
          ? null
          : now.millisecondsSinceEpoch - startMs;
      final start = startMs == null
          ? now
          : DateTime.fromMillisecondsSinceEpoch(startMs);

      final isStreamReq = options.responseType == ResponseType.stream;

      final entry = NetworkLogEntry(
        timestamp: start,
        method: options.method,
        url: options.uri.toString(),
        path: options.uri.path,
        requestHeaders:
            logger.sanitize(_stringifyKeys(options.headers)) as Map<String, dynamic>? ??
                const {},
        requestBody: logger.sanitize(options.data),
        statusCode: response?.statusCode,
        responseHeaders: response == null
            ? const {}
            : logger.sanitize(_flattenHeaders(response.headers))
                    as Map<String, dynamic>? ??
                const {},
        responseBody: response == null
            ? null
            : (isStreamReq
                ? '<stream>'
                : logger.sanitize(response.data)),
        durationMs: durationMs,
        requestBytes: NetworkLogger.estimateBytes(options.data),
        responseBytes: (response == null || isStreamReq)
            ? null
            : NetworkLogger.estimateBytes(response.data),
        error: error == null ? null : _describeError(error),
      );
      logger.log(entry);
    } catch (_) {
      // 日志记录本身绝不能影响主请求链路。
    }
  }

  Map<String, dynamic> _stringifyKeys(Map<String, dynamic> headers) {
    final out = <String, dynamic>{};
    headers.forEach((k, v) => out[k] = v);
    return out;
  }

  Map<String, dynamic> _flattenHeaders(Headers headers) {
    final out = <String, dynamic>{};
    headers.forEach((name, values) {
      out[name] = values.length == 1 ? values.first : values;
    });
    return out;
  }

  String _describeError(DioException e) {
    final code = e.response?.statusCode;
    final type = e.type.name;
    final msg = e.message ?? '';
    return [
      type,
      if (code != null) 'HTTP $code',
      if (msg.isNotEmpty) msg,
    ].join(' · ');
  }
}
