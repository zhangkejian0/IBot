import 'dart:math' as math;
import 'dart:ui' show Offset, Rect;

import '../models/detection.dart';

/// 日常活动状态(与 [BehaviorState] 注意力状态正交的另一个维度)。
///
/// 注意:活动与注意力互不排斥——人可以「专注地喝水」。本状态机独立于
/// [BehaviorStateTracker] 运行,聚焦「正在做什么活动」。
enum Activity {
  /// 无明确活动(兜底)。
  none,

  /// 喝水:手持杯子/瓶子且举到嘴边。
  drinking,

  /// 看手机:手持手机且视线下移。
  lookingAtPhone,

  /// 交谈/说话:嘴部连续开合有节奏。
  talking,

  /// 打哈欠:嘴张大持续,常伴闭眼。
  yawning,

  /// 举手/打招呼:手腕高于肩。
  handRaised,

  /// 托腮/撑脸:手举起并停在脸的侧下方(脸颊/下巴),静态维持。
  /// 是举手的特例——区别在于手落在脸侧下方且长时间不动。
  restingCheek,

  /// 坐姿不良:躯干前倾/头部前倾/耸肩。
  poorPosture,
}

extension ActivityInfo on Activity {
  /// 中文标签(写入人物日志、调试展示)。
  String get label {
    switch (this) {
      case Activity.none:
        return '无活动';
      case Activity.drinking:
        return '喝水';
      case Activity.lookingAtPhone:
        return '看手机';
      case Activity.talking:
        return '交谈';
      case Activity.yawning:
        return '打哈欠';
      case Activity.handRaised:
        return '举手';
      case Activity.restingCheek:
        return '托腮';
      case Activity.poorPosture:
        return '坐姿不良';
    }
  }

  /// 英文 key(上报后端 / 机器可读)。
  String get apiKey {
    switch (this) {
      case Activity.none:
        return 'none';
      case Activity.drinking:
        return 'drinking';
      case Activity.lookingAtPhone:
        return 'looking_at_phone';
      case Activity.talking:
        return 'talking';
      case Activity.yawning:
        return 'yawning';
      case Activity.handRaised:
        return 'hand_raised';
      case Activity.restingCheek:
        return 'resting_cheek';
      case Activity.poorPosture:
        return 'poor_posture';
    }
  }
}

/// 一次活动状态转移(仅在确认转移的那一帧产生)。
class ActivityTransition {
  final Activity from;
  final Activity to;

  /// 离开 [from] 时它已持续的时长。
  final Duration previousDuration;

  const ActivityTransition({
    required this.from,
    required this.to,
    required this.previousDuration,
  });
}

/// 某一帧聚合后的活动快照。
class ActivitySnapshot {
  /// 当前稳定的活动状态。
  final Activity activity;

  /// 当前活动已持续的时长。
  final Duration duration;

  /// 本帧是否刚发生状态转移;未转移时为 null。
  final ActivityTransition? transition;

  const ActivitySnapshot({
    required this.activity,
    required this.duration,
    this.transition,
  });

  static final ActivitySnapshot initial = ActivitySnapshot(
    activity: Activity.none,
    duration: Duration.zero,
    transition: null,
  );
}

/// 单帧采样(活动状态机窗口元素)。
///
/// 从 [DetectionResult] 一次性提取活动判定所需的全部多模态信号:
/// 人脸(嘴部/视线下移)、物体(手持容器)、姿态(举手/坐姿)。
class _Sample {
  final DateTime t;
  final bool hasFace;

  /// 嘴部张开度(jawOpen,0..1)。
  final double mouthOpenness;

  /// 眼睛闭合度(eyeBlink,0..1,1=全闭)。区分打哈欠(常伴闭眼)与交谈。
  final double eyeBlink;

  /// 视线下移程度(gazeY,正=向下)。
  final double gazeDown;

  /// 手持物体是否为「饮水容器」(杯子/瓶子/酒杯)。
  final bool holdingDrinkware;

