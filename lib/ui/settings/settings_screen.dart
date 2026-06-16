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
