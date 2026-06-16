/// 与主人的家庭关系。
enum FamilyRelation {
  owner, // 主人本人
  spouse, // 配偶
  father, // 父亲
  mother, // 母亲
  son, // 儿子
  daughter, // 女儿
  brother, // 兄弟
  sister, // 姐妹
  grandfather, // 祖父/外祖父
  grandmother, // 祖母/外祖母
  friend, // 朋友
  other, // 其他
}

extension FamilyRelationInfo on FamilyRelation {
  String get label {
    switch (this) {
      case FamilyRelation.owner:
        return '主人';
      case FamilyRelation.spouse:
        return '配偶';
      case FamilyRelation.father:
        return '父亲';
      case FamilyRelation.mother:
        return '母亲';
      case FamilyRelation.son:
        return '儿子';
      case FamilyRelation.daughter:
        return '女儿';
      case FamilyRelation.brother:
        return '兄弟';
      case FamilyRelation.sister:
        return '姐妹';
      case FamilyRelation.grandfather:
        return '祖父';
      case FamilyRelation.grandmother:
        return '祖母';
      case FamilyRelation.friend:
        return '朋友';
      case FamilyRelation.other:
        return '其他';
    }
  }

  static FamilyRelation fromName(String? name) {
    return FamilyRelation.values.firstWhere(
      (e) => e.name == name,
      orElse: () => FamilyRelation.other,
    );
  }
}

/// 已录入的人物身份。
///
/// 一个人物可以包含多张录入照片对应的多个特征向量（embeddings），
/// 比对时取与任一向量的最高相似度。
class Person {
  final String id;
  String name;
  FamilyRelation relation;

  /// 多张录入样本的人脸特征向量。
  List<List<double>> embeddings;

  /// 头像缩略图（裁剪后的人脸）文件路径，可为空。
  String? avatarPath;

  final DateTime createdAt;

  Person({
    required this.id,
    required this.name,
    required this.relation,
    List<List<double>>? embeddings,
    this.avatarPath,
    DateTime? createdAt,
  })  : embeddings = embeddings ?? <List<double>>[],
        createdAt = createdAt ?? DateTime.now();

  int get sampleCount => embeddings.length;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'relation': relation.name,
        'embeddings': embeddings,
        'avatarPath': avatarPath,
        'createdAt': createdAt.toIso8601String(),
      };

  factory Person.fromJson(Map<String, dynamic> json) {
    final rawEmbeddings = (json['embeddings'] as List<dynamic>? ?? []);
    return Person(
      id: json['id'] as String,
      name: json['name'] as String? ?? '未命名',
      relation: FamilyRelationInfo.fromName(json['relation'] as String?),
      embeddings: rawEmbeddings
          .map<List<double>>((e) => (e as List<dynamic>)
              .map<double>((v) => (v as num).toDouble())
              .toList())
          .toList(),
      avatarPath: json['avatarPath'] as String?,
      createdAt:
          DateTime.tryParse(json['createdAt'] as String? ?? '') ?? DateTime.now(),
    );
  }
}

/// 身份识别命中结果。
class IdentityMatch {
  final Person person;
  final double similarity;

  const IdentityMatch({required this.person, required this.similarity});
}
