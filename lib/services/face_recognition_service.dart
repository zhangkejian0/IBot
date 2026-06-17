import 'dart:math' as math;

import 'package:collection/collection.dart';
import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;
import 'package:tflite_flutter/tflite_flutter.dart';

import '../models/person.dart';

/// 人脸身份识别服务。
///
/// 使用 MobileFaceNet（TFLite）将人脸裁剪图转换为定长特征向量（embedding），
/// 通过余弦相似度与已录入人物比对得到身份。
///
/// 模型文件需放在 `assets/models/mobilefacenet.tflite`；若缺失则 [isAvailable]
/// 为 false，身份识别功能自动停用（其它识别功能不受影响）。
class FaceRecognitionService {
  static const String _modelAsset = 'models/mobilefacenet.tflite';
  static const int _inputSize = 112;

  Interpreter? _interpreter;
  int _embeddingSize = 192;
  bool _available = false;
  String? _statusMessage;
  // 输出 tensor 的完整 shape（如 [1,192]）。tflite_flutter 要求传入 run() 的
  // 输出容器能 reshape 成此 shape；扁平 Float32List(192) 会被当成 [192]，
  // 与 [1,192] 不匹配 → copyTo 抛 shape mismatch。需按 _outputShape 包装。
  List<int> _outputShape = const [1, 192];
  // identify 诊断日志节流计数器。
  int _identLogCounter = 0;

  // 预分配的输入/输出缓冲区，避免每次 embed() 都新建 112*112*3 个嵌套 List
  // 对象造成 GC 抖动。reshape 为 List 视图供 interpreter.run 使用，不复制数据。
  late final Float32List _inputBuffer =
      Float32List(_inputSize * _inputSize * 3);
  late final Float32List _outputBuffer = Float32List(_embeddingSize);

  bool get isAvailable => _available;
  String? get statusMessage => _statusMessage;
  int get embeddingSize => _embeddingSize;

  /// 识别命中阈值（余弦相似度，范围 -1..1）。
  double matchThreshold = 0.62;

  Future<void> initialize() async {
    Interpreter interpreter;
    try {
      // threads=4 启用 CPU 多核 + XNNPACK，比默认单线程明显加速。
      // MobileFaceNet 112x112 是小模型，GPU delegate 启动开销可能反而变慢，
      // 故先用多线程 CPU（最稳妥），后续可在 Isolate 化时再试 NNAPI。
      final options = InterpreterOptions()..threads = 4;
      interpreter =
          await Interpreter.fromAsset('assets/$_modelAsset', options: options);
    } catch (e) {
      _markUnavailable('未找到身份识别模型 assets/$_modelAsset，身份识别已停用');
      return;
    }

    try {
      // 校验输入契约：必须是 1x112x112x3 float32。
      // 不匹配时优雅降级，避免向模型喂入错误维度的数据。
      final inputTensor = interpreter.getInputTensor(0);
      final inShape = inputTensor.shape;
      const expectedShape = [1, _inputSize, _inputSize, 3];
      final shapeOk = inShape.length == expectedShape.length &&
          ListEquality().equals(inShape, expectedShape);
      if (!shapeOk) {
        interpreter.close();
        _markUnavailable(
          '身份识别模型输入形状不符（得到 $inShape，期望 $expectedShape），身份识别已停用',
        );
        return;
      }

      final outShape = interpreter.getOutputTensor(0).shape;
      // 健壮提取 embedding 维度：不同导出方式的输出 shape 可能是
      // [1,192] / [1,1,1,192] / [1,192,1,1] 等。output buffer 长度必须等于
      // 输出 tensor 的「总元素数」（shape 各维乘积），不能盲取 outShape.last
      // （若 192 不在末位会取错 → buffer 与实际 tensor 不符 → run() 报
      // failed precondition）。embedding 是向量，总元素数即维度。
      var embDim = outShape.fold<int>(1, (p, d) => p * (d > 0 ? d : 1));
      if (embDim <= 0) embDim = 192; // 异常退化
      _embeddingSize = embDim;
      _outputShape = outShape;
      debugPrint('[FaceRecognition] model loaded: inShape=$inShape '
          'outShape=$outShape embeddingSize=$_embeddingSize');
      _interpreter = interpreter;
      _available = true;
      _statusMessage = '身份识别模型已加载（embedding 维度 $_embeddingSize）';
    } catch (e) {
      interpreter.close();
      _markUnavailable('身份识别模型加载异常：$e');
    }
  }

  void _markUnavailable(String message) {
    _available = false;
    _statusMessage = message;
  }

  /// 将一张已裁剪好的人脸图片转换为归一化后的特征向量。
  ///
  /// 任意环节（resize / 推理）抛异常都收敛为返回 null，绝不向上抛 —— 否则在
  /// 录入采集路径里异常会中断 `_processFrame`，导致采集完成回调不被调用、
  /// `_captureCompleter` 残留卡死，只能等超时返回 null（表现为「未捕获到人脸」）。
  List<double>? embed(img.Image faceCrop) {
    try {
      return _embed(faceCrop);
    } catch (e, st) {
      // 完整堆栈定位：是 run() 输入侧（shape/类型）还是输出侧（buffer 尺寸）
      // 抛出的 precondition。此前只 print(e) 丢栈，无法定位精确触发点。
      debugPrint('[FaceRecognition] embed error: $e\n$st');
      return null;
    }
  }

