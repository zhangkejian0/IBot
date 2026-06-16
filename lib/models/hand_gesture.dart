import 'package:hand_detection/hand_detection.dart';

/// 手势类型的中文名称与 emoji 映射。
extension GestureTypeInfo on GestureType {
  String get label {
    switch (this) {
      case GestureType.closedFist:
        return '握拳';
      case GestureType.openPalm:
        return '张开手掌';
      case GestureType.pointingUp:
        return '食指向上';
      case GestureType.thumbDown:
        return '拇指向下';
      case GestureType.thumbUp:
        return '点赞';
      case GestureType.victory:
        return '胜利';
      case GestureType.iLoveYou:
        return '我爱你';
      case GestureType.unknown:
        return '未知手势';
    }
  }

  String get emoji {
    switch (this) {
      case GestureType.closedFist:
        return '✊';
      case GestureType.openPalm:
        return '🖐️';
      case GestureType.pointingUp:
        return '☝️';
      case GestureType.thumbDown:
        return '👎';
      case GestureType.thumbUp:
        return '👍';
      case GestureType.victory:
        return '✌️';
      case GestureType.iLoveYou:
        return '🤟';
      case GestureType.unknown:
        return '❓';
    }
  }
}
