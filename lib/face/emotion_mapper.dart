import '../models/expression.dart';

/// 虚拟人物 FSM 状态（与网页 assets/html/src/face/types.ts 的 FaceState 保持一致）。
///
/// 这里只枚举本端实际会用到的子集，用于把检测到的情绪映射成可显示的机器人态。
/// 完整集合见网页 types.ts。
enum FaceState {
  idle, // 待机（中性）
  gazing, // 注视
  listening, // 聆听
  thinking, // 思考
  happy, // 高兴（大笑）
  confused, // 困惑
  sleepy, // 困倦
  sleeping, // 睡眠
  waking, // 苏醒
}

/// 情绪 → 虚拟人物 FSM 状态映射（方案 A：情绪驱动拟人态）。
///
/// 网页的 FSM 是机器人交互态（听/想/注视/困），并非情绪分类器，故把负面与
/// 异常情绪收敛成有限的反应：
///   neutral     -> idle      无情绪即待机
///   happy       -> happy     大笑（1:1）
///   sad         -> sleepy    低落 -> 耷拉半闭眼
///   angry       -> confused  负面 -> 歪头困惑
///   disgusted   -> confused
///   fearful     -> confused  异常 -> 困惑
///   surprised   -> confused
class EmotionMapper {
  const EmotionMapper();

  FaceState map(Expression emotion) {
    switch (emotion) {
      case Expression.neutral:
        return FaceState.idle;
      case Expression.happy:
        return FaceState.happy;
      case Expression.sad:
        return FaceState.sleepy;
      case Expression.angry:
      case Expression.disgusted:
      case Expression.fearful:
      case Expression.surprised:
        return FaceState.confused;
    }
  }
}
