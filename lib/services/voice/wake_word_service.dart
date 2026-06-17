import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show AssetManifest, rootBundle;
import 'package:lpinyin/lpinyin.dart';
import 'package:path_provider/path_provider.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as s;

/// 本地离线唤醒服务:基于 sherpa-onnx 关键词检测(KWS)。
///
/// 采用 wenetspeech 拼音建模模型 —— 唤醒词以「声母+韵母(带声调)」token 序列
/// 表示,可在运行时通过 [setKeyword] 修改(开放词表,无需重新训练模型)。
///
/// 关键词文本(如"狗蛋")→ lpinyin 转带声调拼音 → 拆成声母/韵母 token →
/// 内联传给 createStream(keywords: ...)。
class WakeWordService {
  // 阶段 2 加载 sherpa-onnx KWS 模型后,根据加载结果改写。
  bool _available = false; // ignore: prefer_final_fields
  bool get isAvailable => _available;

  bool _running = false;
  bool get isRunning => _running;

  /// 当前唤醒词(默认"狗蛋",可在设置页修改)。
  String _keyword = '狗蛋';
  String get keyword => _keyword;

  /// 检测到唤醒词时触发(主回调)。由 [VoiceAssistant] 订阅以进入 waking 态。
  final StreamController<String> _onWake = StreamController<String>.broadcast();
  Stream<String> get onWake => _onWake.stream;

  // sherpa-onnx 句柄与运行态。
  s.KeywordSpotter? _spotter;
  s.OnlineStream? _stream;
  StreamSubscription<Uint8List>? _sub;
  bool _bindingsInited = false;

  /// 模型文件在临时目录里的根路径(FFI 需要真实文件系统路径,不能用 asset)。
  static const _modelAssetRoot = 'assets/models/voice';
  static const _modelDirName =
      'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01';

  /// 加载 KWS 模型并初始化识别器。
  ///
  /// 把 assets/models/voice/ 下的模型文件复制到临时目录,再用 sherpa-onnx
  /// 的 KeywordSpotter 配置初始化。失败不抛异常,置 [isAvailable]=false,
  /// 调用方据此降级。
  Future<void> initialize() async {
    if (_available) return;
    try {
      _initBindingsOnce();
      final dir = await _prepareModelFiles();
      final cfg = s.KeywordSpotterConfig(
        model: s.OnlineModelConfig(
          transducer: s.OnlineTransducerModelConfig(
            encoder: '$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
            decoder: '$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx',
            joiner: '$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
          ),
          tokens: '$dir/tokens.txt',
          numThreads: 2,
          provider: 'cpu',
          debug: false,
        ),
        keywordsThreshold: 0.25,
        keywordsScore: 1.0,
        // 不用 keywords_file:唤醒词走 createStream 的内联参数,便于运行时改。
      );
      _spotter = s.KeywordSpotter(cfg);
      _available = true;
      debugPrint('[WakeWord] initialized, keyword=$_keyword');
    } catch (e) {
      _available = false;
      debugPrint('[WakeWord] initialize failed: $e');
    }
  }

  /// sherpa-onnx FFI 绑定只初始化一次。Android/iOS 上 .so/.framework 由
  /// 打包进 apk/app,DynamicLibrary.open 用默认名即可找到。
  void _initBindingsOnce() {
    if (_bindingsInited) return;
    s.initBindings();
    _bindingsInited = true;
  }

  /// 把模型文件从 assets 复制到临时目录,返回模型目录的真实路径。
  /// 范式同 static_server.dart 的 _prepareAssets。已复制则复用,避免重复 IO。
  Future<String> _prepareModelFiles() async {
    final temp = await getTemporaryDirectory();
    final dest = Directory('${temp.path}/xbot_voice_model/$_modelDirName');
    final marker = File('${dest.path}/tokens.txt');
    if (!await marker.exists()) {
      await dest.create(recursive: true);
      final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
      final assets = manifest
          .listAssets()
          .where((a) => a.startsWith('$_modelAssetRoot/$_modelDirName/'))
          .toList();
      for (final assetPath in assets) {
        final rel = assetPath.replaceFirst('$_modelAssetRoot/$_modelDirName/', '');
        // 跳过非必需文件(test_wavs、全精度 onnx),减小复制量与体积。
        if (rel.startsWith('test_wavs/') ||
            rel.contains('-mobile') ||
            rel.endsWith('.tar.bz2')) {
          continue;
        }
        // 只保留 encoder/decoder/joiner 的 int8 版(体积小)与 tokens。
        if (rel.endsWith('.onnx') &&
            !rel.contains('int8') &&
            (rel.startsWith('encoder') ||
                rel.startsWith('decoder') ||
                rel.startsWith('joiner'))) {
          // decoder 官方仅 int8 可用?实测用非 int8 decoder 也行;但为统一都取 int8。
          // encoder/joiner 取 int8;decoder 同样取 int8(若存在)。
          if (!rel.contains('int8')) continue;
        }
        final outFile = File('${dest.path}/$rel');
        await outFile.parent.create(recursive: true);
        final data = await rootBundle.load(assetPath);
        await outFile.writeAsBytes(data.buffer.asUint8List());
      }
      debugPrint('[WakeWord] model files copied to ${dest.path}');
    }
    return dest.path;
  }