  /// 手持物体是否为「手机」。
  final bool holdingPhone;

  /// 饮水容器是否举到嘴部附近(鼻-腕距离<阈值)。需姿态可见。
  final bool drinkwareNearMouth;

  /// 是否单/双臂举起(手腕高于肩)。需姿态可见。
  final bool armRaised;

  /// 是否托腮/撑脸:手腕落在脸的侧下方(脸颊/下巴区域)。需姿态可见。
  final bool handOnCheek;

  /// 躯干前倾角(度,0=直立)。
  final double torsoTiltDeg;

  /// 是否耸肩(肩-耳垂直距离过小)。需姿态可见。
  final bool shoulderTensed;

  /// 姿态关键点是否可用(双肩可见)。
  final bool poseReliable;

  const _Sample({
    required this.t,
    required this.hasFace,
    required this.mouthOpenness,
    required this.eyeBlink,
    required this.gazeDown,
    required this.holdingDrinkware,
    required this.holdingPhone,
    required this.drinkwareNearMouth,
    required this.armRaised,
    required this.handOnCheek,
    required this.torsoTiltDeg,
    required this.shoulderTensed,
    required this.poseReliable,
  });
}

/// 日常活动时序聚合器。
///
/// 与 [BehaviorStateTracker] 同构(滑动窗口 + 迟滞确认 + dwell),但输入多模态
/// (人脸+物体+姿态),产出 [Activity] 活动状态。各活动互斥取最强(优先级见
/// [_computeDesired])。抗单帧误判:期望活动需连续保持 [_dwellFor] 才确认转移。
class ActivityStateTracker {
  ActivityStateTracker({
    this.window = const Duration(seconds: 5),
    this.minVisibility = 0.5,
    this.handNearMouthDist = 0.18,
    this.cupNearFaceDist = 0.12,
    this.poorPostureTiltDeg = 25.0,
    this.talkMouthLow = 0.15,
    this.talkMouthHigh = 0.8,
    this.yawnMouthThreshold = 0.7,
    this.yawnBlinkThreshold = 0.3,
    this.yawnPeakThreshold = 0.6,
    this.yawnWideRatio = 0.3,
    this.talkVarianceThreshold = 0.005,
    this.talkSpeechRatio = 0.35,
    this.talkPeakMax = 0.65,
    this.talkFluxRatio = 0.5,
  });

  /// 滑动窗口时长。说话判定需看嘴部节奏,窗口略短于注意力状态机。
  final Duration window;

  /// 关键点可见度门控阈值(举手/坐姿判定要求关节 visibility 高于此值)。
  final double minVisibility;

  /// 「手靠近嘴」判定:鼻到腕归一化距离小于此值视为举到嘴边。
  final double handNearMouthDist;

  /// 「杯子贴近脸」判定:杯子中心到脸部 box 的距离小于此值视为举到嘴边。
  /// 真实采样杯子中心落在脸 box 内(距离≈0),0.12 留余量容杯子略偏。
  final double cupNearFaceDist;

  /// 「坐姿不良」的躯干前倾角阈值(度)。
  final double poorPostureTiltDeg;

  /// 说话嘴部开合区间:0.15~0.8 视为在说话(张太大=哈欠,太小=没说话)。
  final double talkMouthLow;
  final double talkMouthHigh;

  /// 打哈欠嘴部张开阈值(均值口径,作为弱信号)。
  final double yawnMouthThreshold;
  /// 打哈欠常伴闭眼:eyeBlink 高于此值视为配合闭眼(强确认信号)。
  final double yawnBlinkThreshold;
  /// 打哈欠:窗口内嘴部张开的峰值(max)达到此值才算「张大过」。
  /// 哈欠峰值常 >0.6,说话峰值一般 <0.6,以此区分。
  final double yawnPeakThreshold;
  /// 打哈欠:窗口内「大张嘴(>yawnPeakThreshold)」帧占比超过此值。
  /// 哈欠张大持续较长,说话仅在峰值瞬间触及。
  final double yawnWideRatio;

