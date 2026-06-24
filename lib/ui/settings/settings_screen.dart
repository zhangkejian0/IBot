import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show MaterialPageRoute;

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../services/voice/pophie_client.dart';
import '../../services/voice/pophie_config.dart';
import '../../theme/app_theme.dart';
import 'base_debug_screen.dart';
import 'face_registration_screen.dart';
import 'friend_list_sheet.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = AppScope.of(context);
    final s = controller.settings;

    return CupertinoPageScaffold(
      backgroundColor: AppTheme.background,
      navigationBar: const CupertinoNavigationBar(
        backgroundColor: Color(0xF01C1C1E),
        middle: Text('设置', style: TextStyle(color: AppTheme.label)),
      ),
      child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          return SafeArea(
            child: ListView(
              padding: const EdgeInsets.symmetric(vertical: 16),
              children: [
                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('显示模式'),
                  footer: const _Footer(
                      '关闭时显示虚拟宠物画面；开启后切换到摄像头识别画面（用于调试人脸/手势识别）。'),
                  children: [
                    _SwitchTile(
                      icon: CupertinoIcons.antenna_radiowaves_left_right,
                      color: AppTheme.accentOrange,
                      label: '调试模式',
                      value: s.debugMode,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.debugMode = v),
                    ),
                  ],
                ),

                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('身份识别'),
                  children: [
                    if (controller.isOwnerRegistered)
                      CupertinoListTile.notched(
                        backgroundColor: AppTheme.groupedBackground,
                        leading: const _LeadingIcon(
                            CupertinoIcons.person_fill, AppTheme.accentPurple),
                        title: const Text('主人',
                            style: TextStyle(color: AppTheme.label)),
                        subtitle: Text(
                          controller.ownerProfile?.nickname ?? '—',
                          style:
                              const TextStyle(color: AppTheme.secondaryLabel),
                        ),
                      ),
                    CupertinoListTile.notched(
                      backgroundColor: AppTheme.groupedBackground,
                      leading: const _LeadingIcon(
                          CupertinoIcons.person_2_fill, AppTheme.accent),
                      title: const Text('认识我',
                          style: TextStyle(color: AppTheme.label)),
                      subtitle: Text(
                        '已录入 ${controller.personRepository.people.length} 人',
                        style: const TextStyle(color: AppTheme.secondaryLabel),
                      ),
                      trailing: const CupertinoListTileChevron(),
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                              builder: (_) =>
                                  const FaceRegistrationScreen()),
                        );
                      },
                    ),
                    if (controller.personRepository.people.isNotEmpty)
                      CupertinoListTile.notched(
                        backgroundColor: AppTheme.groupedBackground,
                        leading: const _LeadingIcon(
                            CupertinoIcons.person_2_fill,
                            AppTheme.accentGreen),
                        title: const Text('我的朋友',
                            style: TextStyle(color: AppTheme.label)),
                        subtitle: Text(
                          '${controller.personRepository.people.length} 位',
                          style:
                              const TextStyle(color: AppTheme.secondaryLabel),
                        ),
                        trailing: const CupertinoListTileChevron(),
                        onTap: () =>
                            showFriendListSheet(context, controller),
                      ),
                    _SwitchTile(
                      icon: CupertinoIcons.person_crop_circle_badge_checkmark,
                      color: AppTheme.accent,
                      label: '启用身份识别',
                      value: s.identityEnabled,
                      onChanged: controller.faceRecognition.isAvailable
                          ? (v) => controller
                              .updateSettings(() => s.identityEnabled = v)
                          : null,
                    ),
                    if (controller.isOwnerRegistered)
                      CupertinoListTile.notched(
                        backgroundColor: AppTheme.groupedBackground,
                        leading: const _LeadingIcon(
                            CupertinoIcons.arrow_counterclockwise_circle_fill,
                            AppTheme.accentOrange),
                        title: const Text('重新设置主人',
                            style: TextStyle(color: AppTheme.label)),
                        subtitle: const Text(
                          '清除主人信息并重新进入引导',
                          style: TextStyle(color: AppTheme.secondaryLabel),
                        ),
                        trailing: const CupertinoListTileChevron(),
                        onTap: () => _confirmResetOwner(context, controller),
                      ),
                  ],
                ),

                _ModelStatus(controller: controller),

                // 语音助手分组
                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('语音助手'),
                  footer: const _Footer(
                      '开启后可语音唤醒并对话。唤醒词可在本地离线检测,识别与对话走云端。'
                      '语音活跃时虚拟宠物会切换到聆听/思考/说话表情。'),
                  children: [
                    _SwitchTile(
                      icon: CupertinoIcons.waveform_circle_fill,
                      color: AppTheme.accent,
                      label: '启用语音助手',
                      value: s.voiceEnabled,
                      // 无麦克风权限或子服务未就绪时禁用开关。
                      onChanged: controller.voiceAssistant.isAvailable
                          ? (v) =>
                              controller.updateSettings(() => s.voiceEnabled = v)
                          : null,
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.antenna_radiowaves_left_right,
                      color: AppTheme.accentGreen,
                      label: '语音唤醒',
                      value: s.wakeWordEnabled,
                      // 仅在语音助手启用且唤醒模型加载成功时才有意义。
                      // 模型未就绪时禁用此开关(但整体语音助手仍可用——双击触发)。
                      onChanged: (s.voiceEnabled &&
                              controller.voiceAssistant.isAvailable &&
                              controller.voiceAssistant.wakeWord.isAvailable)
                          ? (v) => controller
                              .updateSettings(() => s.wakeWordEnabled = v)
                          : null,
                    ),
                    _WakeWordTile(
                      keyword: s.wakeWord,
                      // 唤醒关闭或语音未启用时不可编辑。
                      enabled: s.voiceEnabled &&
                          s.wakeWordEnabled &&
                          controller.voiceAssistant.isAvailable,
                      onChanged: (text) => controller
                          .updateSettings(() => s.wakeWord = text),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.volume_up,
                      color: AppTheme.accentOrange,
                      label: '语音播报',
                      value: s.ttsEnabled,
                      onChanged: (s.voiceEnabled &&
                              controller.voiceAssistant.isAvailable)
                          ? (v) =>
                              controller.updateSettings(() => s.ttsEnabled = v)
                          : null,
                    ),
                  ],
                ),

                // Pophie 后端配置分组:语音对话全程走该后端(STT+LLM+TTS 一体化)。
                _PophieConfigSection(controller: controller),

                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('识别功能'),
                  children: [
                    _SwitchTile(
                      icon: CupertinoIcons.smiley,
                      color: AppTheme.accentGreen,
                      label: '人脸表情识别',
                      value: s.faceEnabled,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.faceEnabled = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.hand_raised_fill,
                      color: AppTheme.accentOrange,
                      label: '手势识别',
                      value: s.handEnabled,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.handEnabled = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.cube_box_fill,
                      color: AppTheme.accentTeal,
                      label: '物体识别',
                      value: s.objectEnabled,
                      onChanged: controller.objectEngine.isInitialized
                          ? (v) => controller
                              .updateSettings(() => s.objectEnabled = v)
                          : null,
                    ),
                  ],
                ),

                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('调试显示（第一版）'),
                  footer: const _Footer(
                      '调试阶段会在画面上叠加人脸关键点、人脸框、手部骨架与各类标签。'),
                  children: [
                    _SwitchTile(
                      icon: CupertinoIcons.circle_grid_3x3_fill,
                      color: AppTheme.accentTeal,
                      label: '人脸关键点网格',
                      value: s.showFaceMesh,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.showFaceMesh = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.rectangle,
                      color: AppTheme.accentGreen,
                      label: '人脸框',
                      value: s.showFaceBox,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.showFaceBox = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.hand_draw_fill,
                      color: AppTheme.accentOrange,
                      label: '手部骨架',
                      value: s.showHandSkeleton,
                      onChanged: (v) => controller
                          .updateSettings(() => s.showHandSkeleton = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.number,
                      color: AppTheme.accentYellow,
                      label: '显示关键点序号',
                      value: s.showLandmarkIndices,
                      onChanged: (v) => controller
                          .updateSettings(() => s.showLandmarkIndices = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.textformat,
                      color: AppTheme.accentPurple,
                      label: '表情标签',
                      value: s.showExpression,
                      onChanged: (v) => controller
                          .updateSettings(() => s.showExpression = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.hand_thumbsup_fill,
                      color: AppTheme.accentOrange,
                      label: '手势标签',
                      value: s.showGesture,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.showGesture = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.person_crop_square,
                      color: AppTheme.accent,
                      label: '身份标签',
                      value: s.showIdentity,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.showIdentity = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.cube_box,
                      color: AppTheme.accentTeal,
                      label: '物体框',
                      value: s.showObject,
                      onChanged: (v) =>
                          controller.updateSettings(() => s.showObject = v),
                    ),
                    _SwitchTile(
                      icon: CupertinoIcons.arrow_left_right,
                      color: AppTheme.secondaryLabel,
                      label: '前置摄像头镜像',
                      value: s.mirrorFrontCamera,
                      onChanged: (v) => controller
                          .updateSettings(() => s.mirrorFrontCamera = v),
                    ),
                  ],
                ),

                // 底座控制分组
                CupertinoListSection.insetGrouped(
                  backgroundColor: AppTheme.background,
                  decoration: const BoxDecoration(
                    color: AppTheme.groupedBackground,
                    borderRadius: BorderRadius.all(Radius.circular(12)),
                  ),
                  header: const _Header('底座控制'),
                  footer: const _Footer('连接物理旋转底座，进行调试和控制。'),
                  children: [
                    _SwitchTile(
                      icon: CupertinoIcons.gear_alt_fill,
                      color: AppTheme.accentTeal,
                      label: '启用底座控制',
                      value: s.baseControlEnabled,
                      onChanged: (v) => controller.updateSettings(
                          () => s.baseControlEnabled = v),
                    ),
                    CupertinoListTile.notched(
                      backgroundColor: AppTheme.groupedBackground,
                      leading: const _LeadingIcon(
                          CupertinoIcons.wrench_fill, AppTheme.accentOrange),
                      title: const Text('调试面板',
                          style: TextStyle(color: AppTheme.label)),
                      subtitle: const Text('连接、手动控制、指令日志',
                          style:
                              TextStyle(color: AppTheme.secondaryLabel)),
                      trailing: const CupertinoListTileChevron(),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                            builder: (_) => const BaseDebugScreen()),
                      ),
                    ),
                  ],
                ),

                const _AboutSection(),
              ],
            ),
          );
        },
      ),
    );
  }
}

