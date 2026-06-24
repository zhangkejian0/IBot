import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:ultralytics_yolo/ultralytics_yolo.dart';

import '../models/detection.dart';

/// 物体识别引擎：封装官方 Ultralytics YOLO 插件（YOLO11）。
///
/// **为什么不卡顿**：YOLO 推理在原生线程（平台通道）执行，不占用 Dart 主
/// isolate；调用方（[AppController]）以「后台节流 + JPEG 编码放 Isolate」的方式
/// 驱动它，绝不阻塞人脸/注视的高频帧循环。
///
/// **识别能力**：YOLO11 检测 + COCO 80 类，含 cup / bottle / cell phone / book /
/// laptop / remote / banana 等常见手持物体，定位与准确率均优于「整图分类器套裁剪
/// 框」的旧方案。标签经 [_cocoZh] 映射为中文，未命中返回英文原标签。
///
/// **坐标空间对齐**：[predictJpeg] 接收的是「摆正后图像」编码的 JPEG，返回的
/// 包围盒归一化到该图像的 0..1 空间，与人脸/手势覆盖层共享同一坐标基准，
/// 便于做「手持物体」的空间叠加判断。
///
/// **模型**：用官方 `yolo26n`（COCO 80 类检测）。插件 0.6.5 的原生后处理是按
/// **YOLO26 系列**设计的,与该架构最匹配。注意 ultralytics_yolo 0.6.x 已把
/// `yolo11n` 移出自动下载清单,且 v0.2.0 的旧 `yolo11n` 导出与 0.6.5 的解码器
/// 不匹配 —— 实测换 `yolo11n` 反而劣化,故仍用 `yolo26n`。
///
/// 为完全离线(陪伴机器人不依赖运行时联网),已把 `yolo26n_int8.tflite` 内置到
/// assets/models/:插件解析官方 ID `yolo26n` 时会优先从该资产拷贝到内部存储使用,
/// 找不到才回退联网下载。
///
/// **关于量化与精度**:内置的是 int8 量化模型(体积小、速度快)。int8 对相近小物体
/// (杯子/书/手机)区分略弱,若要更高精度可换**同架构的非量化版** yolo26n:
///   `yolo export model=yolo26n.pt format=tflite half=True`  (生成 fp16)
/// 导出后放到 `assets/models/yolo26n_float.tflite`,并把 [_modelCandidates] 首项
/// 改为该路径即可(int8 作为回退保留)。**切勿换成 yolo11n**:架构不匹配会更差。
///
/// **加载策略([_modelCandidates])**:按优先级依次尝试,第一个成功的即采用,
/// 任一失败(资产缺失/原生加载失败)自动回退,确保物体识别始终可用。
class ObjectEngine {
  /// 模型候选(按优先级)。默认用官方 `yolo26n`(内置 int8 资产,离线可用)。
  /// 想用非量化 yolo26n:导出 fp16 放到 assets/models/yolo26n_float.tflite,
  /// 再把该路径加到本列表最前面即可。
  static const List<String> _modelCandidates = [
    'yolo26n',
  ];

  /// 单帧最多返回的物体数（按面积降序截断）。
  final int maxObjects;

  /// 置信度阈值（低于此值的检测被过滤）。
  final double confidenceThreshold;

  /// NMS 的 IoU 阈值。
  final double iouThreshold;

  YOLO? _yolo;
  bool _initialized = false;
  String? _statusMessage;
  int _logCounter = 0;

  ObjectEngine({
    this.maxObjects = 10,
    this.confidenceThreshold = 0.35,
    this.iouThreshold = 0.45,
  });

  bool get isInitialized => _initialized;
  String? get statusMessage => _statusMessage;

