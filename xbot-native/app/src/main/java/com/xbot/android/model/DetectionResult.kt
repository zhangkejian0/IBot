package com.xbot.android.model

import android.graphics.RectF

/**
 * 一次人脸采样的结果(用于「认识我」录入)。
 * 对应 Flutter 的 EnrollCapture。
 */
data class EnrollCapture(
    val jpgBytes: ByteArray,
    val embedding: List<Float>? = null
) {
    val hasEmbedding: Boolean get() = embedding != null && embedding.isNotEmpty()
}

/**
 * 身份匹配结果。
 * 对应 Flutter 的 IdentityMatch。
 */
data class IdentityMatch(
    val person: Person,
    val similarity: Float
)

/**
 * 帧检测结果聚合。每帧产生一个 DetectionResult。
 * 直接翻译自 Flutter 的 DetectionResult。
 */
data class DetectionResult(
    val faces: List<FaceOverlay> = emptyList(),
    val hands: List<HandOverlay> = emptyList(),
    val objects: List<ObjectOverlay> = emptyList(),
    val poses: List<PoseOverlay> = emptyList(),
    val mirror: Boolean = false
) {
    /** 主脸(面积最大,且含表情/网格) */
    val face: FaceOverlay? get() = faces.firstOrNull()

    /** 手持物体(置信度最高的 heldByHand) */
    val heldObject: ObjectOverlay?
        get() = objects.filter { it.heldByHand }.maxByOrNull { it.confidence }
}

/**
 * 人脸覆盖层:478 点 + 表情 + 注视 + 身份。
 * 直接翻译自 Flutter 的 FaceOverlay。
 */
data class FaceOverlay(
    val landmarks: List<Offset> = emptyList(),
    val boundingBox: RectF = RectF(),
    val expression: ExpressionResult = ExpressionResult.NEUTRAL,
    val identity: IdentityMatch? = null,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val eyeBlink: Float = 0f,
    val mouthOpenness: Float = 0f
)

/**
 * 手部覆盖层:21 点 + 手势。
 * 直接翻译自 Flutter 的 HandOverlay。
 */
data class HandOverlay(
    val landmarks: List<Offset> = emptyList(),
    val boundingBox: RectF = RectF(),
    val handedness: Handedness? = null,
    val gesture: GestureType? = null,
    val gestureConfidence: Float = 0f
)

/**
 * 物体覆盖层。
 * 直接翻译自 Flutter 的 ObjectOverlay。
 */
data class ObjectOverlay(
    val boundingBox: RectF = RectF(),
    val label: String? = null,
    val confidence: Float = 0f,
    val trackingId: Int? = null,
    val heldByHand: Boolean = false
) {
    val center: Offset
        get() = Offset(
            boundingBox.centerX(),
            boundingBox.centerY()
        )
}

/**
 * 姿态覆盖层:33 点骨骼。
 * 直接翻译自 Flutter 的 PoseOverlay。
 */
data class PoseOverlay(
    val landmarks: List<Offset> = emptyList(),
    val visibilities: List<Float> = emptyList(),
    val boundingBox: RectF = RectF()
)

/**
 * 归一化坐标点(0..1)。
 * 对应 Flutter 的 Offset(归一化空间)。
 */
data class Offset(val x: Float, val y: Float)

/**
 * 左右手。
 */
enum class Handedness { LEFT, RIGHT }

/**
 * 手势类型。
 * 对应 Flutter 的 GestureType(hand_detection 包)。
 */
enum class GestureType(val label: String, val emoji: String) {
    THUMB_UP("点赞", "👍"),
    VICTORY("胜利", "✌️"),
    CLOSED_FIST("握拳", "✊"),
    OPEN_PALM("张开", "🖐️"),
    POINTING_UP("指向上", "☝️"),
    I_LOVE_YOU("爱你", "🤟"),
    THUMB_DOWN("踩", "👎"),
    UNKNOWN("未知", "❓")
}