/// 「重新设置主人」二次确认。确认后调用 resetOwner，phase 切回 onboarding，
/// 根路由自动重建到向导（设置页随之销毁）。
void _confirmResetOwner(BuildContext context, AppController controller) {
  showCupertinoDialog<void>(
    context: context,
    builder: (ctx) => CupertinoAlertDialog(
      title: const Text('重新设置主人'),
      content: const Text('将清除主人信息（含人脸）并重新进入引导。此操作不可撤销，确定继续吗？'),
      actions: [
        CupertinoDialogAction(
          isDefaultAction: true,
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('取消'),
        ),
        CupertinoDialogAction(
          isDestructiveAction: true,
          onPressed: () {
            Navigator.of(ctx).pop();
            controller.resetOwner();
          },
          child: const Text('重新设置'),
        ),
      ],
    ),
  );
}

class _ModelStatus extends StatelessWidget {
  const _ModelStatus({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final available = controller.faceRecognition.isAvailable;
    final msg = controller.faceRecognition.statusMessage ?? '';
    return Padding(
      padding: const EdgeInsets.fromLTRB(32, 4, 32, 4),
      child: Row(
        children: [
          Icon(
            available
                ? CupertinoIcons.checkmark_seal_fill
                : CupertinoIcons.exclamationmark_triangle_fill,
            size: 14,
            color: available ? AppTheme.accentGreen : AppTheme.accentOrange,
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              msg,
              style: const TextStyle(
                  color: AppTheme.tertiaryLabel, fontSize: 12, decoration: TextDecoration.none),
            ),
          ),
        ],
      ),
    );
  }
}