  Future<void> initialize() async {
    if (_initialized) return;
    // 按优先级尝试候选模型:float 优先,失败(资产缺失/原生加载失败)逐级回退,
    // 保证物体识别始终可用。记录最后一次错误用于诊断。
    Object? lastError;
    for (final path in _modelCandidates) {
      try {
        // useGpu:false —— 陪伴机器人硬件多样,LiteRT GPU delegate 在部分设备会崩,
        // 低频检测下 CPU 足够稳妥(插件文档亦建议为稳定性关闭 GPU)。
        final yolo = YOLO(
          modelPath: path,
          task: YOLOTask.detect,
          useGpu: false,
        );
        final ok = await yolo.loadModel();
        if (!ok) {
          debugPrint('[ObjectEngine] loadModel returned false for $path');
          continue;
        }
        _yolo = yolo;
        _initialized = true;
        final quant = path.contains('int8') ? 'int8' : 'float';
        _statusMessage = '物体识别已加载 ${_shortName(path)}（$quant）';
        debugPrint('[ObjectEngine] loaded $path ($quant)');
        return;
      } catch (e) {
        lastError = e;
        debugPrint('[ObjectEngine] load $path failed: $e');
      }
    }
    _statusMessage = '物体识别模型加载失败'
        '（检查 assets/models/ 下的 yolo11n_float.tflite 是否打包）'
        '${lastError != null ? '：$lastError' : ''}';
    debugPrint('[ObjectEngine] all model candidates failed');
  }

  /// 从模型路径取一个简短可读名(去目录与扩展名),用于状态展示。
  static String _shortName(String path) =>
      path.split('/').last.replaceAll('.tflite', '');

  /// 对一张「摆正后图像」编码的 JPEG 做物体检测。
  ///
  /// [w]/[h] 为该图像的像素尺寸，用于在原生未提供归一化框时兜底归一化。
  /// 返回归一化（0..1）的物体覆盖层列表，按面积降序、至多 [maxObjects] 个。
  Future<List<ObjectOverlay>> predictJpeg(Uint8List jpg, int w, int h) async {
    final yolo = _yolo;
    if (yolo == null || !_initialized || jpg.isEmpty) return const [];

    Map<String, dynamic> res;
    try {
      res = await yolo.predict(
        jpg,
        confidenceThreshold: confidenceThreshold,
        iouThreshold: iouThreshold,
      );
    } catch (e) {
      debugPrint('[ObjectEngine] predict error: $e');
      return const [];
    }

    final dets = res['detections'];
    if (dets is! List) return const [];

    final out = <ObjectOverlay>[];
    for (final d in dets) {
      if (d is! Map) continue;
      final conf = (d['confidence'] as num?)?.toDouble() ?? 0;
      final rect = _readRect(d, w, h);
      if (rect == null || rect.width <= 0 || rect.height <= 0) continue;
      final cls = (d['className'] as String?) ?? '';
      // 跳过「人」类:人的检测/身份由专门的人脸引擎负责,YOLO 的 person 框
      // 既冗余又会被「手持」空间判定误命中,拼出「手里拿着人」这类离谱描述。
      if (cls.trim().toLowerCase() == 'person') continue;
      out.add(ObjectOverlay(
        boundingBox: rect,
        label: _displayLabel(cls),
        confidence: conf,
      ));
    }

    out.sort((a, b) => (b.boundingBox.width * b.boundingBox.height)
        .compareTo(a.boundingBox.width * a.boundingBox.height));

    if (++_logCounter <= 8 || (_logCounter % 20) == 0) {
      final dump = out
          .take(maxObjects)
          .map((o) => '${o.label}:${o.confidence.toStringAsFixed(2)}')
          .join(', ');
      debugPrint('[ObjectEngine] predict ${w}x$h dets=${dets.length} -> [$dump]');
    }

    return out.take(maxObjects).toList(growable: false);
  }

