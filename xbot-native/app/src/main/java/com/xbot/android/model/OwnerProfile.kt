package com.xbot.android.model

/**
 * 性别枚举。
 * 直接翻译自 Flutter 的 Gender。
 */
enum class Gender(val label: String, val key: String) {
    MALE("男", "male"),
    FEMALE("女", "female"),
    OTHER("其他", "other");

    companion object {
        fun fromName(name: String?): Gender =
            entries.find { it.key.equals(name, ignoreCase = true) } ?: OTHER
    }
}

/**
 * 主人档案:首次激活向导收集的信息。
 * 直接翻译自 Flutter 的 OwnerProfile。
 */
data class OwnerProfile(
    val nickname: String = "主人",
    val robotName: String = "狗蛋",
    val gender: Gender? = null,
    val birthday: Long? = null,
    val faceRegistered: Boolean = false,
    val personId: String? = null,
    val syncedToServer: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