class _AboutSection extends StatelessWidget {
  const _AboutSection();

  @override
  Widget build(BuildContext context) {
    return CupertinoListSection.insetGrouped(
      backgroundColor: AppTheme.background,
      decoration: const BoxDecoration(
        color: AppTheme.groupedBackground,
        borderRadius: BorderRadius.all(Radius.circular(12)),
      ),
      header: const _Header('关于'),
      children: const [
        CupertinoListTile.notched(
          backgroundColor: AppTheme.groupedBackground,
          leading: _LeadingIcon(CupertinoIcons.info_circle_fill,
              AppTheme.secondaryLabel),
          title: Text('版本', style: TextStyle(color: AppTheme.label)),
          trailing: Text('1.0.0',
              style: TextStyle(color: AppTheme.secondaryLabel, fontSize: 14, decoration: TextDecoration.none)),
        ),
      ],
    );
  }
}

class _SwitchTile extends StatelessWidget {
  const _SwitchTile({
    required this.icon,
    required this.color,
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final IconData icon;
  final Color color;
  final String label;
  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return CupertinoListTile.notched(
      backgroundColor: AppTheme.groupedBackground,
      leading: _LeadingIcon(icon, color),
      title: Text(
        label,
        style: TextStyle(
          color: onChanged == null ? AppTheme.tertiaryLabel : AppTheme.label,
        ),
      ),
      trailing: CupertinoSwitch(
        value: value,
        onChanged: onChanged,
        activeTrackColor: AppTheme.accentGreen,
      ),
    );
  }
}

class _LeadingIcon extends StatelessWidget {
  const _LeadingIcon(this.icon, this.color);
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 29,
      height: 29,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(7),
      ),
      child: Icon(icon, color: AppTheme.label, size: 18),
    );
  }
}

