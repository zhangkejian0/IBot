import 'package:flutter/widgets.dart';

import '../theme/app_theme.dart';

/// 7 种常见表情（Ekman 经典 6 种 + 中性）。
enum Expression {
  neutral, // 中性
  happy, // 高兴
  sad, // 伤心
  surprised, // 惊讶
  angry, // 愤怒
  disgusted, // 厌恶
  fearful, // 恐惧
}

extension ExpressionInfo on Expression {
  String get label {
    switch (this) {
      case Expression.neutral:
        return '中性';
      case Expression.happy:
        return '高兴';
      case Expression.sad:
        return '伤心';
      case Expression.surprised:
        return '惊讶';
      case Expression.angry:
        return '愤怒';
      case Expression.disgusted:
        return '厌恶';
      case Expression.fearful:
        return '恐惧';
    }
  }

  String get emoji {
    switch (this) {
      case Expression.neutral:
        return '😐';
      case Expression.happy:
        return '😄';
      case Expression.sad:
        return '😢';
      case Expression.surprised:
        return '😲';
      case Expression.angry:
        return '😠';
      case Expression.disgusted:
        return '🤢';
      case Expression.fearful:
        return '😨';
    }
  }

  Color get color {
    switch (this) {
      case Expression.neutral:
        return AppTheme.secondaryLabel;
      case Expression.happy:
        return AppTheme.accentGreen;
      case Expression.sad:
        return AppTheme.accentTeal;
      case Expression.surprised:
        return AppTheme.accentYellow;
      case Expression.angry:
        return AppTheme.accentRed;
      case Expression.disgusted:
        return AppTheme.accentPurple;
      case Expression.fearful:
        return AppTheme.accentOrange;
    }
  }
}

/// 表情识别结果：最可能的表情、其置信度，以及全部表情的打分（调试用）。
class ExpressionResult {
  final Expression expression;
  final double score;
  final Map<Expression, double> scores;

  const ExpressionResult({
    required this.expression,
    required this.score,
    required this.scores,
  });

  static const ExpressionResult neutral = ExpressionResult(
    expression: Expression.neutral,
    score: 1.0,
    scores: {Expression.neutral: 1.0},
  );
}
