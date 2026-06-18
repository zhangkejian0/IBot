import 'package:flutter/foundation.dart';

/// 一条 Pophie 对话交互记录。记录一轮对话中各阶段的关键信息,供设置页
/// 「交互日志」查看,便于排查「说了话但没反应」「表情卡住」等问题。
///
/// 一轮对话的典型阶段(与 [VoiceAssistant._runConversation] 对应):
/// 唤醒 → 聆听(STT) → 思考(LLM) → 播报(TTS) → 结束/出错。
/// 每个阶段都通过 [ConversationLogger.log] 追加一条记录。
class ConversationEntry {
  ConversationEntry({
    required this.timestamp,
    required this.stage,
    required this.message,
    this.error = false,
  });

  /// 记录时刻(用于排序与展示「多久前」)。
  final DateTime timestamp;
  /// 阶段标识:wake / listen / think / speak / end / error 等。
  final String stage;
  /// 可读详情(如识别到的文本、LLM 回复、音频字节数、错误信息)。
  final String message;
  /// 是否为错误记录(错误在 UI 上用红色标记)。
  final bool error;

  @override
  String toString() =>
      '[${timestamp.toIso8601String()}] $stage: $message${error ? ' [ERROR]' : ''}';
}

/// 对话交互日志收集器。以环形缓冲保存最近若干条记录,对外是 [ChangeNotifier],
/// 设置页订阅它实时刷新。记录同时输出到 debugPrint,控制台也能看到。
///
/// 容量有限([maxEntries]),超出自动丢弃最早的,避免内存无限增长。
class ConversationLogger extends ChangeNotifier {
  ConversationLogger({this.maxEntries = 200});

  /// 最多保留的记录条数(FIFO,超出丢弃最早)。
  final int maxEntries;
  final List<ConversationEntry> _entries = [];

  /// 当前所有记录(按时间顺序,最旧在前)。返回只读视图。
  List<ConversationEntry> get entries => List.unmodifiable(_entries);

  /// 追加一条记录并通知监听者。同步打印到控制台。
  void log(String stage, String message, {bool error = false}) {
    final e = ConversationEntry(
      timestamp: DateTime.now(),
      stage: stage,
      message: message,
      error: error,
    );
    _entries.add(e);
    while (_entries.length > maxEntries) {
      _entries.removeAt(0);
    }
    debugPrint('[ConvLog] ${e.toString()}');
    notifyListeners();
  }

  /// 清空所有记录。
  void clear() {
    _entries.clear();
    notifyListeners();
  }
}