/// 唤醒词输入行:显示当前唤醒词,点击可编辑(弹窗输入)。
/// 复用 _LeadingIcon 的视觉风格,保持与 _SwitchTile 一致。
class _WakeWordTile extends StatelessWidget {
  const _WakeWordTile({
    required this.keyword,
    required this.enabled,
    required this.onChanged,
  });

  final String keyword;
  final bool enabled;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return CupertinoListTile.notched(
      backgroundColor: AppTheme.groupedBackground,
      leading: _LeadingIcon(
          CupertinoIcons.mic_circle_fill, AppTheme.accentPurple),
      title: Text(
        '唤醒词',
        style: TextStyle(
          color: enabled ? AppTheme.label : AppTheme.tertiaryLabel,
        ),
      ),
      subtitle: Text(
        keyword,
        style: const TextStyle(color: AppTheme.secondaryLabel),
      ),
      trailing: enabled
          ? const CupertinoListTileChevron()
          : const SizedBox.shrink(),
      onTap: enabled ? () => _showEditor(context) : null,
    );
  }

  void _showEditor(BuildContext context) {
    final controller = TextEditingController(text: keyword);
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('设置唤醒词'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: CupertinoTextField(
            controller: controller,
            autofocus: true,
            placeholder: '如：你好',
            textAlign: TextAlign.center,
            maxLength: 16,
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('取消'),
            onPressed: () => Navigator.of(ctx).pop(),
          ),
          CupertinoDialogAction(
            child: const Text('保存'),
            onPressed: () {
              final text = controller.text.trim();
              if (text.isNotEmpty) onChanged(text);
              Navigator.of(ctx).pop();
            },
          ),
        ],
      ),
    );
  }
}

