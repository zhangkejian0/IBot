package com.xbot.android.model

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * 已录入的人物身份（对应 Flutter Person）。
 * 一个人物可含多张录入样本对应的多个特征向量，比对时取与任一向量的最高相似度。
 */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val relation: FamilyRelation = FamilyRelation.OTHER,
    val embeddings: MutableList<List<Float>> = mutableListOf(),
    var avatarPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val sampleCount: Int get() = embeddings.size
}

/** 与主人的家庭关系。 */
@Serializable
enum class FamilyRelation(val label: String) {
    OWNER("主人"),
    SPOUSE("配偶"),
    FATHER("父亲"),
    MOTHER("母亲"),
    SON("儿子"),
    DAUGHTER("女儿"),
    BROTHER("兄弟"),
    SISTER("姐妹"),
    GRANDFATHER("祖父"),
    GRANDMOTHER("祖母"),
    FRIEND("朋友"),
    OTHER("其他");
}

/** 单只手的覆盖层绘制数据（21 点）。坐标归一化到 0..1。 */
data class HandOverlay(
    val landmarks: List<NormalizedPoint> = List(21) { NormalizedPoint(0f, 0f) },
    val boundingBox: RectF,
    val handedness: Handedness? = null,
    val gesture: HandGesture? = null,
    val gestureConfidence: Float = 0f,
)

/** 左右手。 */
enum class Handedness { LEFT, RIGHT }

/** 7 种手势（对应 Flutter hand_detection GestureType）。 */
enum class HandGesture(val apiName: String, val label: String) {
    THUMB_UP("thumbs_up", "点赞"),
    VICTORY("victory", "胜利"),
    CLOSED_FIST("fist", "握拳"),
    OPEN_PALM("open_palm", "张开"),
    POINTING_UP("point", "指向"),
    I_LOVE_YOU("heart", "比心"),
    THUMB_DOWN("thumb_down", "踩"),
    UNKNOWN("unknown", "未知");
}

/** 单个被识别物体（坐标归一化 0..1）。 */
data class ObjectOverlay(
    val boundingBox: RectF,
    val label: String? = null,
    val confidence: Float = 0f,
    val trackingId: Int? = null,
    val heldByHand: Boolean = false,
) {
    /** 归一化中心点。 */
    val center: NormalizedPoint
        get() = NormalizedPoint(boundingBox.centerX(), boundingBox.centerY())
}

/** 单个人体覆盖层（33 关键点）。坐标归一化 0..1。 */
data class PoseOverlay(
    val landmarks: List<NormalizedPoint> = List(33) { NormalizedPoint(0f, 0f) },
    val visibilities: List<Float> = List(33) { 0f },
    val boundingBox: RectF,
)
