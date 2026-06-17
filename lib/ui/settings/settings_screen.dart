import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show MaterialPageRoute;

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
import '../../services/voice/pophie_config.dart';
import '../../theme/app_theme.dart';
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
                      // 仅在语音助手启用时才有意义。
                      onChanged: (s.voiceEnabled &&
                              controller.voiceAssistant.isAvailable)
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
                      icon: CupertinoIcons.arrow_left_right,
                      color: AppTheme.secondaryLabel,
                      label: '前置摄像头镜像',
                      value: s.mirrorFrontCamera,
                      onChanged: (v) => controller
                          .updateSettings(() => s.mirrorFrontCamera = v),
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
            placeholder: '如：狗蛋',
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
            _ConfigTile(
              icon: CupertinoIcons.waveform,
              color: AppTheme.accentPurple,
              label: 'TTS 音色',
              value: cfg.voiceId,
              placeholder: '默认音色',
              editorLabel: 'TTS 音色 (voice_id)',
              editorHint: 'zh-CN-XiaoxiaoNeural',
              onTap: (v) =>
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