/// Pophie 后端配置分组。语音对话全程走该后端的 /api/chat
/// (STT+LLM+TTS 一体化,见 docs/API对接文档.md)。每一项点击可编辑,
/// 保存后持久化并实时下发到客户端。订阅 controller 以反映最新值。
class _PophieConfigSection extends StatelessWidget {
  const _PophieConfigSection({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final cfg = controller.pophieConfigStore.config;
        return CupertinoListSection.insetGrouped(
          backgroundColor: AppTheme.background,
          decoration: const BoxDecoration(
            color: AppTheme.groupedBackground,
            borderRadius: BorderRadius.all(Radius.circular(12)),
          ),
          header: const _Header('Pophie 服务'),
          footer: const _Footer(
              '语音对话走 Pophie 后端,一次完成识别+大模型+合成。'
              '请填写后端地址(局域网需用电脑 IP,非 127.0.0.1)。'),
          children: [
            _ConfigTile(
              icon: CupertinoIcons.link,
              color: AppTheme.accent,
              label: '后端地址',
              value: cfg.baseUrl,
              placeholder: PophieConfig.defaultBaseUrl,
              editorLabel: '后端地址 (Base URL)',
              editorHint: PophieConfig.defaultBaseUrl,
              // 去掉结尾斜杠,客户端会自行拼接 /api/chat。
              onTap: (v) => controller.updatePophieConfig(
                  cfg.copyWith(baseUrl: _trimSlash(v))),
            ),
            // TTS 音色:从 /api/schema 拉取列表做选择(不让手输,避免填错 id)。
            _TtsVoiceTile(
              voiceId: cfg.voiceId,
              pophie: controller.voiceAssistant.pophie,
              onChanged: (v) =>
                  controller.updatePophieConfig(cfg.copyWith(voiceId: v)),
            ),
            CupertinoListTile.notched(
              backgroundColor: AppTheme.groupedBackground,
              leading: const _LeadingIcon(
                  CupertinoIcons.device_phone_portrait, AppTheme.secondaryLabel),
              title: const Text('设备 ID',
                  style: TextStyle(color: AppTheme.label)),
              subtitle: Text(
                cfg.robotId,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: AppTheme.tertiaryLabel),
              ),
            ),
            CupertinoListTile.notched(
              backgroundColor: AppTheme.groupedBackground,
              leading: const _LeadingIcon(
                  CupertinoIcons.antenna_radiowaves_left_right,
                  AppTheme.accentGreen),
              title: const Text('测试连接',
                  style: TextStyle(color: AppTheme.label)),
              subtitle: const Text('检查后端是否可达',
                  style: TextStyle(color: AppTheme.secondaryLabel)),
              trailing: const CupertinoListTileChevron(),
              onTap: () => _testConnection(context),
            ),
            // 交互日志:查看每一轮对话的唤醒/STT/LLM/TTS 详情与错误,
            // 排查"说了话没反应""表情卡住"等问题。
            CupertinoListTile.notched(
              backgroundColor: AppTheme.groupedBackground,
              leading: _LeadingIcon(
                  CupertinoIcons.list_bullet, AppTheme.accent),
              title: const Text('交互日志',
                  style: TextStyle(color: AppTheme.label)),
              subtitle: const Text('查看语音对话各阶段记录',
                  style: TextStyle(color: AppTheme.secondaryLabel)),
              trailing: const CupertinoListTileChevron(),
              onTap: () => _showConversationLog(context),
            ),
          ],
        );
      },
    );
  }

  static String _trimSlash(String v) {
    var s = v.trim();
    while (s.endsWith('/')) {
      s = s.substring(0, s.length - 1);
    }
    return s;
  }

  Future<void> _testConnection(BuildContext context) async {
    final ok = await controller.voiceAssistant.pophie.health();
    if (!context.mounted) return;
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(ok ? '连接成功' : '连接失败'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(ok
              ? '后端可达,语音能力已就绪。'
              : '无法连接后端,请检查地址与网络(同一局域网、防火墙、端口 8000)。'),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('好'),
            onPressed: () => Navigator.of(ctx).pop(),
          ),
        ],
      ),
    );
  }

  /// 打开交互日志弹窗:全屏模态,展示最近若干轮对话的各阶段记录。
  /// 错误记录用红色标记;支持清空。订阅 ConversationLogger 实时刷新。
  Future<void> _showConversationLog(BuildContext context) async {
    await Navigator.of(context).push(
      CupertinoPageRoute<void>(
        builder: (_) => _ConversationLogScreen(
          logger: controller.voiceAssistant.conversationLog,
        ),
      ),
    );
  }
}

