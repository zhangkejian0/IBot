import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../models/owner_profile.dart';
import '../../theme/app_theme.dart';
import 'face_scan_ring.dart';

/// 首次激活向导（iOS 风格，PageView 多步）。
///
/// 首次启动未注册时由根路由（`AppPhase.onboarding`）渲染。采集主人昵称、
/// 机器人昵称、性别、生日、人脸，完成后调用 [AppController.completeOnboarding]
/// 落盘本地档案 + best-effort 同步 Pophie 后端。
///
/// 人脸数据绝不上传（端侧 MobileFaceNet 本地比对）；本向导采集的样本连同
/// 文本档案交给控制器，由控制器决定落 people.json（relation=owner）与上传字段。
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with SingleTickerProviderStateMixin {
  late final PageController _pageController;
  late final AnimationController _scanController;
  int _step = 0;
  static const int _totalSteps = 5;

  // 表单暂存（最后一步统一提交，中途退出不留垃圾）。
  final _nicknameController = TextEditingController();
  final _robotNameController = TextEditingController(text: '狗蛋');
  Gender? _gender;
  DateTime? _birthday;

  // 人脸采集。
  final List<EnrollCapture> _faceSamples = [];
  FaceScanState _faceState = FaceScanState.idle;
  bool _faceScanning = false;
  String? _faceMessage;

  @override
  void initState() {
    super.initState();
    _pageController = PageController();
    _scanController = AnimationController(
      duration: const Duration(seconds: 2),
      vsync: this,
    );
  }

  @override
  void dispose() {
    _scanController.dispose();
    _pageController.dispose();
    _nicknameController.dispose();
    _robotNameController.dispose();
    // 离开向导时确保恢复沉浸式（避免输入态残留影响主界面）。
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    super.dispose();
  }

  AppController get _controller => AppScope.of(context);

  bool get _canGoNext {
    switch (_step) {
      case 1: // 关于你
        return _nicknameController.text.trim().isNotEmpty;
      default:
        return true;
    }
  }

  void _next() {
    if (_step < _totalSteps - 1) {
      _pageController.nextPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
    }
  }

  void _goTo(int i) {
    _pageController.animateToPage(
      i,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
    );
  }

  Future<void> _finish() async {
    final profile = OwnerProfile(
      nickname: _nicknameController.text.trim(),
      robotName: _robotNameController.text.trim().isEmpty
          ? '狗蛋'
          : _robotNameController.text.trim(),
      gender: _gender,
      birthday: _birthday,
      faceRegistered: _faceSamples.isNotEmpty,
    );
    // 由控制器落盘 + 上传；本地优先，不阻塞。
    await _controller.completeOnboarding(
      profile: profile,
      faceSamples: _faceSamples,
    );
    // phase 切到 ready 后根路由自动重建到主界面，无需手动 pop。
  }

  // ===== 人脸采集（内联，复用主相机 + captureFaceSample）=====
  static const int _requiredSamples = 5;

  Future<void> _startFaceScan() async {
    final controller = _controller;
    if (!controller.faceRecognition.isAvailable) {
      setState(() => _faceMessage = '未加载身份识别模型，可稍后在设置中补录');
      return;
    }
    setState(() {
      _faceScanning = true;
      _faceState = FaceScanState.collecting;
      _faceSamples.clear();
      _faceMessage = null;
    });
    _scanController.repeat();

    try {
      final deadline = DateTime.now().add(const Duration(seconds: 20));
      while (_faceSamples.length < _requiredSamples &&
          DateTime.now().isBefore(deadline)) {
        if (!mounted) return;
        final capture = await controller.captureFaceSample(
          timeout: const Duration(seconds: 6),
        );
        if (!mounted) return;
        if (capture == null || !capture.hasEmbedding) continue;

        // 判重：已有人脸库命中则中断（主人脸不应与现有库重复）。
        final existing = controller.findExistingIdentity(capture.embedding!);
        if (existing != null) {
          _scanController.stop();
          setState(() {
            _faceState = FaceScanState.duplicate;
            _faceScanning = false;
            _faceMessage = '这张脸我已认识啦，跳过录入';
          });
          return;
        }

        _faceSamples.add(capture);
        setState(() {}); // 刷新进度环
        if (_faceSamples.length < _requiredSamples) {
          await Future<void>.delayed(const Duration(milliseconds: 350));
        }
      }

      if (_faceSamples.isEmpty) {
        _scanController.stop();
        setState(() {
          _faceState = FaceScanState.failed;
          _faceScanning = false;
          _faceMessage = '未捕获到清晰人脸，可重试或稍后补录';
        });
        return;
      }
      _scanController.stop();
      setState(() {
        _faceState = FaceScanState.success;
        _faceScanning = false;
        _faceMessage = '记住你啦（${_faceSamples.length}/$_requiredSamples）';
      });
    } catch (_) {
      if (!mounted) return;
      _scanController.stop();
      setState(() {
        _faceState = FaceScanState.failed;
        _faceScanning = false;
        _faceMessage = '录入失败，可重试或稍后补录';
      });
    }
  }

  void _resetFaceScan() {
    setState(() {
      _faceSamples.clear();
      _faceState = FaceScanState.idle;
      _faceMessage = null;
    });
  }

  // ===== 选择器 =====
  void _pickGender() {
    Gender temp = _gender ?? Gender.male;
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
                    child: const Text('完成',
                        style: TextStyle(decoration: TextDecoration.none)),
                  ),
                ],
              ),
            ),
            Expanded(
              child: CupertinoPicker(
                backgroundColor: AppTheme.secondaryBackground,
                itemExtent: 36,
                scrollController: FixedExtentScrollController(
                  initialItem: Gender.values.indexOf(temp),
                ),
                onSelectedItemChanged: (i) => temp = Gender.values[i],
                children: [
                  for (final g in Gender.values)
                    Center(
                      child: Text(g.label,
                          style: const TextStyle(color: AppTheme.label)),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    ).then((_) {
      setState(() => _gender = temp);
    });
  }

  void _pickBirthday() {
    DateTime temp = _birthday ?? DateTime(1995, 1, 1);
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => Container(
        height: 300,
        color: AppTheme.secondaryBackground,
        child: Column(
          children: [
            SizedBox(
              height: 44,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  CupertinoButton(
                    onPressed: () => Navigator.of(ctx).pop(),
                    child: const Text('不填写',
                        style: TextStyle(decoration: TextDecoration.none)),
                  ),
                  CupertinoButton(
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      setState(() => _birthday = temp);
                    },
                    child: const Text('完成',
                        style: TextStyle(decoration: TextDecoration.none)),
                  ),
                ],
              ),
            ),
            Expanded(
              child: CupertinoDatePicker(
                mode: CupertinoDatePickerMode.date,
                initialDateTime: temp,
                maximumDate: DateTime.now(),
                minimumYear: 1920,
                onDateTimeChanged: (d) => temp = d,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: AppTheme.background,
      child: DefaultTextStyle(
        // 全局兜底：所有未单独指定样式的 Text 一律不带装饰/下划线。
        style: const TextStyle(decoration: TextDecoration.none),
        child: SafeArea(
          child: Column(
            children: [
              _buildProgressDots(),
              Expanded(
                child: PageView(
                  controller: _pageController,
                  physics: _faceScanning
                      ? const NeverScrollableScrollPhysics()
                      : null,
                  onPageChanged: (i) => setState(() => _step = i),
                  children: [
                    _buildWelcome(),
                    _buildAboutYou(),
                    _buildRobotName(),
                    _buildFace(),
                    _buildSummary(),
                  ],
                ),
              ),
              _buildBottomBar(),
            ],
          ),
        ),
      ),
    );
  }

  // ===== 顶部进度点 =====
  Widget _buildProgressDots() {
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < _totalSteps; i++)
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 4),
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: i == _step
                    ? AppTheme.accent
                    : (i < _step
                        ? AppTheme.accent.withValues(alpha: 0.5)
                        : const Color(0xFF48484A)),
              ),
            ),
        ],
      ),
    );
  }

  // ===== Step 0: 欢迎 =====
  Widget _buildWelcome() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 40),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(28),
            child: Image.asset(
              'assets/images/logo.png',
              width: 120,
              height: 120,
              fit: BoxFit.cover,
            ),
          ),
          const SizedBox(height: 32),
          const Text(
            '让我们认识一下',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: AppTheme.label,
              fontSize: 28,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.3,
            ),
          ),
          const SizedBox(height: 12),
          const Text(
            '几步设置，让陪伴更懂你。\n我会记住你的称呼、给我起的名字，并认出你。',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: AppTheme.secondaryLabel,
              fontSize: 15,
              height: 1.5,
            ),
          ),
        ],
      ),
    );
  }

  // ===== Step 1: 关于你 =====
  Widget _buildAboutYou() {
    final nickname = _nicknameController.text.trim();
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 48, vertical: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _title('关于你'),
          const SizedBox(height: 4),
          _subtitle('告诉我怎么称呼你（必填）'),
          const SizedBox(height: 20),
          _sectionCard([
            // 称呼：点击弹窗输入（弹窗内输入框不触发 OPPO 安全键盘）。
            _pickerRow(
              label: '称呼',
              value: nickname,
              hint: '想让我怎么叫你',
              onTap: () => _editNickname(),
            ),
            _rowDivider(),
            _pickerRow(
              label: '性别',
              value: _gender?.label ?? '',
              hint: '选填',
              onTap: _pickGender,
            ),
            _rowDivider(),
            _pickerRow(
              label: '生日',
              value: _birthday == null
                  ? ''
                  : '${_birthday!.year}-${_birthday!.month.toString().padLeft(2, '0')}-${_birthday!.day.toString().padLeft(2, '0')}',
              hint: '选填',
              onTap: _pickBirthday,
            ),
          ]),
        ],
      ),
    );
  }

  /// 称呼弹窗编辑（参照唤醒词的 CupertinoAlertDialog 模式）。
  /// OPPO 安全键盘只对直接铺页面的输入框触发，弹窗内的输入框不受影响。
  void _editNickname() {
    final controller = TextEditingController(text: _nicknameController.text);
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('你的称呼'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: CupertinoTextField(
            controller: controller,
            autofocus: true,
            placeholder: '想让我怎么叫你',
            textAlign: TextAlign.center,
            maxLength: 16,
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('取消',
                style: TextStyle(decoration: TextDecoration.none)),
          ),
          CupertinoDialogAction(
            onPressed: () {
              _nicknameController.text = controller.text;
              setState(() {});
              Navigator.of(ctx).pop();
            },
            child: const Text('保存',
                style: TextStyle(decoration: TextDecoration.none)),
          ),
        ],
      ),
    );
  }

  // ===== Step 2: 机器人昵称 =====
  Widget _buildRobotName() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 48, vertical: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _title('给它起个名字'),
          const SizedBox(height: 4),
          _subtitle('这是你对我的称呼（默认「狗蛋」）'),
          const SizedBox(height: 20),
          _sectionCard([
            // 名字：点击弹窗输入（弹窗内不触发安全键盘）。
            _pickerRow(
              label: '名字',
              value: _robotNameController.text.trim(),
              hint: '狗蛋',
              onTap: () => _editRobotName(),
            ),
          ]),
          const SizedBox(height: 28),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.groupedBackground,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Text(
              '你好，我是 ${_robotNameController.text.trim().isEmpty ? '狗蛋' : _robotNameController.text.trim()}，很高兴认识你！',
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppTheme.label, fontSize: 15, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  /// 机器人名字弹窗编辑（参照唤醒词模式，避开 OPPO 安全键盘）。
  void _editRobotName() {
    final controller = TextEditingController(text: _robotNameController.text);
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('给它起个名字'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: CupertinoTextField(
            controller: controller,
            autofocus: true,
            placeholder: '狗蛋',
            textAlign: TextAlign.center,
            maxLength: 16,
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('取消',
                style: TextStyle(decoration: TextDecoration.none)),
          ),
          CupertinoDialogAction(
            onPressed: () {
              _robotNameController.text = controller.text;
              setState(() {});
              Navigator.of(ctx).pop();
            },
            child: const Text('保存',
                style: TextStyle(decoration: TextDecoration.none)),
          ),
        ],
      ),
    );
  }

  // ===== Step 3: 人脸（横屏左右布局，参照「认识新朋友」页）=====
  Widget _buildFace() {
    final cameraController = _controller.cameraController;
    return Row(
      children: [
        // 左：大尺寸扫描环。
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Center(
              child: AnimatedBuilder(
                animation: _scanController,
                builder: (context, _) {
                  return FaceScanRing(
                    cameraController: cameraController,
                    state: _faceState,
                    progress: _faceSamples.length / _requiredSamples,
                    sweep: _faceScanning ? _scanController.value : null,
                  );
                },
              ),
            ),
          ),
        ),
        // 右：状态提示卡 + 说明（操作按钮复用全局底部栏，避免横屏竖向拥挤）。
        Container(
          width: 300,
          decoration: const BoxDecoration(
            border: Border(
                left: BorderSide(color: Color(0x5C545458), width: 0.5)),
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 状态提示卡。
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(14),
                  margin: const EdgeInsets.only(bottom: 18),
                  decoration: BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                        color: _ringFaceColor.withValues(alpha: 0.4)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _faceInstructionTitle,
                        style: TextStyle(
                            color: _ringFaceColor,
                            fontSize: 16,
                            fontWeight: FontWeight.w600),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _faceInstructionSubtitle,
                        style: const TextStyle(
                            color: AppTheme.secondaryLabel,
                            fontSize: 12,
                            height: 1.4),
                      ),
                    ],
                  ),
                ),
                _sectionHeader('说明'),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Text(
                    '点击「开始录入」后，相机将自动采集多帧人脸样本，建议采集时缓慢转动头部以提升识别稳定性。',
                    style: TextStyle(
                        color: AppTheme.secondaryLabel,
                        fontSize: 13,
                        height: 1.5),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Color get _ringFaceColor {
    switch (_faceState) {
      case FaceScanState.success:
        return AppTheme.accentGreen;
      case FaceScanState.failed:
      case FaceScanState.duplicate:
        return AppTheme.accentRed;
      default:
        return AppTheme.accent;
    }
  }

  String get _faceInstructionTitle {
    switch (_faceState) {
      case FaceScanState.collecting:
        return _faceScanning ? '采集中…请保持稳定' : '请将面部正对摄像头';
      case FaceScanState.success:
        return '录入成功';
      case FaceScanState.failed:
        return '录入失败';
      case FaceScanState.duplicate:
        return '已认识';
      case FaceScanState.idle:
        return '录入面部';
    }
  }

  String get _faceInstructionSubtitle {
    switch (_faceState) {
      case FaceScanState.collecting:
        return _faceScanning
            ? '已采集 ${_faceSamples.length}/$_requiredSamples · 请缓慢转动头部'
            : '保持光线充足，动作放缓';
      case FaceScanState.failed:
        return _faceMessage ?? '未捕获到清晰人脸，可重试或稍后补录';
      case FaceScanState.duplicate:
        return _faceMessage ?? '这张脸我已认识啦';
      case FaceScanState.success:
        return _faceMessage ?? '记住你啦，下次见一定认得你';
      case FaceScanState.idle:
        return '让机器人认出你是谁';
    }
  }

  Widget _sectionHeader(String text) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 0, 8),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
            color: AppTheme.secondaryLabel,
            fontSize: 12,
            fontWeight: FontWeight.w500,
            letterSpacing: 0.5),
      ),
    );
  }

  // ===== Step 4: 总结 =====
  Widget _buildSummary() {
    final name = _nicknameController.text.trim();
    final robot = _robotNameController.text.trim().isEmpty
        ? '狗蛋'
        : _robotNameController.text.trim();
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 48, vertical: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _title('确认信息'),
          const SizedBox(height: 4),
          _subtitle('检查无误后即可完成'),
          const SizedBox(height: 20),
          _sectionCard([
            _summaryRow('你的称呼', name),
            _rowDivider(),
            _summaryRow('机器人名', robot),
            _rowDivider(),
            _summaryRow('性别', _gender?.label ?? '未填写'),
            _rowDivider(),
            _summaryRow(
                '生日',
                _birthday == null
                    ? '未填写'
                    : '${_birthday!.year}-${_birthday!.month.toString().padLeft(2, '0')}-${_birthday!.day.toString().padLeft(2, '0')}'),
            _rowDivider(),
            _summaryRow(
                '人脸', _faceSamples.isEmpty ? '未录入' : '已录入（${_faceSamples.length} 张）'),
          ]),
          const SizedBox(height: 20),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppTheme.groupedBackground,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Text(
              '人脸数据仅保存在本机用于识别，不会上传。\n称呼等信息会同步到服务端。',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppTheme.secondaryLabel, fontSize: 12, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  // ===== 底部导航 =====
  Widget _buildBottomBar() {
    final isLast = _step == _totalSteps - 1;
    final isFaceStep = _step == 3; // 人脸录入步
    final canNext = _canGoNext;

    // 动态主按钮：人脸步骤复用为「开始录入/采集中/继续」，避免横屏竖向拥挤。
    final String primaryLabel;
    final VoidCallback? primaryAction;
    if (isFaceStep) {
      if (_faceScanning) {
        primaryLabel = '采集中…（${_faceSamples.length}/$_requiredSamples）';
        primaryAction = null;
      } else if (_faceSamples.isEmpty) {
        primaryLabel = '开始录入';
        primaryAction = _startFaceScan;
      } else {
        primaryLabel = '继续';
        primaryAction = _next;
      }
    } else {
      primaryLabel = isLast ? '完成' : '继续';
      primaryAction = (isLast || canNext)
          ? (isLast ? _finish : _next)
          : null;
    }

    return Container(
      padding: const EdgeInsets.fromLTRB(48, 12, 48, 18),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Color(0x5C545458), width: 0.5)),
      ),
      child: SizedBox(
        height: 50,
        child: Row(
          children: [
            if (_step > 0)
              CupertinoButton(
                padding: EdgeInsets.zero,
                onPressed: _faceScanning
                    ? null
                    : () => _goTo(_step - 1),
                child: const Text('上一步',
                    style: TextStyle(
                        color: AppTheme.secondaryLabel,
                        decoration: TextDecoration.none)),
              )
            else
              const SizedBox(width: 72),
            // 人脸步骤已有样本时提供「重新录入」次级入口。
            if (isFaceStep &&
                _faceSamples.isNotEmpty &&
                !_faceScanning)
              CupertinoButton(
                padding: const EdgeInsets.only(left: 8),
                onPressed: _resetFaceScan,
                child: const Text('重新录入',
                    style: TextStyle(
                        color: AppTheme.accent,
                        decoration: TextDecoration.none)),
              ),
            const Spacer(),
            SizedBox(
              width: 160,
              child: CupertinoButton.filled(
                onPressed: primaryAction,
                child: Text(primaryLabel,
                    style: const TextStyle(
                        decoration: TextDecoration.none)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ===== 通用组件 =====
  Widget _title(String text) => Text(text,
      style: const TextStyle(
          color: AppTheme.label,
          fontSize: 24,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.3));

  Widget _subtitle(String text) => Text(text,
      style: const TextStyle(color: AppTheme.secondaryLabel, fontSize: 14, height: 1.4));

  Widget _sectionCard(List<Widget> rows) => Container(
        decoration: BoxDecoration(
          color: AppTheme.groupedBackground,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(children: rows),
      );

  Widget _rowDivider() => const Padding(
        padding: EdgeInsets.only(left: 16),
        child: Divider(height: 0.5, thickness: 0.5, color: Color(0x5C545458)),
      );

  Widget _pickerRow({
    required String label,
    required String value,
    required String hint,
    required VoidCallback onTap,
  }) {
    final hasValue = value.isNotEmpty;
    return CupertinoButton(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      minimumSize: Size.zero,
      onPressed: onTap,
      child: Row(
        children: [
          SizedBox(
              width: 64,
              child: Text(label,
                  style: const TextStyle(
                      color: AppTheme.label,
                      fontSize: 16,
                      decoration: TextDecoration.none))),
          const Spacer(),
          Text(hasValue ? value : hint,
              style: TextStyle(
                  color: hasValue ? AppTheme.label : const Color(0x99EBEBF5),
                  fontSize: 16,
                  decoration: TextDecoration.none)),
          const SizedBox(width: 4),
          const Icon(CupertinoIcons.chevron_up_chevron_down,
              size: 14, color: Color(0x99EBEBF5)),
        ],
      ),
    );
  }

  Widget _summaryRow(String label, String value) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Text(label,
                style: const TextStyle(
                    color: Color(0x99EBEBF5), fontSize: 15)),
            const Spacer(),
            Text(value,
                style: const TextStyle(color: AppTheme.label, fontSize: 15)),
          ],
        ),
      );
}