  /// 说话判定:窗口内嘴部开合方差大于此值(有节奏起伏)。
  final double talkVarianceThreshold;
  /// 说话判定:嘴部落在 talkMouthLow~High 区间的帧占比大于此值。
  final double talkSpeechRatio;
  /// 说话判定:窗口内嘴部峰值(max)须低于此值,否则视为哈欠(张大)而非说话。
  final double talkPeakMax;
  /// 说话判定:相邻帧嘴部开合方向变化次数(开→合/合→开)归一化占比超过
  /// 此值,证明有「快速节奏」。哈欠是单峰缓慢张开,变化次数极少;说话每秒
  /// 多次开合,变化频繁。这是区分哈欠/交谈的最关键信号。
  final double talkFluxRatio;

  final List<_Sample> _samples = [];

  Activity _state = Activity.none;
  DateTime _stateSince = DateTime.now();
  Activity? _candidate;
  DateTime _candidateSince = DateTime.now();

  Activity get state => _state;

  /// 喂入一帧,返回聚合后的活动快照。
  ActivitySnapshot update(DetectionResult result, DateTime now) {
    _samples.add(_extract(result, now));

    // 淘汰过期采样。
    while (_samples.isNotEmpty && now.difference(_samples.first.t) > window) {
      _samples.removeAt(0);
    }

    final desired = _computeDesired(now);
    final transition = _applyHysteresis(desired, now);

    return ActivitySnapshot(
      activity: _state,
      duration: now.difference(_stateSince),
      transition: transition,
    );
  }

  /// 丢失上下文时重置(如相机重启)。
  void reset() {
    _samples.clear();
    _state = Activity.none;
    _stateSince = DateTime.now();
    _candidate = null;
  }

  /// 从 [DetectionResult] 提取一帧的多模态采样。
  _Sample _extract(DetectionResult result, DateTime now) {
    final face = result.face;
    final hasFace = result.faces.isNotEmpty && face != null;
    final mouth = face?.mouthOpenness ?? 0;
    // eyeBlink:打哈欠常伴闭眼,交谈不会,用于区分二者。
    final blink = face?.eyeBlink ?? 0;
    // gazeY 正=向下看;clamp 到 0..1 作为「下视程度」。
    final gazeDown = (face?.gazeY ?? 0).clamp(0.0, 1.0).toDouble();

    // 手持物体分类。
    final held = result.heldObject?.label;
    final holdingDrinkware =
        held == '杯子' || held == '瓶子' || held == '酒杯';
    final holdingPhone = held == '手机';

    // 姿态几何判定(需要 pose 可见)。
    final pose = result.poses.isNotEmpty ? result.poses.first : null;
    final lm = pose?.landmarks ?? const [];
    final vis = pose?.visibilities ?? const [];
    final poseOk = _isPoseReliable(lm, vis);

    bool armRaised = false;
    bool nearMouth = false;
    bool onCheek = false;
    bool shoulderTensed = false;
    double torsoTilt = 0;
    if (poseOk) {
      armRaised = _isArmRaised(lm, vis);
      torsoTilt = _torsoTiltDeg(lm);
      shoulderTensed = _isShoulderTensed(lm, vis);
    }
    // 喝水判定:用杯子 box 与脸部 box 的贴近程度(不依赖 pose——实测喝水时
    // pose 手腕距鼻常超阈值,但杯子本身紧贴脸下方)。杯子在脸附近即视为
    // "举到嘴边"。
    if (holdingDrinkware && hasFace) {
      nearMouth = _isCupNearFace(result.heldObject!, face.boundingBox);
    }
    // 托腮判定:用 HandOverlay + FaceOverlay(不依赖 pose——实测托腮时 pose
    // 常因手臂遮挡而检测不到,但手部检测稳定)。未持饮水容器时,若任一手
    // 的指尖贴近脸部 box 且手腕在脸下方,判定为托腮。
    if (!holdingDrinkware && hasFace && result.hands.isNotEmpty) {
      onCheek = _isHandOnCheekByHand(result.hands, face.boundingBox);
    }

    return _Sample(
      t: now,
      hasFace: hasFace,
      mouthOpenness: mouth,
      eyeBlink: blink,
      gazeDown: gazeDown,
      holdingDrinkware: holdingDrinkware,
      holdingPhone: holdingPhone,
      drinkwareNearMouth: nearMouth,
      armRaised: armRaised,
      handOnCheek: onCheek,
      torsoTiltDeg: torsoTilt,
      shoulderTensed: shoulderTensed,
      poseReliable: poseOk,
    );
  }

