package com.xbot.android.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主人性别（向导采集）。对应 Flutter Gender。
 */
@Serializable
enum class Gender(val apiName: String, val label: String) {
    MALE("male", "男"),
    FEMALE("female", "女"),
    OTHER("other", "其他");

    companion object {
        fun fromApiName(name: String?): Gender? = entries.firstOrNull { it.apiName == name }
    }
}

/**
 * 陪伴机器人「主人」档案（首次激活向导采集）。对应 Flutter OwnerProfile。
 *
 * 人脸数据绝不上传（端侧 MobileFaceNet 本地比对），后端只收 [faceRegistered] 标志位。
 * 本地额外字段 [personId]（关联的人脸 Person id）与 [syncedToServer] 不进入上传格式。
 */
@Serializable
data class OwnerProfile(
    val nickname: String,
    val robotName: String,
    val gender: Gender? = null,
    /** ISO yyyy-MM-dd 或 null。 */
    val birthday: String? = null,
    val faceRegistered: Boolean = false,
    val personId: String? = null,
    val syncedToServer: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun fromJson(raw: String): OwnerProfile = json.decodeFromString(serializer(), raw)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Pophie 上传格式：仅 5 字段，snake_case，不含本地字段（阶段3 语音助手用到）。 */
    fun toPophieJson(): String {
        val parts = ArrayList<String>()
        parts += "\"nickname\":\"$nickname\""
        parts += "\"robot_name\":\"$robotName\""
        parts += "\"face_registered\":$faceRegistered"
        if (gender != null) parts += "\"gender\":\"${gender.apiName}\""
        if (birthday != null) parts += "\"birthday\":\"$birthday\""
        return "{${parts.joinToString(",")}}"
    }
}