  List<double>? _embed(img.Image faceCrop) {
    final interpreter = _interpreter;
    if (interpreter == null || !_available) return null;

    final resized = img.copyResize(
      faceCrop,
      width: _inputSize,
      height: _inputSize,
      // 与模型归一化一致使用 8 位 RGB；由后续线性变换映射到 [-1,1]。
    );

    // 批量取 RGB 字节（顺序 R,G,B），避免逐像素 getPixel 的对象分配。
    final rgb = resized.getBytes(order: img.ChannelOrder.rgb);
    final buf = _inputBuffer;
    final n = _inputSize * _inputSize;
    // rgb 长度应等于 n*3；若图像通道数与预期不符则回退到逐像素，保证稳健。
    if (rgb.length >= n * 3) {
      for (var i = 0; i < n; i++) {
        final j = i * 3;
        buf[j] = (rgb[j] - 127.5) / 128.0; // R
        buf[j + 1] = (rgb[j + 1] - 127.5) / 128.0; // G
        buf[j + 2] = (rgb[j + 2] - 127.5) / 128.0; // B
      }
    } else {
      // 回退路径：通道数不匹配时逐像素取，仍写入同一扁平 buffer。
      var k = 0;
      for (var y = 0; y < _inputSize; y++) {
        for (var x = 0; x < _inputSize; x++) {
          final px = resized.getPixel(x, y);
          buf[k++] = (px.r - 127.5) / 128.0;
          buf[k++] = (px.g - 127.5) / 128.0;
          buf[k++] = (px.b - 127.5) / 128.0;
        }
      }
    }

    _outputBuffer.fillRange(0, _embeddingSize, 0.0);
    // tflite_flutter 0.12.x 要求传入 run() 的输入/输出容器都能 reshape 成
    // 对应 tensor 的完整 shape，否则报：
    //  - 输入侧："input->dims->size != 4 (1 != 4)" / failed precondition
    //  - 输出侧："Output object shape mismatch ... [1,192] vs [192]"
    // 故输入按 [1,112,112,3]、输出按 _outputShape（如 [1,192]）reshape。
    //
    // 注意：tflite_flutter 的 reshape 会【复制】数据（非视图），所以 run() 把
    // 结果写进了 outputView 这个嵌套副本，而 _outputBuffer 保持 fillRange 的 0。
    // 若直接 _l2Normalize(_outputBuffer) 会得到全 0 向量（bestSim=0.000）。
    // 因此 run 后必须把 outputView 的内容拷回扁平 buffer，再归一化。
    final input4d = buf.reshape([1, _inputSize, _inputSize, 3]);
    final outputView = _outputBuffer.reshape(_outputShape);
    interpreter.run(input4d, outputView);

    // 把嵌套输出视图扁平化写回 _outputBuffer（reshape 是副本，run 只改了副本）。
    final flat = <double>[];
    _flatten(outputView, flat);
    for (var i = 0; i < flat.length && i < _outputBuffer.length; i++) {
      _outputBuffer[i] = flat[i];
    }

    return _l2Normalize(_outputBuffer);
  }

  /// 递归把嵌套 List（run 的输出视图，reshape 后是副本）扁平化收集进 [sink]。
  /// 处理任意维度（如 [1,192]、[1,1,1,192]）。
  /// 必要性：tflite_flutter 的 reshape 复制数据，run 只改副本，需手动拷回。
  void _flatten(dynamic nested, List<double> sink) {
    if (nested is num) {
      sink.add(nested.toDouble());
    } else if (nested is List) {
      for (final e in nested) {
        _flatten(e, sink);
      }
    }
  }

  /// 在已录入人物中寻找最相似者；不足阈值则返回 null。
  IdentityMatch? identify(List<double> embedding, List<Person> people) {
    Person? best;
    double bestSim = -1;
    for (final person in people) {
      for (final sample in person.embeddings) {
        final sim = _cosine(embedding, sample);
        if (sim > bestSim) {
          bestSim = sim;
          best = person;
        }
      }
    }
    // 诊断：前 5 次必打（确保首次对准脸即可看到 bestSim），之后每 30 次打一次。
    if (people.isNotEmpty && (++_identLogCounter <= 5 || (_identLogCounter % 30) == 0)) {
      debugPrint('[FaceRecognition] identify: '
          'people=${people.length} '
          'samples=${people.fold<int>(0, (a, p) => a + p.embeddings.length)} '
          'bestSim=${bestSim.toStringAsFixed(3)} '
          'threshold=$matchThreshold '
          'hit=${bestSim >= matchThreshold}');
    }
    if (best == null || bestSim < matchThreshold) return null;
    return IdentityMatch(person: best, similarity: bestSim);
  }

  List<double> _l2Normalize(List<double> v) {
    double sum = 0;
    for (final x in v) {
      sum += x * x;
    }
    final norm = math.sqrt(sum);
    if (norm == 0) return v;
    return v.map((x) => x / norm).toList();
  }

  double _cosine(List<double> a, List<double> b) {
    if (a.length != b.length) return -1;
    double dot = 0;
    for (var i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
    }
    return dot; // 两侧均为 L2 归一化向量，点积即余弦相似度
  }

  void dispose() {
    _interpreter?.close();
    _interpreter = null;
  }
}