  /// 根据窗口统计计算出「本帧期望活动」(未经迟滞)。
  ///
  /// 优先级(取最强):喝水 > 看手机 > 交谈 > 打哈欠 > 托腮 > 举手 > 坐姿不良 > none。
  Activity _computeDesired(DateTime now) {
    if (_samples.isEmpty) return Activity.none;

    // —— 喝水:近期帧中持饮水容器并靠近嘴部的占比 ——
    final recent = _samples.where((s) => s.hasFace).toList();
    if (recent.isNotEmpty) {
      final drinkHits = recent.where((s) =>
          s.holdingDrinkware && s.drinkwareNearMouth).length;
      if (drinkHits / recent.length >= 0.4) return Activity.drinking;
    }

    // —— 看手机:持手机且视线下移的占比 ——
    if (recent.isNotEmpty) {
      final phoneHits = recent
          .where((s) => s.holdingPhone && s.gazeDown > 0.2)
          .length;
      if (phoneHits / recent.length >= 0.5) return Activity.lookingAtPhone;
    }

    // —— 打哈欠 vs 交谈:本质区别在于「嘴部节奏」与「峰值幅度」 ——
    // 打哈欠:单峰、张大(峰值高)、变化慢(低节奏)、常伴闭眼。
    // 交谈  :多峰、中等幅度、变化快(高频开合)、不伴闭眼。
    //
    // 关键区分信号:
    // 1. 峰值(max):哈欠峰值常 >0.6,说话峰值一般 <0.6。
    // 2. 节奏(flux):说话相邻帧频繁改变开合方向(每秒多次),哈欠单峰缓慢。
    // 3. 闭眼(eyeBlink):哈欠常伴闭眼,说话不会。
    // (旧逻辑只看均值+方差,哈欠闭合阶段会把均值拉低、把方差拉高,
    //  导致哈欠被误判成交谈。)
    if (recent.length >= 4) {
      final mouths = recent.map((s) => s.mouthOpenness).toList();
      final blinks = recent.map((s) => s.eyeBlink).toList();
      final maxMouth = mouths.reduce(math.max);
      final flux = _mouthFlux(mouths); // 开合方向变化归一化次数
      final blinkRatio =
          blinks.where((b) => b > yawnBlinkThreshold).length / blinks.length;

      // —— 打哈欠:峰值张大 + 大张嘴持续占一定比例 ——
      // 用「峰值 + 大张嘴占比」代替旧的「均值」,避免闭合阶段拉低均值。
      final wideRatio =
          mouths.where((m) => m > yawnPeakThreshold).length / mouths.length;
      final isYawning = maxMouth >= yawnPeakThreshold &&
          wideRatio >= yawnWideRatio &&
          // 节奏慢(低 flux)或伴闭眼至少满足其一,确认是哈欠而非说话。
          (flux < talkFluxRatio || blinkRatio > 0.3);
      if (isYawning) return Activity.yawning;

      // —— 交谈:快速开合节奏 + 峰值不超大 + 落在说话区间 ——
      // 峰值 < talkPeakMax(排除哈欠);flux 高(有节奏);区间占比达标。
      final speechRatio = mouths
          .where((m) => m > talkMouthLow && m < talkMouthHigh)
          .length / mouths.length;
      final varMouth = _variance(mouths);
      final isTalking = flux >= talkFluxRatio &&
          varMouth > talkVarianceThreshold &&
          speechRatio > talkSpeechRatio &&
          maxMouth < talkPeakMax;
      if (isTalking) return Activity.talking;
    }

    // —— 托腮:手腕持续落在脸的侧下方(举手特例) ——
    // 必须高占比(持续性):不是一重叠就判定,要求窗口内大多数帧都满足,
    // 再叠加 [_dwellFor](restingCheek) 的较长确认时长,共同保证「维持够久」。
    // 优先级高于普通举手(手举到脸侧 = 托腮,而非打招呼)。
    if (recent.isNotEmpty) {
      final cheekHits = recent.where((s) => s.handOnCheek).length;
      if (cheekHits / recent.length >= 0.6) return Activity.restingCheek;
    }

    // —— 举手:近期多数帧手臂举起 ——
    if (recent.isNotEmpty) {
      final armHits = recent.where((s) => s.armRaised).length;
      if (armHits / recent.length >= 0.6) return Activity.handRaised;
    }

    // —— 坐姿不良:躯干持续前倾或耸肩 ——
    if (recent.isNotEmpty) {
      final postureHits = recent.where((s) =>
          s.torsoTiltDeg > poorPostureTiltDeg || s.shoulderTensed).length;
      if (postureHits / recent.length >= 0.7) return Activity.poorPosture;
    }

    return Activity.none;
  }

