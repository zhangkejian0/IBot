package com.xbot.android.model

import android.graphics.RectF

/**
 * 一帧聚合的全部识别结果（对应 Flutter DetectionResult）。
 *
 * 所有覆盖层坐标均归一化到 0..1（相对竖直摆正后的画面）。
 * 人脸为列表（[faces]）以支持多人脸：第 0 个为主脸（面积最大、携带 MediaPipe 478 点网格与表情）。
 */
data class DetectionResult(
    val faces: List<FaceOverlay> = emptyList(),
    val hands: List<HandOverlay> = emptyList(),
    val objects: List<ObjectOverlay> = emptyList(),
    val poses: List<PoseOverlay> = emptyList(),
    val mirror: Boolean = false,
) {
    /** 主脸（列表首个，可能为空）。 */
    val face: FaceOverlay? get() = faces.firstOrNull()

    /** 被手持的物体（多个取置信度最高者）。 */
    val heldObject: ObjectOverlay?
        get() = objects.filter { it.heldByHand }.maxByOrNull { it.confidence }
}

/**
 * 人脸覆盖层数据。坐标归一化到 0..1。
 *
 * 注视方向 gazeX/gazeY（-1..1，正=右/下）为检测原始值，未做前置镜像。
 * 仅主脸（MediaPipe）携带 landmarks/expression/gaze/eyeBlink/mouthOpenness。
 */
data class FaceOverlay(
    val landmarks: List<NormalizedPoint> = emptyList(),
    val boundingBox: RectF,
    val expression: ExpressionResult = ExpressionResult.NEUTRAL,
    val identity: IdentityMatch? = null,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val eyeBlink: Float = 0f,
    val mouthOpenness: Float = 0f,
)

/** 归一化点（0..1）。 */
data class NormalizedPoint(val x: Float, val y: Float)

/** 身份识别命中结果。 */
data class IdentityMatch(
    val person: Person,
    val similarity: Float,
)
