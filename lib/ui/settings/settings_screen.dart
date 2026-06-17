import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show MaterialPageRoute;

import '../../core/app_controller.dart';
import '../../core/app_scope.dart';
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

                // AI 服务(LLM)配置分组:DeepSeek 预设,可编辑切换任意 OpenAI 兼容端点。
                _LlmConfigSection(controller: controller),

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

/// AI 服务(LLM)配置分组。预设 DeepSeek,每一项点击可编辑,
/// 保存后持久化并实时下发到对话服务。订阅 controller 以反映最新值。
class _LlmConfigSection extends StatelessWidget {
  const _LlmConfigSection({required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final cfg = controller.llmConfigStore.config;
        final connected = controller.voiceAssistant.chat.isAvailable;
        return CupertinoListSection.insetGrouped(
          backgroundColor: AppTheme.background,
          decoration: const BoxDecoration(
            color: AppTheme.groupedBackground,
            borderRadius: BorderRadius.all(Radius.circular(12)),
          ),
          header: const _Header('AI 服务'),
          footer: _Footer(connected
              ? '已配置 ${cfg.model},可语音对话。点击各项可修改,适用于任何 OpenAI 兼容端点。'
              : '尚未配置有效 API Key,语音对话不可用。点击「API Key」录入。'),
          children: [
            _ConfigTile(
              icon: CupertinoIcons.link,
              color: AppTheme.accent,
              label: '服务地址',
              value: cfg.baseUrl,
              placeholder: 'https://api.deepseek.com',
              editorLabel: '服务地址 (Base URL)',
              editorHint: 'https://api.deepseek.com',
              onTap: (v) => controller.updateLlmConfig(cfg.copyWith(baseUrl: v)),
            ),
            _ConfigTile(
              icon: CupertinoIcons.lock_fill,
              color: AppTheme.accentOrange,
              label: 'API Key',
              value: _maskKey(cfg.apiKey),
              placeholder: '未设置',
              editorLabel: 'API Key (Bearer)',
              editorHint: 'sk-...',
              // 编辑时回填明文原值(非脱敏值)。
              editorInitial: cfg.apiKey,
              onTap: (v) => controller.updateLlmConfig(cfg.copyWith(apiKey: v)),
            ),
            _ConfigTile(
              icon: CupertinoIcons.cube_box_fill,
              color: AppTheme.accentPurple,
              label: '模型',
              value: cfg.model,
              placeholder: 'deepseek-chat',
              editorLabel: '模型名称',
              editorHint: 'deepseek-chat',
              onTap: (v) => controller.updateLlmConfig(cfg.copyWith(model: v)),
            ),
          ],
        );
      },
    );
  }

  /// API Key 脱敏:只显示前 6 位 + 末 4 位,中间用 ··· 代替。
  String _maskKey(String key) {
    if (key.isEmpty) return '';
    if (key.length <= 10) return '···';
    return '${key.substring(0, 6)}···${key.substring(key.length - 4)}';
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
    this.editorInitial,
  });

  final IconData icon;
  final Color color;
  final String label;
  final String value;
  final String placeholder;
  final String editorLabel;
  final String editorHint;
  final ValueChanged<String> onTap;
  /// 编辑弹窗里的初始文本(默认取 [value];API Key 用明文原值而非脱敏值)。
  final String? editorInitial;

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
    final tec = TextEditingController(text: editorInitial ?? value);
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