  /// 迟滞状态机:期望活动需连续保持 [_dwellFor] 才确认转移。
  ActivityTransition? _applyHysteresis(Activity desired, DateTime now) {
    if (desired == _state) {
      _candidate = null;
      return null;
    }
    if (_candidate != desired) {
      _candidate = desired;
      _candidateSince = now;
      return null;
    }
    if (now.difference(_candidateSince) >= _dwellFor(desired)) {
      final from = _state;
      final prevDuration = now.difference(_stateSince);
      _state = desired;
      _stateSince = now;
      _candidate = null;
      return ActivityTransition(
        from: from,
        to: desired,
        previousDuration: prevDuration,
      );
    }
    return null;
  }

  /// 各活动的确认时长(dwell)。强信号活动确认快,坐姿/交谈确认慢(防误判)。
  Duration _dwellFor(Activity target) {
    switch (target) {
      case Activity.drinking:
        return const Duration(milliseconds: 1000);
      case Activity.lookingAtPhone:
        return const Duration(milliseconds: 1500);
      case Activity.talking:
        return const Duration(seconds: 2);
      case Activity.yawning:
        return const Duration(milliseconds: 1500);
      case Activity.handRaised:
        return const Duration(milliseconds: 600);
      case Activity.restingCheek:
        // 托腮是静态维持动作,需更长时间确认,避免举手路过脸侧被误判。
        return const Duration(milliseconds: 2500);
      case Activity.poorPosture:
        return const Duration(seconds: 3);
      case Activity.none:
        return const Duration(milliseconds: 1500);
    }
  }

  // ============ 姿态几何判定(针对 PoseOverlay,归一化坐标) ============

  /// 姿态是否可靠:双肩均可见(visibility>minVisibility)。
  /// 关键点索引:leftShoulder=11, rightShoulder=12(见 PoseLandmarkIndex)。
  bool _isPoseReliable(List<Offset> lm, List<double> vis) {
    if (lm.length < 13) return false;
    double visAt(int i) => i < vis.length ? vis[i] : 0;
    return visAt(11) > minVisibility && visAt(12) > minVisibility;
  }

