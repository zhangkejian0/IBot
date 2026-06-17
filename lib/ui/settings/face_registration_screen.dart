import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../models/person.dart';
import '../../theme/app_theme.dart';

/// 人脸身份录入页（横屏左右布局，Face-ID 风格）。
///
/// 样式与布局参照 X-Bot_temp 的 face_registration_screen；底层识别引擎
/// 完全复用 XBot 现有的 FaceRecognitionService（MobileFaceNet TFLite），
/// 不引入第三方 face_recognition_auth 包。
///
/// 左侧：Face-ID 风格圆形摄像头取景框（直接预览主相机流）+ 状态刻度环。
/// 右侧：姓名 + 与主人的关系 输入 + 「开始录入」按钮；点击后自动采集 5 帧样本。
class FaceRegistrationScreen extends StatefulWidget {
  const FaceRegistrationScreen({super.key});

  @override
  State<FaceRegistrationScreen> createState() =>
      _FaceRegistrationScreenState();
}

/// 录入流程的自定义状态机（XBot 没有 fra 的 FaceAuthState，自建一套）。
enum _EnrollState { idle, detecting, collecting, success, failed, duplicate }

class _FaceRegistrationScreenState extends State<FaceRegistrationScreen>
    with SingleTickerProviderStateMixin {
  // ===== iOS 调色板（与 X-Bot_temp 保持一致）=====
  static const _bg = Color(0xFF000000);
  static const _card = Color(0xFF1C1C1E);
  static const _cardRow = Color(0xFF2C2C2E);
  static const _separator = Color(0xFF38383A);
  static const _label = Color(0xFF8E8E93);
  static const _blue = Color(0xFF0A84FF);
  static const _green = Color(0xFF30D158);
  static const _red = Color(0xFFFF453A);

  /// 单次录入采集的样本数。
  static const int _requiredSamples = 5;

  final _nameController = TextEditingController();
  FamilyRelation _relation = FamilyRelation.owner;

  _EnrollState _state = _EnrollState.idle;
  bool _running = false;
  int _collected = 0;

  late final AnimationController _scanController;

  @override
  void initState() {
    super.initState();
    _scanController = AnimationController(
      duration: const Duration(seconds: 2),
      vsync: this,
    );
  }

  @override
  void dispose() {
    _scanController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  AppController get _controller => AppScope.read(context);

  /// 启动录入：连续采集 5 帧样本，逐帧判重，全部成功后保存。
  Future<void> _start() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) {
      _toast('请输入名字或昵称');
      return;
    }
    final controller = _controller;
    if (!controller.faceRecognition.isAvailable) {
      _toast('未加载身份识别模型，无法录入');
      return;
    }

    setState(() {
      _running = true;
      _state = _EnrollState.detecting;
      _collected = 0;
    });
    _scanController.repeat();

    final samples = <_Sample>[];
    try {
      // 在总时限内尽量采集 _requiredSamples 张样本。
      //
      // 关键改动：单帧漏检（如缓慢转头到大角度、运动模糊导致检测器丢脸）
      // 不再立刻中止整个录入流程，而是在截止时间内继续重试。只有在整个窗口
      // 内一张都没采到时才判定失败 —— 避免「明明人脸清晰却报未捕获」。
      final deadline = DateTime.now().add(const Duration(seconds: 20));
      while (samples.length < _requiredSamples &&
          DateTime.now().isBefore(deadline)) {
        if (!mounted) return;
        setState(() => _state = _EnrollState.collecting);

        // 单次尝试给足时间：一次请求会被「窗口内首个检测到人脸的帧」满足，
        // 故较长超时可吸收慢帧（录入页动画 + 主页推送会拖慢单帧处理）。
        final capture = await controller.captureFaceSample(
          timeout: const Duration(seconds: 6),
        );
        if (!mounted) return;

        if (capture == null || !capture.hasEmbedding) {
          // 本次窗口内没等到清晰人脸：不中止，回到循环继续重试（直到截止时间）。
          continue;
        }

        // 立即判重：若已有人脸库命中，中断并提示。
        final existing = controller.findExistingIdentity(capture.embedding!);
        if (existing != null) {
          _finish(_EnrollState.duplicate, '这张脸我已经认识啦，不用重复录入～');
          return;
        }

        samples.add(_Sample(capture.jpgBytes, capture.embedding!));
        setState(() => _collected = samples.length);

        // 采集间隔，引导用户调整角度增加多样性（最后一张不等）。
        if (samples.length < _requiredSamples) {
          await Future<void>.delayed(const Duration(milliseconds: 350));
        }
      }

      // 整个窗口一张都没采到才算失败（人脸全程不可见 / 模型异常）。
      if (samples.isEmpty) {
        _finish(_EnrollState.failed, '未捕获到清晰人脸，请正对摄像头后重试');
        return;
      }

      // 采集到至少 1 张即可保存（多张更稳；不足 5 张也允许，提升成功率）。
      // 保存头像 + 人物。
      final person = Person(
        id: DateTime.now().microsecondsSinceEpoch.toString(),
        name: name,
        relation: _relation,
      );
      person.embeddings.addAll(samples.map((s) => s.embedding));
      final avatarPath =
          await controller.saveAvatar(person.id, samples.first.thumb);
      person.avatarPath = avatarPath;
      await controller.savePerson(person);

      if (!mounted) return;
      _scanController.stop();
      setState(() => _state = _EnrollState.success);
      _toast('记住 $name 啦～下次见一定认得你！');
      await Future<void>.delayed(const Duration(milliseconds: 600));
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      _finish(_EnrollState.failed, '录入失败，请重试');
    }
  }

  void _finish(_EnrollState state, String msg) {
    if (!mounted) return;
    _scanController.stop();
    setState(() {
      _running = false;
      _state = state;
    });
    _toast(msg);
  }

  String get _instructionTitle {
    switch (_state) {
      case _EnrollState.detecting:
        return '请将面部正对摄像头';
      case _EnrollState.collecting:
        return '采集中…请保持稳定';
      case _EnrollState.success:
        return '录入成功';
      case _EnrollState.failed:
        return '录入失败';
      case _EnrollState.duplicate:
        return '已认识';
      case _EnrollState.idle:
        return _running ? '准备就绪' : '录入面部';
    }
  }

  String get _instructionSubtitle {
    switch (_state) {
      case _EnrollState.detecting:
      case _EnrollState.collecting:
        return _running
            ? '已采集 $_collected/$_requiredSamples · 请缓慢转动头部'
            : '保持光线充足，动作放缓';
      case _EnrollState.failed:
        return '请确保面部清晰可见，然后重试';
      case _EnrollState.duplicate:
        return '该人脸已在库中，无需重复录入';
      case _EnrollState.success:
        return '正在保存…';
      case _EnrollState.idle:
        return '输入名字后点击「开始录入」，相机将自动采集 $_requiredSamples 帧样本';
    }
  }

  Color get _ringColor {
    switch (_state) {
      case _EnrollState.failed:
      case _EnrollState.duplicate:
        return _red;
      case _EnrollState.success:
        return _green;
      case _EnrollState.detecting:
      case _EnrollState.collecting:
        return _blue;
      case _EnrollState.idle:
        return _blue;
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // 软键盘弹起时不挤压 body，保证左侧视频采集区尺寸稳定。
      resizeToAvoidBottomInset: false,
      backgroundColor: _bg,
      body: SafeArea(
        child: Column(
          children: [
            _buildTopBar(),
            Expanded(
              child: Row(
                children: [
                  Expanded(flex: 5, child: _buildScanPane()),
                  Expanded(flex: 4, child: _buildFormPane()),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Container(
      height: 52,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          CupertinoButton(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            onPressed: _running ? null : () => Navigator.pop(context, false),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(CupertinoIcons.back, color: _blue, size: 22),
                Text('返回', style: TextStyle(color: _blue, fontSize: 17)),
              ],
            ),
          ),
          const Expanded(
            child: Center(
              child: Text(
                '认识新朋友',
                style: TextStyle(
                    color: Colors.white,
                    fontSize: 17,
                    fontWeight: FontWeight.w600),
              ),
            ),
          ),
          const SizedBox(width: 96),
        ],
      ),
    );
  }

  // ===== 左侧：Face ID 风格视频采集区 =====

  Widget _buildScanPane() {
    return LayoutBuilder(
      builder: (context, constraints) {
        const padding = 16.0;
        final maxHeight = constraints.maxHeight - padding * 2;
        final maxWidth = constraints.maxWidth - padding * 2;
        final circle = math
            .min(maxHeight, maxWidth)
            .clamp(96.0, 360.0)
            .toDouble();
        return Center(child: _buildFaceCircle(circle));
      },
    );
  }

  Widget _buildFaceCircle(double size) {
    final ringPadding = 22.0;
    final previewSize = size - ringPadding * 2;

    return AnimatedBuilder(
      animation: _scanController,
      builder: (context, _) {
        return SizedBox(
          width: size,
          height: size,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // 扫描刻度环。
              CustomPaint(
                size: Size(size, size),
                painter: _FaceIdRingPainter(
                  activeColor: _ringColor,
                  baseColor: _separator,
                  sweep: _running ? _scanController.value : null,
                  progress: _collected / _requiredSamples,
                ),
              ),
              // 圆形摄像头取景。
              ClipOval(
                child: SizedBox(
                  width: previewSize,
                  height: previewSize,
                  child: _buildPreview(previewSize),
                ),
              ),
              // 成功态遮罩。
              if (_state == _EnrollState.success)
                Container(
                  width: previewSize,
                  height: previewSize,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _green.withValues(alpha: 0.18)),
                  child: const Icon(CupertinoIcons.checkmark_alt,
                      color: _green, size: 72),
                ),
              // 失败态遮罩。
              if (_state == _EnrollState.failed ||
                  _state == _EnrollState.duplicate)
                Container(
                  width: previewSize,
                  height: previewSize,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _red.withValues(alpha: 0.18)),
                  child: const Icon(CupertinoIcons.exclamationmark,
                      color: _red, size: 64),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildPreview(double size) {
    final controller = _controller.cameraController;
    if (controller == null || !controller.value.isInitialized) {
      return Container(
        color: _card,
        child: const Center(
            child: CupertinoActivityIndicator(color: Colors.white, radius: 14)),
      );
    }
    // 直接预览常驻的主相机流。previewSize 以传感器朝向给出，不同设备/锁屏状态
    // 可能报竖向也可能报横向；强制让 SizedBox 保持「横向比例（宽>高）」，
    // 再由 FittedBox.cover 按长边裁剪铺满圆形，避免拉伸变形。
    final preview = controller.value.previewSize ?? Size(size, size);
    final maxSide = math.max(preview.width, preview.height);
    final minSide = math.min(preview.width, preview.height);
    return ClipRect(
      child: OverflowBox(
        alignment: Alignment.center,
        child: FittedBox(
          fit: BoxFit.cover,
          child: SizedBox(
            width: maxSide,
            height: minSide,
            child: CameraPreview(controller),
          ),
        ),
      ),
    );
  }

  // ===== 右侧：表单区 =====

  Widget _buildFormPane() {
    return Container(
      decoration: const BoxDecoration(
          border: Border(left: BorderSide(color: _separator, width: 0.5))),
      child: Column(
        children: [
          Expanded(child: _buildFormContent()),
          _buildBottomBar(),
        ],
      ),
    );
  }

  Widget _buildFormContent() {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 状态提示卡。
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            margin: const EdgeInsets.only(bottom: 18),
            decoration: BoxDecoration(
              color: _card,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: _ringColor.withValues(alpha: 0.4)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _instructionTitle,
                  style: TextStyle(
                      color: _ringColor,
                      fontSize: 16,
                      fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 4),
                Text(
                  _instructionSubtitle,
                  style:
                      const TextStyle(color: _label, fontSize: 12, height: 1.4),
                ),
              ],
            ),
          ),
          _sectionHeader('个人信息'),
          _formCard([
            _textRow(
              label: '姓名',
              controller: _nameController,
              hint: '想让我怎么称呼你',
              onChanged: (_) => setState(() {}),
            ),
            _divider(),
            _relationRow(),
          ]),
          const SizedBox(height: 22),
          _sectionHeader('说明'),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration:
                BoxDecoration(color: _card, borderRadius: BorderRadius.circular(12)),
            child: const Text(
              '点击「开始录入」后，相机将自动采集多帧人脸样本，建议采集时缓慢转动头部以提升识别稳定性。',
              style: TextStyle(color: _label, fontSize: 13, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomBar() {
    final canStart =
        _nameController.text.trim().isNotEmpty && !_running;
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 14),
      decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: _separator, width: 0.5))),
      child: SizedBox(
        height: 48,
        width: double.infinity,
        child: CupertinoButton(
          padding: EdgeInsets.zero,
          borderRadius: BorderRadius.circular(12),
          color: canStart ? _blue : _cardRow,
          onPressed: canStart ? _start : null,
          child: Text(
            _running ? '采集中…（$_collected/$_requiredSamples）' : '开始录入',
            style: TextStyle(
              color: canStart ? Colors.white : _label,
              fontSize: 17,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }

  // ===== iOS 风格组件 =====

  Widget _sectionHeader(String text) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 0, 8),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
            color: _label,
            fontSize: 12,
            fontWeight: FontWeight.w500,
            letterSpacing: 0.5),
      ),
    );
  }

  Widget _formCard(List<Widget> rows) {
    return Container(
      decoration:
          BoxDecoration(color: _card, borderRadius: BorderRadius.circular(12)),
      child: Column(children: rows),
    );
  }

  Widget _divider() {
    return const Padding(
      padding: EdgeInsets.only(left: 16),
      child: Divider(height: 0.5, thickness: 0.5, color: _separator),
    );
  }

  Widget _textRow({
    required String label,
    required TextEditingController controller,
    required String hint,
    ValueChanged<String>? onChanged,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          SizedBox(
            width: 64,
            child: Text(label,
                style: const TextStyle(color: Colors.white, fontSize: 16)),
          ),
          Expanded(
            child: TextField(
              controller: controller,
              onChanged: onChanged,
              textAlign: TextAlign.right,
              style: const TextStyle(color: Colors.white, fontSize: 16),
              cursorColor: _blue,
              decoration: InputDecoration(
                isCollapsed: true,
                contentPadding: EdgeInsets.zero,
                border: InputBorder.none,
                isDense: true,
                hintText: hint,
                hintStyle: const TextStyle(color: _label, fontSize: 16),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _relationRow() {
    return InkWell(
      onTap: _running ? null : _pickRelation,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            SizedBox(
              width: 64,
              child: const Text('关系',
                  style: TextStyle(color: Colors.white, fontSize: 16)),
            ),
            const Spacer(),
            Text(_relation.label,
                style: const TextStyle(color: Colors.white, fontSize: 16)),
            const SizedBox(width: 4),
            const Icon(CupertinoIcons.chevron_up_chevron_down,
                size: 16, color: _label),
          ],
        ),
      ),
    );
  }

  void _pickRelation() {
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => Container(
        height: 260,
        color: AppTheme.secondaryBackground,
        child: Column(
          children: [
            SizedBox(
              height: 44,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  CupertinoButton(
                    onPressed: () => Navigator.of(ctx).pop(),
                    child: const Text('完成'),
                  ),
                ],
              ),
            ),
            Expanded(
              child: CupertinoPicker(
                backgroundColor: AppTheme.secondaryBackground,
                itemExtent: 36,
                scrollController: FixedExtentScrollController(
                  initialItem: FamilyRelation.values.indexOf(_relation),
                ),
                onSelectedItemChanged: (i) {
                  setState(() => _relation = FamilyRelation.values[i]);
                },
                children: [
                  for (final r in FamilyRelation.values)
                    Center(
                      child: Text(r.label,
                          style: const TextStyle(color: AppTheme.label)),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 单帧采集结果（缩略图 + 特征向量）。
class _Sample {
  final Uint8List thumb;
  final List<double> embedding;
  const _Sample(this.thumb, this.embedding);
}

/// Face ID 风格的放射状刻度环。
class _FaceIdRingPainter extends CustomPainter {
  _FaceIdRingPainter({
    required this.activeColor,
    required this.baseColor,
    this.sweep,
    this.progress = 0,
  });

  static const int tickCount = 72;
  final Color activeColor;
  final Color baseColor;
  final double? sweep;

  /// 采集进度 [0,1]，决定已完成刻度的染色比例。
  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final outerRadius = size.width / 2 - 2;
    const baseLen = 7.0;
    const activeLen = 14.0;

    for (var i = 0; i < tickCount; i++) {
      final t = i / tickCount;
      final angle = -math.pi / 2 + 2 * math.pi * t;
      final len = sweep != null ? activeLen : baseLen;
      final inner = outerRadius - len;
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      final p1 = Offset(center.dx + cosA * inner, center.dy + sinA * inner);
      final p2 =
          Offset(center.dx + cosA * outerRadius, center.dy + sinA * outerRadius);

      var color = baseColor;
      var width = 2.0;

      // 已采集部分整体染色，给予进度感。
      if (t < progress) {
        color = Color.lerp(baseColor, activeColor, 0.55)!;
        width = 2.5;
      }

      // 录入中：流动高光环绕。
      if (sweep != null) {
        final dist = (sweep! - t).abs();
        final glow = (1 - (dist * 6)).clamp(0.0, 1.0);
        if (glow > 0) {
          color = Color.lerp(color, activeColor, glow)!;
          width = 3.0 + glow * 1.5;
        }
      }
      canvas.drawLine(
          p1, p2, Paint()..color = color..strokeWidth = width..strokeCap = StrokeCap.round);
    }
  }

  @override
  bool shouldRepaint(covariant _FaceIdRingPainter old) =>
      old.activeColor != activeColor ||
      old.baseColor != baseColor ||
      old.sweep != sweep ||
      old.progress != progress;
}
