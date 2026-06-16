import 'dart:math' as math;

import 'package:collection/collection.dart';
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

  bool get isAvailable => _available;
  String? get statusMessage => _statusMessage;
  int get embeddingSize => _embeddingSize;

  /// 识别命中阈值（余弦相似度，范围 -1..1）。
  double matchThreshold = 0.62;

  Future<void> initialize() async {
    Interpreter interpreter;
    try {
      interpreter = await Interpreter.fromAsset('assets/$_modelAsset');
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
    );

    // 输入张量 [1, 112, 112, 3]，归一化到 [-1, 1]
    final input = List.generate(
      1,
      (_) => List.generate(
        _inputSize,
        (y) => List.generate(_inputSize, (x) {
          final px = resized.getPixel(x, y);
          return [
            (px.r - 127.5) / 128.0,
            (px.g - 127.5) / 128.0,
            (px.b - 127.5) / 128.0,
          ];
        }),
      ),
    );

    final output = List.generate(1, (_) => List.filled(_embeddingSize, 0.0));
    interpreter.run(input, output);

    return _l2Normalize(output[0]);
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