  /// 是否举手:任一手腕 y < 肩 y(屏幕坐标系 y 向下,手腕在上=举起)。
  /// leftShoulder=11, rightShoulder=12, leftWrist=15, rightWrist=16。
  bool _isArmRaised(List<Offset> lm, List<double> vis) {
    if (lm.length < 17) return false;
    double visAt(int i) => i < vis.length ? vis[i] : 0;
    bool check(int shoulderIdx, int wristIdx) {
      if (visAt(wristIdx) <= minVisibility) return false;
      final wrist = lm[wristIdx];
      if (wrist == Offset.zero) return false;
      return wrist.dy < lm[shoulderIdx].dy - 0.02;
    }
    return check(11, 15) || check(12, 16);
  }

  /// 杯子是否贴近脸部(举到嘴边):用杯子 box 与脸部 box 的距离判定。
  ///
  /// 实测喝水时 pose 手腕距鼻常超 0.18 阈值(手腕在杯柄,距嘴较远),但
  /// 杯子本身紧贴脸下方(两 box 重叠或间距≈0)。故改用杯子 box 贴近脸 box
  /// 判定,比手腕近鼻更直接可靠,且不依赖 pose。
  /// 真实采样:杯子 box 与脸 box 间距≈0(IoU≈0.4,大面积重叠)。
  bool _isCupNearFace(ObjectOverlay cup, Rect faceBox) {
    final d = _distToBox(
      Offset(cup.boundingBox.center.dx, cup.boundingBox.center.dy),
      faceBox,
    );
    // 杯子中心距脸 box < 0.12(杯子中心在脸下方略偏,贴近嘴部)。
    return d < cupNearFaceDist;
  }

  /// 是否托腮/撑脸:基于 HandOverlay(手部 21 点) + 脸部 box 判定。
  ///
  /// 实测托腮时人体姿态(Pose)常因手臂遮挡而检测不到,但手部检测稳定,
  /// 故改用手部关键点判定。HandLandmarkType 顺序:0=手腕,8=食指尖,
  /// 12=中指尖。
  ///
  /// 托腮几何特征(由真实采样数据校准):
  ///  1. 指尖(8/12)贴近脸部 box:指尖到 face box 的最短距离 < 阈值;
  ///  2. 指尖垂直位置在脸的中下部(脸颊/下巴),而非额头上方;
  ///  3. 手腕(0)在脸 box 下方或侧下方(撑住脸的姿态),而非脸上方(挥手)。
  /// 与喝水区别:未持饮水容器(调用方已保证)。
  bool _isHandOnCheekByHand(List<HandOverlay> hands, Rect faceBox) {
    for (final h in hands) {
      if (h.landmarks.length < 13) continue;
      final wrist = h.landmarks[0];
      final indexTip = h.landmarks[8]; // 食指尖
      final middleTip = h.landmarks[12]; // 中指尖
      if (wrist == Offset.zero || indexTip == Offset.zero) continue;
      // 指尖到 face box 的最短距离(归一化)。
      final tipDist = math.min(
        _distToBox(indexTip, faceBox),
        _distToBox(middleTip, faceBox),
      );
      // 指尖贴近脸(<0.06,实测采样约 0.01~0.05)。
      if (tipDist >= 0.06) continue;
      // 指尖垂直位置在脸的中下部:y 在 faceBox 上 1/3 到下边界之间。
      final faceUpperThird = faceBox.top + faceBox.height / 3;
      final tipY = (indexTip.dy + middleTip.dy) / 2;
      if (tipY < faceUpperThird) continue; // 指尖在额头区域,不算托腮
      // 手腕在脸下方或同高(撑脸姿态):手腕 y >= 脸上 1/4。
      if (wrist.dy < faceBox.top + faceBox.height / 4) continue;
      return true;
    }
    return false;
  }

  /// 点到矩形(归一化)的最短距离。点在矩形内则距离为 0。
  double _distToBox(Offset p, Rect box) {
    final dx = p.dx < box.left
        ? box.left - p.dx
        : (p.dx > box.right ? p.dx - box.right : 0.0);
    final dy = p.dy < box.top
        ? box.top - p.dy
        : (p.dy > box.bottom ? p.dy - box.bottom : 0.0);
    return math.sqrt(dx * dx + dy * dy);
  }

