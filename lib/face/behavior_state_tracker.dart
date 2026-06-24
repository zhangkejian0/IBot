import 'dart:math' as math;

import '../models/detection.dart';
import '../models/expression.dart';

/// 时序行为状态。把高频单帧观测（表情/注视/人脸位置）聚合成稳定的语义状态，
/// 抵消单帧误判带来的交互抖动。
enum BehaviorState {
  /// 离开：连续一段时间未检测到人脸。
  absent,

  /// 在场：检测到人脸，但未达到专注/走神/困倦的判定条件。
  present,

  /// 专注：人脸位置长时间稳定 + 注视方向长时间稳定。
  focused,

  /// 走神：注视方向频繁游移、东张西望。
  distracted,

  /// 困倦：眼睛长时间近乎闭合。
  drowsy,
}

extension BehaviorStateInfo on BehaviorState {
  /// 中文标签（写入人物日志、调试展示）。
  String get label {
    switch (this) {
      case BehaviorState.absent:
        return '离开';
      case BehaviorState.present:
        return '在场';
      case BehaviorState.focused:
        return '专注';
      case BehaviorState.distracted:
        return '走神';
      case BehaviorState.drowsy:
        return '困倦';
    }
  }

  /// 英文 key（上报后端 / 机器可读）。
  String get apiKey {
    switch (this) {
      case BehaviorState.absent:
        return 'absent';
      case BehaviorState.present:
        return 'present';
      case BehaviorState.focused:
        return 'focused';
      case BehaviorState.distracted:
        return 'distracted';
      case BehaviorState.drowsy:
        return 'drowsy';
    }
  }
}

/// 一次状态转移（仅在确认转移的那一帧产生）。
class BehaviorTransition {
  final BehaviorState from;
  final BehaviorState to;

  /// 离开 [from] 时，它已持续的时长。
  final Duration previousDuration;

  const BehaviorTransition({
    required this.from,
    required this.to,
    required this.previousDuration,
  });
}

/// 某一帧聚合后的行为快照，用于驱动下游交互/日志。
class BehaviorSnapshot {
  /// 当前稳定的行为状态。
  final BehaviorState state;

  /// 当前状态已持续的时长。
  final Duration duration;

  /// 窗口内置信度加权投票得到的主导表情（比单帧表情稳定）。
  final Expression dominantExpression;

  /// 主导表情在窗口内的占比（0..1）。
  final double dominantExpressionRatio;

  /// 本帧是否刚发生状态转移；未转移时为 null。
  final BehaviorTransition? transition;

  const BehaviorSnapshot({
    required this.state,
    required this.duration,
    required this.dominantExpression,
    required this.dominantExpressionRatio,
    this.transition,
  });

  static final BehaviorSnapshot initial = BehaviorSnapshot(
    state: BehaviorState.absent,
    duration: Duration.zero,
    dominantExpression: Expression.neutral,
    dominantExpressionRatio: 1.0,
    transition: null,
  );
}

/// 单帧采样（环形窗口元素）。
class _Sample {
  final DateTime t;
  final bool hasFace;
  final double cx; // 主脸框中心 X（0..1）
  final double cy; // 主脸框中心 Y（0..1）
  final double size; // 主脸框宽（归一化，用于尺度无关的运动度量）
  final double gazeX; // 注视方向（-1..1，原始值）
  final double gazeY;
  final double blink; // 眼睛闭合（0..1）
  final Expression expr;
  final double exprScore;

  const _Sample({
    required this.t,
    required this.hasFace,
    required this.cx,
    required this.cy,
    required this.size,
    required this.gazeX,
    required this.gazeY,
    required this.blink,
    required this.expr,
    required this.exprScore,
  });
}

