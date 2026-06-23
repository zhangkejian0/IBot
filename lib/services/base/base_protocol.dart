import 'dart:convert';
import 'dart:typed_data';

/// 底座控制协议层（与 docs/底座控制协议.md 对齐）。
///
/// 经典蓝牙 SPP(RFCOMM)是字节流，没有天然消息边界，因此约定：
///   - 每帧为单行 UTF-8 JSON 文本
///   - 行尾以 '\n'(0x0A) 作为帧分隔符
///   - 收发两端均按 '\n' 切帧
///
/// 本类只负责"字节流 ↔ JSON 帧"的编解码，不涉及蓝牙传输，
/// 与 [BaseService] 的传输实现解耦。
class BaseProtocol {
  BaseProtocol();

  // 流式接收缓冲区（字节），跨包拼接。
  final List<int> _rxBuf = <int>[];

  /// 把一条 JSON 指令编码为发送字节：jsonEncode + '\n'。
  ///
  /// [cmd] 指令名（如 move/home/get_version），[params] 为其余字段。
  /// 例: encode('move', {'yaw':45.0,'pitch':-15.0})
  ///   → '{"cmd":"move","yaw":45.0,"pitch":-15.0}\n'
  Uint8List encode(String cmd, Map<String, dynamic> params) {
    final json = <String, dynamic>{'cmd': cmd, ...params};
    final text = '${jsonEncode(json)}\n';
    return Uint8List.fromList(utf8.encode(text));
  }

  /// 喂入一段接收到的字节，返回其中已完整成帧（按 '\n' 切出）的 JSON 解析结果。
  ///
  /// 未结束的尾部留在缓冲区，等下次喂入续上（处理一帧分多次到达）。
  /// 解析失败的帧被跳过（调用方无法感知坏帧；日志层已在传输侧记录原始字节）。
  List<Map<String, dynamic>> feed(List<int> bytes) {
    _rxBuf.addAll(bytes);
    final frames = <Map<String, dynamic>>[];
    while (true) {
      final nl = _rxBuf.indexOf(0x0A); // '\n'
      if (nl < 0) break; // 缓冲区里没有完整帧
      final raw = _rxBuf.sublist(0, nl);
      _rxBuf.removeRange(0, nl + 1);
      final text = utf8.decode(raw, allowMalformed: true).trim();
      if (text.isEmpty) continue;
      try {
        final obj = jsonDecode(text);
        if (obj is Map<String, dynamic>) {
          frames.add(obj);
        }
      } catch (_) {
        // 坏帧：忽略，等待下一帧
      }
    }
    return frames;
  }

  /// 重置接收缓冲区（断开重连时调用，丢弃半截帧）。
  void reset() => _rxBuf.clear();
}
