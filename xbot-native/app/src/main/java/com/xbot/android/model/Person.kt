package com.xbot.android.model

/**
 * 亲属关系枚举。
 * 直接翻译自 Flutter 的 FamilyRelation。
 */
enum class FamilyRelation(val label: String) {
    OWNER("主人"),
    SPOUSE("配偶"),
    FATHER("父亲"),
    MOTHER("母亲"),
    SON("儿子"),
    DAUGHTER("女儿"),
    BROTHER("兄弟"),
    SISTER("姐妹"),
    GRANDFATHER("爷爷"),
    GRANDMOTHER("奶奶"),
    FRIEND("朋友"),
    OTHER("其他");

    companion object {
        fun fromName(name: String?): FamilyRelation =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

/**
 * 人物模型:已录入的人物(含人脸 embedding 向量)。
 * 直接翻译自 Flutter 的 Person。
 */
data class Person(
    val id: String,
    val name: String,
    val relation: FamilyRelation = FamilyRelation.OTHER,
    val embeddings: MutableList<List<Float>> = mutableListOf(),
    val avatarPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val sampleCount: Int get() = embeddings.size
}