/// 时序行为聚合器。
///
/// 设计要点（抗单帧误判）：
/// 1. 滑动时间窗口：保留最近 [window] 时长的采样，统计而非看单帧。
/// 2. 尺度无关运动：人脸框中心位移用人脸框宽归一化，远近一致。
/// 3. 迟滞 + 最小持续：进入「专注」需条件连续保持数秒确认，离开更快，
///    单帧抖动/短暂转头不立即切换状态。
/// 4. 表情窗口投票：按置信度加权取主导表情，避免一帧误判触发反应。
class BehaviorStateTracker {
  BehaviorStateTracker({
    this.window = const Duration(seconds: 8),
    this.recentPresenceWindow = const Duration(milliseconds: 1500),
    this.movementThreshold = 0.15,
    this.gazeStableStd = 0.12,
    this.zoneChangeRatePerSec = 0.6,
    this.blinkClosedThreshold = 0.55,
    this.minJudgeSpan = const Duration(milliseconds: 2500),
  });

  /// 滑动窗口时长。
  final Duration window;

  /// 判定「离开」用的近端窗口：仅看最近这段时间的在场率。
  final Duration recentPresenceWindow;

  /// 运动稳定阈值：窗口内中心位置标准差 / 平均人脸宽 小于此值视为「基本没动」。
  final double movementThreshold;

  /// 注视稳定阈值：窗口内注视方向标准差小于此值视为「长时间盯一个方向」。
  final double gazeStableStd;

  /// 走神判定：窗口内注视九宫格切换频率（次/秒）超过此值视为「东张西望」。
  final double zoneChangeRatePerSec;

  /// 困倦判定：窗口内平均眼睛闭合度超过此值视为「近乎闭眼」。
  final double blinkClosedThreshold;

  /// 进入专注/走神前，窗口至少要覆盖这么长时间，避免刚出现人脸就妄下结论。
  final Duration minJudgeSpan;

  final List<_Sample> _samples = [];

  BehaviorState _state = BehaviorState.absent;
  DateTime _stateSince = DateTime.now();

  BehaviorState? _candidate;
  DateTime _candidateSince = DateTime.now();

  BehaviorState get state => _state;

  /// 喂入一帧识别结果，返回聚合后的行为快照。
  BehaviorSnapshot update(DetectionResult result, DateTime now) {
    final face = result.face;
    final hasFace = result.faces.isNotEmpty && face != null;
    _samples.add(_Sample(
      t: now,
      hasFace: hasFace,
      cx: face?.boundingBox.center.dx ?? 0,
      cy: face?.boundingBox.center.dy ?? 0,
      size: face != null ? math.max(face.boundingBox.width, 1e-3) : 1e-3,
      gazeX: face?.gazeX ?? 0,
      gazeY: face?.gazeY ?? 0,
      blink: face?.eyeBlink ?? 0,
      expr: face?.expression.expression ?? Expression.neutral,
      exprScore: face?.expression.score ?? 0,
    ));

    // 淘汰过期采样。
    while (_samples.isNotEmpty &&
        now.difference(_samples.first.t) > window) {
      _samples.removeAt(0);
    }

    final desired = _computeDesired(now);
    final transition = _applyHysteresis(desired, now);

    final dominant = _dominantExpression();

    return BehaviorSnapshot(
      state: _state,
      duration: now.difference(_stateSince),
      dominantExpression: dominant.$1,
      dominantExpressionRatio: dominant.$2,
      transition: transition,
    );
  }

  /// 丢失上下文时重置（如相机重启）。
  void reset() {
    _samples.clear();
    _state = BehaviorState.absent;
    _stateSince = DateTime.now();
    _candidate = null;
  }

