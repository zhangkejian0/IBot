import 'package:camera/camera.dart';

/// 集中管理 [AppController] 的全部「调参常量」(编译期 const)。
///
/// 把原本散落在 app_controller.dart 各处的 `static const` 集中于此,
/// 便于后期统一维护与调参:所有感知/交互相关的经验阈值、节流间隔、采样
/// 参数都在这里一眼可见,改动无需在文件里四处翻找。
///
/// 形态说明:这些是**经验值类阈值**,目前作为编译期常量;改值需重新编译,
/// 但零运行时开销,适合联调阶段频繁微调后定型的参数。(若后续需要运行时
/// 可调 + 持久化,可仿 `PophieConfig`/`LlmConfig` 演进。)
class AppTuning {
  AppTuning._(); // 纯常量容器,禁止实例化

  // ===========================================================================
  // 注视触发对话(无需唤醒词,持续注视机器人自动发起交互)
  // ===========================================================================

  /// 正视机器人的 gaze 特征点 X(由真实采样校准):正视摄像头时 blendshape
  /// 的系统偏移。判定:gaze 到此点的距离 < [gazeTriggerRadius]。
  /// 阈值依据:9 组采样(5正视/4非正视),正视距离 0.01~0.097,非正视>0.21。
  static const double gazeCenterX = -0.27;

  /// 正视机器人的 gaze 特征点 Y。见 [gazeCenterX]。
  static const double gazeCenterY = 0.31;

  /// 注视触发圆半径(gaze 到正视特征点的距离 < 此值才算正视)。
  ///
  /// **多重门槛**(实测单看 gaze 半径仍偏易触发,故叠加收紧):
  ///   1. gaze 半径收紧到 0.10(正视最远 0.097,留极小余量,排除"大致朝前");
  ///   2. 持续时长 8 秒(路过/偶然对视凑不够);
  ///   3. 同时要求行为态 focused(人静止专注,排除边走边瞥);
  ///   4. 人脸较居中(排除侧着脸正对镜头的误判);
  ///   5. 容错:允许短暂出圆 ≤1.5s 不重置(自然注视会有瞬间偏移)。
  static const double gazeTriggerRadius = 0.12;

  /// 持续注视多久(秒)后触发对话。
  static const int gazeTriggerSeconds = 5;

  /// 触发后冷却时长(秒),期间不再因注视触发(避免刚说完又触发)。
  static const int gazeCooldownSeconds = 60;

  /// 容错:短暂出圆不超过此时长(秒)不重置注视计时(自然注视有瞬间偏移)。
  static const int gazeToleranceSeconds = 1;

  /// 人脸需较居中:face 中心 x 在 [0.5 ± faceCenterTolerance] 范围内。
  static const double faceCenterTolerance = 0.22;

  // ===========================================================================
  // 身份识别与按需采样节流
  // ===========================================================================

  /// 身份识别节流间隔。两次识别运行之间至少间隔此时长。
  static const Duration identityInterval = Duration(milliseconds: 1200);

  /// 按需采样间隔(≈3.3fps)。陪伴场景的信息采集与注视跟随足够;调大更省、
  /// 更流畅,调小更跟手。可按设备实测在 250~600ms 之间权衡。
  ///
  // —— 按需采样(替代持续帧流),避免平台主线程被相机/原生推理长期占用 ——
  // webview_flutter 在 Android 走 Hybrid Composition,WebView 合成发生在
  // Android 主线程;而 camera 的持续 startImageStream 每帧都把 YUV 经平台
  // 通道投递到 Dart 并触发 MediaPipe/MLKit 原生推理,同样占用主线程 ——
  // 两者抢同一线程导致 WebView 周期性掉帧(实测注释掉帧流即彻底不卡)。
  // 方案:平时不开帧流(主线程空闲 → WebView 流畅),用定时器每隔此时长
  // 临时开流抓「一帧」后立即 stopImageStream,再处理这一帧。把对主线程的
  // 占用压成短促、稀疏的脉冲。注视/表情靠 JS 侧 spring 平滑补偿低采样率。
  static const Duration sampleInterval = Duration(milliseconds: 300);

  /// 物体检测独立节流。物体移动远比注视/表情慢,没必要每个检测帧都跑。
  /// YOLO 比 ML Kit 重,且走 JPEG 往返,节流放宽到 700ms(~1.4fps),配合
  /// 后台执行;两次物体检测之间复用上一帧 objects(覆盖层/感知不闪烁)。
  static const Duration objectInterval = Duration(milliseconds: 700);

  // ===========================================================================
  // 物体与感知
  // ===========================================================================

  /// 「手持物体」判定:物体框与手框的中心距离阈值(归一化空间,0..1)。
  /// 手部 landmarks 包围盒通常较紧,放宽到 0.22 兼顾「手握住物体边缘」。
  static const double heldDistance = 0.22;

  /// 发往后端感知的物体置信度下限:低于此值的物体识别不可信,不随语音交互
  /// 发送。仅作用于「发送」(覆盖层显示不受影响,保留调试可见性)。
  static const double perceptionObjectConfidence = 0.65;

  /// 身份 slot TTL:多人脸时为每张脸维持稳定身份(避免逐帧串脸),TTL 内
  /// 即使这一帧未跑识别(节流中),也能凭 slot 给该位置的人脸续上身份。
  static const Duration identityTtl = Duration(seconds: 3);

  /// 中心点距离阈值(归一化空间,0..1),超过则视为不同位置的人脸。
  /// 收紧到 0.15:两人并排时各自中心间距常 < 0.25,过大会让两张脸塌缩到
  /// 同一个 slot,导致串脸。
  static const double slotMatchDistance = 0.15;

  /// 模型/相机加载完成后的固定缓冲,确保首帧前各引擎已彻底就绪。
  static const Duration readyBuffer = Duration(seconds: 2);

  /// 感知日志最小间隔:感知帧高频(~5fps),不能逐帧落盘。做「变化触发 +
  /// 最小间隔」节流,避免日志被重复帧刷爆。
  static const Duration perceptionMinInterval = Duration(seconds: 2);

  // ===========================================================================
  // 相机
  // ===========================================================================

  /// 取流分辨率。物体识别(YOLO)对输入分辨率敏感:过低(如 low≈240p)时
  /// 细节不足,杯子/书/手机这类相似小物体极易被误判。medium(≈480p)在
  /// 准确率与 CPU/内存(逐像素 YUV→RGB 在主 isolate)之间取得平衡;若设备
  /// 算力不足出现卡顿,可下调回 low;追求更高精度可上调到 high(720p)。
  static const ResolutionPreset captureResolution = ResolutionPreset.medium;
}