  /// 优先用原生归一化框；无效时用像素框 / (w,h) 兜底。
  Rect? _readRect(Map d, int w, int h) {
    final nb = d['normalizedBox'];
    if (nb is Map) {
      final l = (nb['left'] as num?)?.toDouble();
      final t = (nb['top'] as num?)?.toDouble();
      final r = (nb['right'] as num?)?.toDouble();
      final b = (nb['bottom'] as num?)?.toDouble();
      if (l != null && t != null && r != null && b != null && r > l && b > t) {
        return Rect.fromLTRB(
          l.clamp(0.0, 1.0),
          t.clamp(0.0, 1.0),
          r.clamp(0.0, 1.0),
          b.clamp(0.0, 1.0),
        );
      }
    }
    final bb = d['boundingBox'];
    if (bb is Map && w > 0 && h > 0) {
      final l = ((bb['left'] as num?)?.toDouble() ?? 0) / w;
      final t = ((bb['top'] as num?)?.toDouble() ?? 0) / h;
      final r = ((bb['right'] as num?)?.toDouble() ?? 0) / w;
      final b = ((bb['bottom'] as num?)?.toDouble() ?? 0) / h;
      if (r > l && b > t) {
        return Rect.fromLTRB(
          l.clamp(0.0, 1.0),
          t.clamp(0.0, 1.0),
          r.clamp(0.0, 1.0),
          b.clamp(0.0, 1.0),
        );
      }
    }
    return null;
  }

  String _displayLabel(String raw) {
    final key = raw.trim().toLowerCase();
    return _cocoZh[key] ?? raw;
  }

  /// COCO 80 类英文 → 中文。键为 YOLO 输出的英文类名（小写）。
  static const Map<String, String> _cocoZh = {
    'person': '人',
    'bicycle': '自行车',
    'car': '汽车',
    'motorcycle': '摩托车',
    'airplane': '飞机',
    'bus': '公交车',
    'train': '火车',
    'truck': '卡车',
    'boat': '船',
    'traffic light': '红绿灯',
    'fire hydrant': '消防栓',
    'stop sign': '停车标志',
    'parking meter': '停车计时器',
    'bench': '长椅',
    'bird': '鸟',
    'cat': '猫',
    'dog': '狗',
    'horse': '马',
    'sheep': '羊',
    'cow': '牛',
    'elephant': '大象',
    'bear': '熊',
    'zebra': '斑马',
    'giraffe': '长颈鹿',
    'backpack': '背包',
    'umbrella': '伞',
    'handbag': '手提包',
    'tie': '领带',
    'suitcase': '行李箱',
    'frisbee': '飞盘',
    'skis': '滑雪板',
    'snowboard': '单板滑雪',
    'sports ball': '球',
    'kite': '风筝',
    'baseball bat': '棒球棒',
    'baseball glove': '棒球手套',
    'skateboard': '滑板',
    'surfboard': '冲浪板',
    'tennis racket': '网球拍',
    'bottle': '瓶子',
    'wine glass': '酒杯',
    'cup': '杯子',
    'fork': '叉子',
    'knife': '刀',
    'spoon': '勺子',
    'bowl': '碗',
    'banana': '香蕉',
    'apple': '苹果',
    'sandwich': '三明治',
    'orange': '橙子',
    'broccoli': '西兰花',
    'carrot': '胡萝卜',
    'hot dog': '热狗',
    'pizza': '披萨',
    'donut': '甜甜圈',
    'cake': '蛋糕',
    'chair': '椅子',
    'couch': '沙发',
    'potted plant': '盆栽',
    'bed': '床',
    'dining table': '餐桌',
    'toilet': '马桶',
    'tv': '电视',
    'laptop': '笔记本电脑',
    'mouse': '鼠标',
    'remote': '遥控器',
    'keyboard': '键盘',
    'cell phone': '手机',
    'microwave': '微波炉',
    'oven': '烤箱',
    'toaster': '烤面包机',
    'sink': '水槽',
    'refrigerator': '冰箱',
    'book': '书',
    'clock': '时钟',
    'vase': '花瓶',
    'scissors': '剪刀',
    'teddy bear': '玩偶熊',
    'hair drier': '吹风机',
    'toothbrush': '牙刷',
  };

  Future<void> dispose() async {
    await _yolo?.dispose();
    _yolo = null;
    _initialized = false;
  }
}
