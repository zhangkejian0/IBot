// XBot 基础冒烟测试。
//
// 注意：完整应用依赖摄像头与原生 ML 插件，无法在纯单元测试环境中初始化。
// 这里仅验证表情分类器等纯 Dart 逻辑可正常工作。

import 'package:flutter_test/flutter_test.dart';
import 'package:kwon_mediapipe_landmarker/kwon_mediapipe_landmarker.dart';
import 'package:xbot/models/expression.dart';
import 'package:xbot/services/expression_classifier.dart';

void main() {
  test('空 blendshapes 判定为中性', () {
    const classifier = ExpressionClassifier();
    final result = classifier.classify(const {});
    expect(result.expression, Expression.neutral);
  });

  test('强烈微笑判定为高兴', () {
    const classifier = ExpressionClassifier();
    final result = classifier.classify({
      FaceBlendshape.mouthSmileLeft: 0.9,
      FaceBlendshape.mouthSmileRight: 0.9,
      FaceBlendshape.cheekSquintLeft: 0.5,
      FaceBlendshape.cheekSquintRight: 0.5,
    });
    expect(result.expression, Expression.happy);
  });
}