/// 交互日志全屏页:列表展示 [ConversationLogger] 的记录,实时刷新。
/// 每条记录显示阶段徽标 + 详情 + 相对时间;错误记录红色高亮。
class _ConversationLogScreen extends StatefulWidget {
  const _ConversationLogScreen({required this.logger});
  final dynamic logger; // ConversationLogger(避免此处再 import,用 dynamic)

  @override
  State<_ConversationLogScreen> createState() => _ConversationLogScreenState();
}

class _ConversationLogScreenState extends State<_ConversationLogScreen> {
  @override
  void initState() {
    super.initState();
    widget.logger.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.logger.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  /// 阶段标识 → 显示色。
  Color _stageColor(String stage, bool error) {
    if (error) return const Color(0xFFFF453A); // 红
    switch (stage) {
      case 'wake':
        return const Color(0xFFFFD60A); // 黄
      case 'listen':
        return const Color(0xFF64D2FF); // 青
      case 'think':
        return const Color(0xFFBF5AF2); // 紫
      case 'speak':
        return const Color(0xFF30D158); // 绿
      case 'proactive':
        return const Color(0xFFFF9F0A); // 橙(主动提醒,与对话各阶段区分)
      case 'end':
        return AppTheme.tertiaryLabel;
      default:
        return AppTheme.secondaryLabel;
    }
  }

  @override
  Widget build(BuildContext context) {
    final entries = widget.logger.entries as List;
    return CupertinoPageScaffold(
      backgroundColor: AppTheme.background,
      navigationBar: CupertinoNavigationBar(
        middle: const Text('交互日志'),
        backgroundColor: AppTheme.background,
        trailing: GestureDetector(
          onTap: () => widget.logger.clear(),
          child: const Padding(
            padding: EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Text('清空',
                style: TextStyle(color: AppTheme.accent, fontSize: 16)),
          ),
        ),
      ),
      child: SafeArea(
        child: entries.isEmpty
            ? const Center(
                child: Text('暂无记录\n唤醒「你好」开始一轮对话即可看到日志',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppTheme.secondaryLabel)),
              )
            // 倒序展示:最新在最上,便于查看最近一轮对话。
            : ListView.builder(
                reverse: true,
                padding: const EdgeInsets.symmetric(vertical: 8),
                itemCount: entries.length,
                itemBuilder: (context, i) {
                  final e = entries[entries.length - 1 - i];
                  final ts = e.timestamp as DateTime;
                  final dur = DateTime.now().difference(ts);
                  final ago = dur.inMinutes < 1
                      ? '${dur.inSeconds}秒前'
                      : dur.inHours < 1
                          ? '${dur.inMinutes}分前'
                          : '${dur.inHours}时前';
                  final color = _stageColor(e.stage as String, e.error as bool);
                  return Container(
                    margin: const EdgeInsets.symmetric(
                        horizontal: 12, vertical: 4),
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: AppTheme.groupedBackground,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // 阶段徽标(彩色圆点)
                        Container(
                          margin: const EdgeInsets.only(top: 4, right: 8),
                          width: 8,
                          height: 8,
                          decoration: BoxDecoration(
                            color: color,
                            shape: BoxShape.circle,
                          ),
                        ),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Text(e.stage as String,
                                      style: TextStyle(
                                        color: color,
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                      )),
                                  const SizedBox(width: 8),
                                  Text(ago,
                                      style: const TextStyle(
                                        color: AppTheme.tertiaryLabel,
                                        fontSize: 11,
                                      )),
                                ],
                              ),
                              const SizedBox(height: 4),
                              Text(e.message as String,
                                  style: TextStyle(
                                    color: e.error as bool
                                        ? const Color(0xFFFF9D8A)
                                        : AppTheme.label,
                                    fontSize: 13,
                                    height: 1.4,
                                  )),
                            ],
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
      ),
    );
  }
}

