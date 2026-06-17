import 'dart:math' as math;
import 'dart:typed_data';

import 'package:collection/collection.dart';
import 'package:image/image.dart' as img;
import 'package:flutter_litert/flutter_litert.dart';

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
      _embeddingSize = outShape.isNotEmpty ? outShape.last : 192;
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
  List<double>? embed(img.Image faceCrop) {
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

    // 复用预分配输出缓冲。tflite_flutter 内部对输入做 flatten，扁平
    // Float32List 已是平铺结构，直接传入即可，无需 reshape（reshape 会复制）。
    _outputBuffer.fillRange(0, _embeddingSize, 0.0);
    interpreter.run(buf, _outputBuffer);

    return _l2Normalize(_outputBuffer);
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
