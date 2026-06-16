import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/app_controller.dart';
import '../../models/person.dart';

/// 已录入朋友列表的 iOS 风格底部弹层。
///
/// 样式参照 X-Bot_temp 的 _showFriendsList：圆角卡片 + 顶部抓手 + 标题 +
/// 列表（头像/姓名/关系 + 删除）。删除带 CupertinoAlertDialog 确认。
void showFriendListSheet(BuildContext context, AppController controller) {
  showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (ctx) {
      // ===== iOS 调色板（与录入页一致）=====
      const card = Color(0xFF1C1C1E);
      const separator = Color(0xFF38383A);
      const label = Color(0xFF8E8E93);
      const blue = Color(0xFF0A84FF);
      const red = Color(0xFFFF453A);

      return StatefulBuilder(
        builder: (ctx, setSheetState) {
          final people = controller.personRepository.people;
          return Container(
            margin: const EdgeInsets.all(10),
            constraints: BoxConstraints(
              maxHeight: MediaQuery.of(ctx).size.height * 0.85,
            ),
            decoration: BoxDecoration(
              color: card,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // 顶部抓手。
                Container(
                  width: 36,
                  height: 5,
                  margin: const EdgeInsets.only(top: 8, bottom: 4),
                  decoration: BoxDecoration(
                    color: separator,
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 8, 18, 12),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        '我的朋友',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text('${people.length} 位',
                          style:
                              const TextStyle(color: label, fontSize: 14)),
                    ],
                  ),
                ),
                const Divider(
                    height: 0.5, thickness: 0.5, color: separator),
                Flexible(
                  child: people.isEmpty
                      ? const Padding(
                          padding: EdgeInsets.symmetric(vertical: 48),
                          child: Text('还没有认识的朋友',
                              style:
                                  TextStyle(color: label, fontSize: 15)),
                        )
                      : ListView.separated(
                          shrinkWrap: true,
                          padding: const EdgeInsets.symmetric(vertical: 4),
                          itemCount: people.length,
                          separatorBuilder: (context, index) =>
                              const Padding(
                            padding: EdgeInsets.only(left: 70),
                            child: Divider(
                                height: 0.5,
                                thickness: 0.5,
                                color: separator),
                          ),
                          itemBuilder: (ctx, index) {
                            final person = people[index];
                            return Padding(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 18, vertical: 8),
                              child: Row(
                                children: [
                                  _Avatar(person: person, blue: blue),
                                  const SizedBox(width: 14),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Text(person.name,
                                            style: const TextStyle(
                                                color: Colors.white,
                                                fontSize: 16)),
                                        const SizedBox(height: 2),
                                        Text(
                                          '${person.relation.label} · ${person.sampleCount} 个样本',
                                          style: const TextStyle(
                                              color: label, fontSize: 13),
                                        ),
                                      ],
                                    ),
                                  ),
                                  CupertinoButton(
                                    padding: EdgeInsets.zero,
                                    onPressed: () async {
                                      final deleted = await _confirmDelete(
                                          context, controller, person);
                                      if (deleted) setSheetState(() {});
                                    },
                                    child: const Icon(
                                      CupertinoIcons.delete,
                                      color: red,
                                      size: 22,
                                    ),
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                ),
                SizedBox(
                    height: MediaQuery.of(ctx).padding.bottom + 8),
              ],
            ),
          );
        },
      );
    },
  );
}

/// 头像：有图片文件用图片，否则取姓名首字的蓝色圆形头像。
class _Avatar extends StatelessWidget {
  const _Avatar({required this.person, required this.blue});
  final Person person;
  final Color blue;

  @override
  Widget build(BuildContext context) {
    final avatar = person.avatarPath;
    if (avatar != null && File(avatar).existsSync()) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(20),
        child: Image.file(File(avatar),
            width: 40, height: 40, fit: BoxFit.cover),
      );
    }
    return CircleAvatar(
      radius: 20,
      backgroundColor: blue,
      child: Text(
        person.name.isNotEmpty ? person.name.characters.first : '?',
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// 删除确认弹窗；确认后执行删除并 toast，返回是否执行了删除。
Future<bool> _confirmDelete(
  BuildContext context,
  AppController controller,
  Person person,
) async {
  final confirmed = await showCupertinoDialog<bool>(
    context: context,
    builder: (ctx) => CupertinoAlertDialog(
      title: const Text('确认删除'),
      content: Text('确定要忘记「${person.name}」吗？'),
      actions: [
        CupertinoDialogAction(
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('取消'),
        ),
        CupertinoDialogAction(
          isDestructiveAction: true,
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('忘记'),
        ),
      ],
    ),
  );

  if (confirmed == true) {
    await controller.deletePerson(person.id);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已经忘记 ${person.name} 了'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
    return true;
  }
  return false;
}