/// 单行配置编辑项:显示当前值(或 placeholder),点击弹窗输入并回调。
/// 复用 _LeadingIcon 视觉风格,与 _SwitchTile 一致。
class _ConfigTile extends StatelessWidget {
  const _ConfigTile({
    required this.icon,
    required this.color,
    required this.label,
    required this.value,
    required this.placeholder,
    required this.editorLabel,
    required this.editorHint,
    required this.onTap,
  });

  final IconData icon;
  final Color color;
  final String label;
  final String value;
  final String placeholder;
  final String editorLabel;
  final String editorHint;
  final ValueChanged<String> onTap;

  @override
  Widget build(BuildContext context) {
    final hasValue = value.trim().isNotEmpty;
    return CupertinoListTile.notched(
      backgroundColor: AppTheme.groupedBackground,
      leading: _LeadingIcon(icon, color),
      title: Text(label, style: const TextStyle(color: AppTheme.label)),
      subtitle: Text(
        hasValue ? value : placeholder,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: hasValue ? AppTheme.secondaryLabel : AppTheme.tertiaryLabel,
        ),
      ),
      trailing: const CupertinoListTileChevron(),
      onTap: () => _showEditor(context),
    );
  }

  void _showEditor(BuildContext context) {
    final tec = TextEditingController(text: value);
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(editorLabel),
        content: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: CupertinoTextField(
            controller: tec,
            autofocus: true,
            placeholder: editorHint,
            // API Key 不强制隐藏,便于核对;但可按需切 obscure。
            clearButtonMode: OverlayVisibilityMode.editing,
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('取消'),
            onPressed: () => Navigator.of(ctx).pop(),
          ),
          CupertinoDialogAction(
            child: const Text('保存'),
            onPressed: () {
              final text = tec.text.trim();
              if (text.isNotEmpty) onTap(text);
              Navigator.of(ctx).pop();
            },
          ),
        ],
      ),
    );
  }
}

/// TTS 音色选择项:点击从 /api/schema 拉取音色列表,以底部选择器展示。
/// 不让用户手输,避免填错 voice_id。空字符串表示用服务端默认音色。
/// 拉取失败时回退为"默认音色"单选,并提示错误。
class _TtsVoiceTile extends StatefulWidget {
  const _TtsVoiceTile({
    required this.voiceId,
    required this.pophie,
    required this.onChanged,
  });

  final String voiceId;
  final PophieClient pophie;
  final ValueChanged<String> onChanged;

  @override
  State<_TtsVoiceTile> createState() => _TtsVoiceTileState();
}

class _TtsVoiceTileState extends State<_TtsVoiceTile> {
  @override
  Widget build(BuildContext context) {
    return CupertinoListTile.notched(
      backgroundColor: AppTheme.groupedBackground,
      leading: const _LeadingIcon(CupertinoIcons.waveform, AppTheme.accentPurple),
      title: const Text('TTS 音色', style: TextStyle(color: AppTheme.label)),
      subtitle: Text(
        _displayLabel(widget.voiceId),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: widget.voiceId.isEmpty
              ? AppTheme.tertiaryLabel
              : AppTheme.secondaryLabel,
        ),
      ),
      trailing: const CupertinoListTileChevron(),
      onTap: () => _pickVoice(context),
    );
  }

  /// 根据当前 voiceId 给出展示文本。空 → "默认音色"。
  String _displayLabel(String id) =>
      id.isEmpty ? '默认音色' : id;

  /// 拉取 schema 后弹出底部选择器。拉取期间显示 loading。
  Future<void> _pickVoice(BuildContext context) async {
    List<TtsVoice> voices;
    String? errMsg;
    try {
      final schema = await widget.pophie.fetchSchema();
      voices = schema.ttsVoices;
    } catch (e) {
      errMsg = e.toString();
      voices = const [];
    }
    if (!mounted) return;
    if (!context.mounted) return;
    showCupertinoModalPopup<void>(
      context: context,
      // 用半屏 sheet 让长列表可滚动。
      builder: (ctx) => _VoicePickerSheet(
        voices: voices,
        currentId: widget.voiceId,
        errMsg: errMsg,
        onSelected: (id) {
          widget.onChanged(id);
          Navigator.of(ctx).pop();
        },
      ),
    );
  }
}

