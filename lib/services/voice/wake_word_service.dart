import 'dart:async';
import 'dart:convert';
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
/// 关键词文本(如"你好")→ lpinyin 转带声调拼音 → 拆成声母/韵母 token →
/// 内联传给 createStream(keywords: ...)。
class WakeWordService {
  // 阶段 2 加载 sherpa-onnx KWS 模型后,根据加载结果改写。
  bool _available = false; // ignore: prefer_final_fields
  bool get isAvailable => _available;

  /// 最近一次加载失败的原因(start 被拦截时打印,避免在启动日志里翻找)。
  String? _loadError;

  bool _running = false;
  bool get isRunning => _running;

  /// 当前唤醒词(默认"你好",可在设置页修改)。
  String _keyword = '你好';
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
      // 预先算好初始关键词行及其 UTF-8 字节长度。
      // sherpa-onnx 创建 spotter 时强制要求 keywords-file 或 keywords-buf
      // 非空,且 keywordsBufSize 必须显式给出(绑定层用 std::string(buf,size)
      // 读取,size=0 时即使 buf 有内容也当空串)。详见下方注释。
      final keywordLine = _buildKeywordLine(_keyword);
      final keywordLineBytes = utf8.encode(keywordLine).length;
      debugPrint('[WakeWord] init keywordLine="$keywordLine" '
          'bytes=$keywordLineBytes');
      final cfg = s.KeywordSpotterConfig(
        model: s.OnlineModelConfig(
          transducer: s.OnlineTransducerModelConfig(
            encoder: '$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
            decoder: '$dir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
            joiner: '$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
          ),
          tokens: '$dir/tokens.txt',
          numThreads: 2,
          provider: 'cpu',
          // modelType 必须留空!显式写 'zipformer' 会让 sherpa-onnx 走
          // 错误的加载分支,强制读不存在的 'attention_dims' 元数据 →
          // native crash(online-zipformer-transducer-model.cc:112)。
          // 留空时它根据 encoder+decoder+joiner 自动判定为 transducer。
          // debug=true 让原生层打印详细日志,便于排查(确认稳定后可关)。
          debug: true,
          modelingUnit: 'cjkchar',
        ),
        keywordsThreshold: 0.1,
        keywordsScore: 1.0,
        // sherpa-onnx 创建 spotter 时强制要求有关键词(keywords-file 或
        // keywords-buf 至少一个非空),否则 keyword-spotter.cc:Validate 报
        // "Please provide either a keywords-file or the keywords-buf" →
        // 返回 nullptr。这里用 keywordsBuf 传入初始唤醒词。
        //
        // 关键:keywordsBufSize 必须显式设为 buf 的 UTF-8 字节长度!绑定层
        // (keyword_spotter.dart:162-163)用 std::string(buf, size) 读取,
        // size=0(构造函数默认)时即使 buf 有内容也会被当空串 → 校验仍失败。
        keywordsBuf: keywordLine,
        keywordsBufSize: keywordLineBytes,
      );
      _spotter = s.KeywordSpotter(cfg);
      _available = true;
      debugPrint('[WakeWord] initialized, keyword=$_keyword');
    } catch (e) {
      _available = false;
      _loadError = e.toString();
      debugPrint('[WakeWord] initialize failed: $e');
      // 诊断:加载失败时打印临时目录实际文件列表与期望的 onnx 路径,
      // 避免再次出现"文件名拼错被上层 || true 静默"的情况。
      try {
        final temp = await getTemporaryDirectory();
        final dest = Directory('${temp.path}/xbot_voice_model/$_modelDirName');
        final files = dest.existsSync()
            ? dest.listSync().map((f) => f.path.split(RegExp(r'[/\\]')).last).join(', ')
            : '<dir not found>';
        debugPrint('[WakeWord] diagnostic dir=${dest.path} '
            'existingFiles=[$files]');
        // 逐个校验三个 onnx 是否存在,定位到底是哪个文件缺/路径错。
        for (final n in [
          'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
          'decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
          'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
          'tokens.txt',
        ]) {
          final f = File('${dest.path}/$n');
          final exists = await f.exists();
          final size = exists ? await f.length() : 0;
          debugPrint('[WakeWord] check $n exists=$exists size=$size');
        }
      } catch (diag) {
        debugPrint('[WakeWord] diagnostic itself failed: $diag');
      }
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
  /// 范式同 static_server.dart 的 _prepareAssets。已复制且完整则复用,
  /// 避免重复 IO;残缺(如旧版本残留)则强制重补。
  Future<String> _prepareModelFiles() async {
    final temp = await getTemporaryDirectory();
    final dest = Directory('${temp.path}/xbot_voice_model/$_modelDirName');
    // 必需文件齐全才复用;缺一个都重新复制(自愈旧版残留/复制中断)。
    final required = [
      'tokens.txt',
      'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
      'decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
      'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    ];
    final allPresent =
        await Future.wait(required.map((n) => File('${dest.path}/$n').exists()));
    if (allPresent.every((p) => p)) {
      debugPrint('[WakeWord] reuse existing model dir ${dest.path} '
          '(all required files present)');
      return dest.path;
    }

    await dest.create(recursive: true);
    final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final assets = manifest
        .listAssets()
        .where((a) => a.startsWith('$_modelAssetRoot/$_modelDirName/'))
        .toList();
    debugPrint('[WakeWord] manifest assets found: ${assets.length}');
    // manifest 匹配到 0 个文件,说明 assets 没被打包进 apk(pubspec.yaml
    // 的目录声明不递归,必须显式声明模型所在子目录)。直接抛错,避免后面
    // 静默跳过导致空目录 → "Failed to create kws" 的迷惑现象。
    if (assets.isEmpty) {
      throw Exception(
          'No model assets found in manifest under '
          '$_modelAssetRoot/$_modelDirName/. '
          '请检查 pubspec.yaml 是否显式声明了该子目录 '
          '(Flutter assets 声明不递归)。');
    }
    final copied = <String>[];
    final skipped = <String>[];
    for (final assetPath in assets) {
      final rel = assetPath.replaceFirst('$_modelAssetRoot/$_modelDirName/', '');
      // 跳过非必需文件(test_wavs、全精度 onnx),减小复制量与体积。
      // encoder/decoder/joiner 一律只取 int8 版(体积小),其余 .onnx 跳过。
      if (rel.startsWith('test_wavs/') ||
          rel.contains('-mobile') ||
          rel.endsWith('.tar.bz2')) {
        skipped.add(rel);
        continue;
      }
      if (rel.endsWith('.onnx') && !rel.contains('int8')) {
        skipped.add(rel);
        continue;
      }
      final outFile = File('${dest.path}/$rel');
      await outFile.parent.create(recursive: true);
      final data = await rootBundle.load(assetPath);
      await outFile.writeAsBytes(data.buffer.asUint8List());
      copied.add(rel);
    }
    debugPrint('[WakeWord] model files copied to ${dest.path} '
        'copied=$copied skipped=$skipped');
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
    // 被拦截时打印具体原因,便于区分"模型没加载 / 已在跑 / 流未接"。
    if (!_available) {
      debugPrint('[WakeWord] start SKIPPED: not available. '
          'loadError=${_loadError ?? "(initialize 未调用或成功?)"}');
      return;
    }
    if (_running) {
      debugPrint('[WakeWord] start SKIPPED: already running');
      return;
    }
    if (_spotter == null) {
      debugPrint('[WakeWord] start SKIPPED: spotter is null (model not loaded)');
      return;
    }
    _running = true;
    _stream = _spotter!.createStream(keywords: _buildKeywordLine(_keyword));
    _sub = pcmStream.listen(_onPcm);
    debugPrint('[WakeWord] start listening keyword=$_keyword '
        'threshold=0.1 line="${_buildKeywordLine(_keyword)}"');
  }

  int _pcmCount = 0; // 节流计数:每 50 片打一次点,确认音频确实流入。
  /// 处理一片 16-bit PCM:转 Float32 喂给 stream,解码并取结果。
  void _onPcm(Uint8List bytes) {
    final stream = _stream;
    final spotter = _spotter;
    if (!_running || stream == null || spotter == null) return;
    final samples = _pcm16ToFloat32(bytes);
    stream.acceptWaveform(samples: samples, sampleRate: 16000);
    // 周期打点:确认 PCM 在流入 + 当前最大音量(判断是否静音/麦克风未授权)。
    if (++_pcmCount % 50 == 0) {
      double maxAbs = 0;
      for (final s in samples) {
        final a = s.abs();
        if (a > maxAbs) maxAbs = a;
      }
      debugPrint('[WakeWord] pcm tick #$_pcmCount frames=${samples.length} '
          'maxAmplitude=${maxAbs.toStringAsFixed(4)}');
    }
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
  // 形如 "n ǐ h ǎo @你好"(多词用 / 分隔)。
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
