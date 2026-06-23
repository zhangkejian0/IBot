import 'package:flutter/foundation.dart';

/// 主人性别（向导采集）。
enum Gender {
  male, // 男
  female, // 女
  other, // 其他
}

extension GenderInfo on Gender {
  String get label {
    switch (this) {
      case Gender.male:
        return '男';
      case Gender.female:
        return '女';
      case Gender.other:
        return '其他';
    }
  }

  /// 序列化为后端 key（文档 §2.8）。
  String get name {
    switch (this) {
      case Gender.male:
        return 'male';
      case Gender.female:
        return 'female';
      case Gender.other:
        return 'other';
    }
  }

  static Gender? fromName(String? name) {
    if (name == null) return null;
    for (final g in Gender.values) {
      if (g.name == name) return g;
    }
    return null;
  }
}

/// 陪伴机器人「主人」档案（首次激活向导采集）。
///
/// 对应后端文档 §2.8 的 `OwnerProfile`：昵称、机器人昵称、性别、生日、
/// 是否已录入人脸。其中**人脸数据绝不上传**（端侧 MobileFaceNet 本地比对），
/// 后端只收 [faceRegistered] 标志位。
///
/// 本地额外字段 [personId]（关联的人脸 Person id）与 [syncedToServer]
///（是否已成功同步到 Pophie）不进入 [toPophieJson]。
@immutable
class OwnerProfile {
  /// 主人昵称/称呼，机器人对主人的个性化称呼。
  final String nickname;

  /// 主人给机器人起的名字（替换默认名「狗蛋」）。
  final String robotName;

  /// 主人性别，可为空（用户选择不填写）。
  final Gender? gender;

  /// 主人生日，可为空（用户选择不填写）。
  final DateTime? birthday;

  /// 端侧是否已录入主人人脸（仅标志位，不传人脸向量）。
  final bool faceRegistered;

  /// 关联的本地主脸 Person id（relation=owner），未录脸时为空。
  final String? personId;

  /// 是否已成功同步到 Pophie 后端。best-effort：本地先生效，后端失败置 false
  /// 并静默重试。
  final bool syncedToServer;

  final DateTime createdAt;

  const OwnerProfile({
    required this.nickname,
    required this.robotName,
    this.gender,
    this.birthday,
    this.faceRegistered = false,
    this.personId,
    this.syncedToServer = false,
    DateTime? createdAt,
  }) : createdAt = createdAt ?? DateTime.now();

  OwnerProfile copyWith({
    String? nickname,
    String? robotName,
    Gender? gender,
    DateTime? birthday,
    bool clearGender = false,
    bool clearBirthday = false,
    bool? faceRegistered,
    String? personId,
    bool? syncedToServer,
  }) {
    return OwnerProfile(
      nickname: nickname ?? this.nickname,
      robotName: robotName ?? this.robotName,
      gender: clearGender ? null : (gender ?? this.gender),
      birthday: clearBirthday ? null : (birthday ?? this.birthday),
      faceRegistered: faceRegistered ?? this.faceRegistered,
      personId: personId ?? this.personId,
      syncedToServer: syncedToServer ?? this.syncedToServer,
      createdAt: createdAt,
    );
  }

  /// 本地完整序列化（含本地字段）。
  Map<String, dynamic> toJson() => {
        'nickname': nickname,
        'robotName': robotName,
        'gender': gender?.name,
        'birthday': birthday?.toIso8601String().split('T').first,
        'faceRegistered': faceRegistered,
        'personId': personId,
        'syncedToServer': syncedToServer,
        'createdAt': createdAt.toIso8601String(),
      };

  factory OwnerProfile.fromJson(Map<String, dynamic> json) {
    final genderName = json['gender'] as String?;
    return OwnerProfile(
      nickname: (json['nickname'] as String?) ?? '主人',
      robotName: (json['robotName'] as String?) ?? '狗蛋',
      gender: GenderInfo.fromName(genderName),
      birthday: _parseDate(json['birthday'] as String?),
      faceRegistered: (json['faceRegistered'] as bool?) ?? false,
      personId: json['personId'] as String?,
      syncedToServer: (json['syncedToServer'] as bool?) ?? false,
      createdAt:
          DateTime.tryParse(json['createdAt'] as String? ?? '') ?? DateTime.now(),
    );
  }

  /// Pophie 上传格式（文档 §2.8）：仅 5 个字段，使用 snake_case，
  /// 不含本地字段（personId / syncedToServer）。
  Map<String, dynamic> toPophieJson() {
    final m = <String, dynamic>{
      'nickname': nickname,
      'robot_name': robotName,
      'face_registered': faceRegistered,
    };
    if (gender != null) m['gender'] = gender!.name;
    if (birthday != null) {
      m['birthday'] = birthday!.toIso8601String().split('T').first;
    }
    return m;
  }

  static DateTime? _parseDate(String? s) {
    if (s == null || s.isEmpty) return null;
    return DateTime.tryParse('${s}T00:00:00');
  }
}