  /// 躯干前倾角(度):双肩中点与双髋中点的连线,相对竖直方向的倾角。
  /// 0=躯干竖直;值越大越前倾/后仰。leftHip=23, rightHip=24。
  double _torsoTiltDeg(List<Offset> lm) {
    if (lm.length < 25) return 0;
    final shoulderMid = Offset(
      (lm[11].dx + lm[12].dx) / 2,
      (lm[11].dy + lm[12].dy) / 2,
    );
    final hipMid = Offset(
      (lm[23].dx + lm[24].dx) / 2,
      (lm[23].dy + lm[24].dy) / 2,
    );
    final dx = shoulderMid.dx - hipMid.dx;
    final dy = shoulderMid.dy - hipMid.dy; // 正常坐姿:肩在髋上方,dy<0
    // 前倾时肩向髋前方移动。倾角 = atan2(|dx|, |dy|)。
    if (dy.abs() < 1e-3) return 90;
    return math.atan2(dx.abs(), dy.abs()) * 180 / math.pi;
  }

  /// 是否耸肩:肩-耳垂直距离过小(肩抬起贴近耳朵)。
  /// leftEar=7, leftShoulder=11。正常肩在耳下方(距离大);耸肩时距离变小。
  bool _isShoulderTensed(List<Offset> lm, List<double> vis) {
    if (lm.length < 12) return false;
    double visAt(int i) => i < vis.length ? vis[i] : 0;
    double earShoulderGap(int earIdx, int shoulderIdx) {
      if (visAt(earIdx) <= minVisibility || visAt(shoulderIdx) <= minVisibility) {
        return double.infinity;
      }
      final ear = lm[earIdx], shoulder = lm[shoulderIdx];
      if (ear == Offset.zero || shoulder == Offset.zero) return double.infinity;
      return (ear - shoulder).distance;
    }
    final gap = math.min(
      earShoulderGap(7, 11),
      earShoulderGap(8, 12),
    );
    // 经验阈值:正常肩-耳归一化距离约 0.10~0.18;耸肩时<0.08。
    return gap < 0.08;
  }

  // ============ 统计辅助 ============

  static double _mean(List<double> xs) {
    if (xs.isEmpty) return 0;
    var sum = 0.0;
    for (final x in xs) {
      sum += x;
    }
    return sum / xs.length;
  }

  static double _variance(List<double> xs) {
    if (xs.length < 2) return 0;
    final m = _mean(xs);
    var acc = 0.0;
    for (final x in xs) {
      final d = x - m;
      acc += d * d;
    }
    return acc / xs.length;
  }

  /// 嘴部「开合方向变化」归一化次数(节奏指标),用于区分哈欠与交谈。
  ///
  /// 统计相邻帧嘴部张度变化方向的切换次数(张开↔闭合),除以帧数归一化到
  /// 0..1。说话时嘴部高频开合,频繁改变方向 → flux 高(如 0.5+);打哈欠是
  /// 单峰缓慢张开再闭合,方向极少改变 → flux 低(如 <0.2)。
  ///
  /// 用一个微小阈值 [eps] 忽略噪声级抖动(只有越过 eps 的变化才算方向改变)。
  static double _mouthFlux(List<double> xs, {double eps = 0.03}) {
    if (xs.length < 3) return 0;
    int dir = 0; // 上一变化方向:+1 张开,-1 闭合,0 未定
    int changes = 0;
    for (var i = 1; i < xs.length; i++) {
      final delta = xs[i] - xs[i - 1];
      final cur = delta > eps ? 1 : (delta < -eps ? -1 : 0);
      if (cur != 0 && dir != 0 && cur != dir) {
        changes++;
      }
      if (cur != 0) dir = cur;
    }
    return changes / (xs.length - 1);
  }
}
