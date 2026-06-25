/// 虚拟人物 FSM 状态（与网页 assets/html/src/face/types.ts 的 FaceState 保持一致）。
///
/// 这里只枚举本端实际会用到的子集，用于驱动可显示的机器人态。
/// 完整集合见网页 types.ts。
enum FaceState {
  idle, // 待机（中性）
  gazing, // 注视
  listening, // 聆听
  thinking, // 思考
  happy, // 高兴（大笑）
  confused, // 困惑
  angry, // 愤怒
  sleepy, // 困倦
  sleeping, // 睡眠
  waking, // 苏醒
}
