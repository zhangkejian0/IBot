/// MediaPipe Pose 33 个人体关键点的骨架连接拓扑(标准 POSE_CONNECTIONS)。
///
/// 索引与 `kwon_mediapipe_landmarker` 的 `PoseLandmarkIndex` 一致:
/// 0-10 头部、11-22 上肢、23-32 下肢。每个元素是一对 `[起点索引, 终点索引]`,
/// 绘制层据此在 [PoseOverlay.landmarks] 之间画骨头连线(见
/// [DetectionOverlayPainter._paintPose])。
///
/// 参照 [HandOverlay] 用 `handLandmarkConnections` 画手骨架的范式;区别在于
/// 手用的是枚举的 `.index`,这里直接用 int 索引(因 Pose 的 landmark 本身不带
/// 枚举类型,按 [PoseLandmarkIndex] 的固定顺序摆位)。
const List<List<int>> poseConnections = [
  // —— 头部轮廓 ——
  [0, 1], // nose — leftEyeInner
  [1, 2], // leftEyeInner — leftEye
  [2, 3], // leftEye — leftEyeOuter
  [3, 7], // leftEyeOuter — leftEar
  [0, 4], // nose — rightEyeInner
  [4, 5], // rightEyeInner — rightEye
  [5, 6], // rightEye — rightEyeOuter
  [6, 8], // rightEyeOuter — rightEar
  [9, 10], // mouthLeft — mouthRight
  // —— 躯干 ——
  [11, 12], // 左肩 — 右肩
  [11, 23], // 左肩 — 左髋
  [12, 24], // 右肩 — 右髋
  [23, 24], // 左髋 — 右髋
  // —— 左臂 ——
  [11, 13], // 左肩 — 左肘
  [13, 15], // 左肘 — 左腕
  [15, 17], // 左腕 — 左小指
  [15, 19], // 左腕 — 左食指
  [15, 21], // 左腕 — 左拇指
  [17, 19], // 左小指 — 左食指
  // —— 右臂 ——
  [12, 14], // 右肩 — 右肘
  [14, 16], // 右肘 — 右腕
  [16, 18], // 右腕 — 右小指
  [16, 20], // 右腕 — 右食指
  [16, 22], // 右腕 — 右拇指
  [18, 20], // 右小指 — 右食指
  // —— 左腿 ——
  [23, 25], // 左髋 — 左膝
  [25, 27], // 左膝 — 左踝
  [27, 29], // 左踝 — 左脚跟
  [27, 31], // 左踝 — 左脚尖
  [29, 31], // 左脚跟 — 左脚尖
  // —— 右腿 ——
  [24, 26], // 右髋 — 右膝
  [26, 28], // 右膝 — 右踝
  [28, 30], // 右踝 — 右脚跟
  [28, 32], // 右踝 — 右脚尖
  [30, 32], // 右脚跟 — 右脚尖
];