  /// 修改唤醒词。开放词表模型支持运行时改词:重建 stream 即生效。
  Future<void> setKeyword(String keyword) async {
    if (keyword.isEmpty || keyword == _keyword) return;
    _keyword = keyword;
    debugPrint('[WakeWord] keyword changed -> $_keyword');
    // 运行中则重建 stream,使新词立即生效。
    if (_running && _spotter != null) {
      _stream?.free();
      _stream = _spotter!.createStream(keywords: _buildKeywordLine(_keyword));
    }
  }

  /// 开始监听:消费 [pcmStream] 的 16-bit PCM 分片,逐片送入 KWS,
  /// 命中则向 [onWake] 推送。
  Future<void> start(Stream<Uint8List> pcmStream) async {
    if (!_available || _running || _spotter == null) return;
    _running = true;
    _stream = _spotter!.createStream(keywords: _buildKeywordLine(_keyword));
    _sub = pcmStream.listen(_onPcm);
    debugPrint('[WakeWord] start listening keyword=$_keyword');
  }

  /// 处理一片 16-bit PCM:转 Float32 喂给 stream,解码并取结果。
  void _onPcm(Uint8List bytes) {
    final stream = _stream;
    final spotter = _spotter;
    if (!_running || stream == null || spotter == null) return;
    final samples = _pcm16ToFloat32(bytes);
    stream.acceptWaveform(samples: samples, sampleRate: 16000);
    while (spotter.isReady(stream)) {
      spotter.decode(stream);
      final kw = spotter.getResult(stream).keyword;
      if (kw.isNotEmpty) {
        // 命中:重置 stream 避免连续重复触发,并推送唤醒事件。
        spotter.reset(stream);
        _onWake.add(_keyword);
        debugPrint('[WakeWord] DETECTED: $kw');
      }
    }
  }

  /// 停止监听。
  Future<void> stop() async {
    _running = false;
    await _sub?.cancel();
    _sub = null;
    _stream?.free();
    _stream = null;
    debugPrint('[WakeWord] stop');
  }

  Future<void> dispose() async {
    await stop();
    _spotter?.free();
    _spotter = null;
    await _onWake.close();
  }

  // —— 唤醒词文本 → sherpa-onnx 内联关键词行 ——
  // 形如 "g ǒu d àn @狗蛋"(多词用 / 分隔)。
  String _buildKeywordLine(String keyword) {
    // 中文 → 带声调拼音,空格分隔音节。
    final pinyin = PinyinHelper.getPinyin(keyword,
        separator: ' ', format: PinyinFormat.WITH_TONE_MARK);
    final syllables = pinyin.split(' ');
    final tokens = <String>[];
    for (final syl in syllables) {
      tokens.addAll(_splitSyllable(syl));
    }
    return '${tokens.join(' ')} @$keyword';
  }

  /// 把一个拼音音节(如 "xiǎo")拆成 [声母, 韵母](如 ["x","iǎo"])。
  /// 零声母音节(如 "ài")整音节作为单 token。
  List<String> _splitSyllable(String syllable) {
    const initials = ['zh', 'ch', 'sh', 'b', 'p', 'm', 'f', 'd', 't', 'n',
      'l', 'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w'];
    for (final ini in initials) {
      if (syllable.startsWith(ini)) {
        final finalPart = syllable.substring(ini.length);
        if (finalPart.isNotEmpty) return [ini, finalPart];
        return [ini];
      }
    }
    // 无声母(y/w 在词首视作声母,其余元音开头的整音节作单 token)。
    return [syllable];
  }

  /// 16-bit PCM 字节 → Float32 归一化样本(-1..1)。sherpa-onnx 接收 Float32List。
  Float32List _pcm16ToFloat32(Uint8List bytes) {
    final frameCount = bytes.length ~/ 2;
    final out = Float32List(frameCount);
    final data = ByteData.sublistView(bytes);
    for (var i = 0; i < frameCount; i++) {
      out[i] = data.getInt16(i * 2, Endian.little) / 32768.0;
    }
    return out;
  }
}