  /// 根据窗口统计算出「本帧期望状态」（未经迟滞）。
  BehaviorState _computeDesired(DateTime now) {
    if (_samples.isEmpty) return BehaviorState.absent;

    // 近端在场率：判定「离开」只看最近一小段，反应更快。
    final recent = _samples
        .where((s) => now.difference(s.t) <= recentPresenceWindow)
        .toList();
    if (recent.isNotEmpty) {
      final ratio =
          recent.where((s) => s.hasFace).length / recent.length;
      if (ratio < 0.3) return BehaviorState.absent;
    }

    final faces = _samples.where((s) => s.hasFace).toList();
    if (faces.length < 3) return BehaviorState.present;

    final span = now.difference(faces.first.t);

    // 困倦：平均闭眼度高（不要求长窗口，闭眼本身已足够明确）。
    final meanBlink = _mean(faces.map((s) => s.blink));
    if (meanBlink > blinkClosedThreshold) return BehaviorState.drowsy;

    // 时间跨度不足时不轻易判定专注/走神，先归为在场。
    if (span < minJudgeSpan) return BehaviorState.present;

    // 运动稳定度：中心位移标准差 / 平均人脸宽（尺度无关）。
    final meanSize = math.max(_mean(faces.map((s) => s.size)), 1e-3);
    final cxStd = _std(faces.map((s) => s.cx));
    final cyStd = _std(faces.map((s) => s.cy));
    final moveMetric = math.sqrt(cxStd * cxStd + cyStd * cyStd) / meanSize;
    final moveStable = moveMetric < movementThreshold;

    // 注视稳定度：注视方向标准差。
    final gazeStd = math.sqrt(
      math.pow(_std(faces.map((s) => s.gazeX)), 2).toDouble() +
          math.pow(_std(faces.map((s) => s.gazeY)), 2).toDouble(),
    );
    final gazeStable = gazeStd < gazeStableStd;

    // 注视九宫格切换频率（东张西望）。
    final zoneChanges = _zoneChanges(faces);
    final seconds = span.inMilliseconds / 1000.0;
    final zoneRate = seconds > 0 ? zoneChanges / seconds : 0;

    if (moveStable && gazeStable) return BehaviorState.focused;
    if (gazeStd > gazeStableStd * 1.8 || zoneRate > zoneChangeRatePerSec) {
      return BehaviorState.distracted;
    }
    return BehaviorState.present;
  }

  /// 迟滞状态机：期望状态需连续保持 [_dwellFor] 才确认转移。
  BehaviorTransition? _applyHysteresis(BehaviorState desired, DateTime now) {
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
      return BehaviorTransition(
        from: from,
        to: desired,
        previousDuration: prevDuration,
      );
    }
    return null;
  }

  /// 各目标状态的确认时长：进入「专注」慢（防误判），离开/回退快（迟滞）。
  Duration _dwellFor(BehaviorState target) {
    switch (target) {
      case BehaviorState.focused:
        return const Duration(seconds: 3);
      case BehaviorState.distracted:
        return const Duration(milliseconds: 2500);
      case BehaviorState.drowsy:
        return const Duration(seconds: 2);
      case BehaviorState.absent:
        return const Duration(milliseconds: 1500);
      case BehaviorState.present:
        return const Duration(milliseconds: 1500);
    }
  }

  /// 窗口内表情按置信度加权投票，返回（主导表情, 占比）。
  (Expression, double) _dominantExpression() {
    final faces = _samples.where((s) => s.hasFace).toList();
    if (faces.isEmpty) return (Expression.neutral, 1.0);
    final weights = <Expression, double>{};
    var total = 0.0;
    for (final s in faces) {
      // 至少给一点底权重，避免低置信表情被完全忽略。
      final w = 0.2 + s.exprScore;
      weights[s.expr] = (weights[s.expr] ?? 0) + w;
      total += w;
    }
    Expression best = Expression.neutral;
    double bestW = -1;
    weights.forEach((e, w) {
      if (w > bestW) {
        bestW = w;
        best = e;
      }
    });
    return (best, total > 0 ? bestW / total : 1.0);
  }

  /// 把人脸中心量化到 3×3 九宫格后，统计窗口内的切换次数。
  int _zoneChanges(List<_Sample> faces) {
    int changes = 0;
    int? prev;
    for (final s in faces) {
      final col = (s.cx.clamp(0.0, 1.0) * 3).floor().clamp(0, 2);
      final row = (s.cy.clamp(0.0, 1.0) * 3).floor().clamp(0, 2);
      final zone = row * 3 + col;
      if (prev != null && zone != prev) changes++;
      prev = zone;
    }
    return changes;
  }

  static double _mean(Iterable<double> xs) {
    var sum = 0.0;
    var n = 0;
    for (final x in xs) {
      sum += x;
      n++;
    }
    return n == 0 ? 0 : sum / n;
  }

  static double _std(Iterable<double> xs) {
    final list = xs.toList();
    if (list.length < 2) return 0;
    final m = _mean(list);
    var acc = 0.0;
    for (final x in list) {
      final d = x - m;
      acc += d * d;
    }
    return math.sqrt(acc / list.length);
  }
}