/// 音色选择底部弹层:列表展示音色,首项固定为"默认音色"。
class _VoicePickerSheet extends StatelessWidget {
  const _VoicePickerSheet({
    required this.voices,
    required this.currentId,
    required this.errMsg,
    required this.onSelected,
  });

  final List<TtsVoice> voices;
  final String currentId;
  final String? errMsg;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    final height = MediaQuery.of(context).size.height * 0.6;
    return CupertinoPopupSurface(
      isSurfacePainted: true,
      child: SizedBox(
        height: height,
        child: Column(
          children: [
            // 顶部标题栏
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: const BoxDecoration(
                border: Border(
                  bottom: BorderSide(color: AppTheme.separator, width: 0.5),
                ),
              ),
              child: Row(
                children: [
                  const Text('选择 TTS 音色',
                      style: TextStyle(
                          color: AppTheme.label,
                          fontSize: 16,
                          fontWeight: FontWeight.w600)),
                  const Spacer(),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    child: const Text('关闭',
                        style: TextStyle(color: AppTheme.accent, fontSize: 15)),
                  ),
                ],
              ),
            ),
            if (errMsg != null)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                color: const Color(0x33FF453A),
                child: Text('拉取音色列表失败,可选默认音色\n$errMsg',
                    style: const TextStyle(color: Color(0xFFFF9D8A), fontSize: 12)),
              ),
            Expanded(
              child: ListView(
                padding: EdgeInsets.zero,
                children: [
                  // 首项固定为"默认音色"(空 voice_id)
                  _voiceRow('', '默认音色',
                      subtitle: '使用服务端配置的默认音色',
                      isDefault: true),
                  for (final v in voices)
                    _voiceRow(v.id, v.label,
                        subtitle: v.id, isDefault: v.isDefault),
                  if (voices.isEmpty && errMsg == null)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Center(
                        child: Text('服务端未返回音色列表',
                            style: TextStyle(
                                color: AppTheme.tertiaryLabel, fontSize: 13)),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _voiceRow(String id, String label,
      {String? subtitle, bool isDefault = false}) {
    final selected = id == currentId;
    return GestureDetector(
      onTap: () => onSelected(id),
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: const BoxDecoration(
          border: Border(
            bottom: BorderSide(color: AppTheme.separator, width: 0.3),
          ),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(label,
                          style: const TextStyle(
                              color: AppTheme.label, fontSize: 15)),
                      if (isDefault) ...[
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 5, vertical: 1),
                          decoration: BoxDecoration(
                            color: const Color(0x33FFD60A),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Text('默认',
                              style: TextStyle(
                                  color: Color(0xFFFFD60A), fontSize: 10)),
                        ),
                      ],
                    ],
                  ),
                  if (subtitle != null && subtitle.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(subtitle,
                        style: const TextStyle(
                            color: AppTheme.tertiaryLabel, fontSize: 11)),
                  ],
                ],
              ),
            ),
            if (selected)
              const Icon(CupertinoIcons.check_mark,
                  color: AppTheme.accent, size: 20),
          ],
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header(this.text);
  final String text;
  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        color: AppTheme.secondaryLabel,
        fontSize: 13,
        fontWeight: FontWeight.w400,
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(color: AppTheme.tertiaryLabel, fontSize: 12),
    );
  }
}
