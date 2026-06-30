package com.xbot.android.core

/**
 * 集中管理所有调参常量(编译期 const)。
 * 直接翻译自 Flutter 的 [AppTuning]，保持参数一致。
 */
object AppTuning {

    // ===========================================================================
    // 注视触发对话
    // ===========================================================================

    /** 正视机器人的 gaze 特征点 X */
    const val GAZE_CENTER_X = -0.27f

    /** 正视机器人的 gaze 特征点 Y */
    const val GAZE_CENTER_Y = 0.31f

    /** 注视触发圆半径 */
    const val GAZE_TRIGGER_RADIUS = 0.10f

    /** 持续注视多久(秒)后触发对话 */
    const val GAZE_TRIGGER_SECONDS = 8

    /** 触发后冷却时长(秒) */
    const val GAZE_COOLDOWN_SECONDS = 60

    /** 容错:短暂出圆不超过此时长(秒)不重置 */
    const val GAZE_TOLERANCE_SECONDS = 1

    /** 人脸需较居中:face 中心 x 在 [0.5 ± 此值] 范围内 */
    const val FACE_CENTER_TOLERANCE = 0.22f

    // ===========================================================================
    // 身份识别与采样节流
    // ===========================================================================

    /** 身份识别节流间隔(毫秒) */
    const val IDENTITY_INTERVAL_MS = 1200L

    /** 物体检测独立节流间隔(毫秒) */
    const val OBJECT_INTERVAL_MS = 700L

    /** 身份 slot TTL(毫秒) */
    const val IDENTITY_TTL_MS = 3000L

    /** 中心点距离阈值(归一化空间,0..1) */
    const val SLOT_MATCH_DISTANCE = 0.15f

    // ===========================================================================
    // 物体与感知
    // ===========================================================================

    /** 「手持物体」判定:物体框与手框的中心距离阈值 */
    const val HELD_DISTANCE = 0.22f

    /** 发往后端感知的物体置信度下限 */
    const val PERCEPTION_OBJECT_CONFIDENCE = 0.65f

    /** 感知日志最小间隔(毫秒) */
    const val PERCEPTION_MIN_INTERVAL_MS = 2000L

    /** 模型/相机加载完成后的固定缓冲(毫秒) */
    const val READY_BUFFER_MS = 2000L

    // ===========================================================================
    // 相机
    // ===========================================================================

    /** 取流分辨率:medium(480p) */
    const val CAPTURE_WIDTH = 640
    const val CAPTURE_HEIGHT = 480

    /** 采集帧率:2fps(持续流 + 帧丢弃,避免 session 重建) */
    const val CAPTURE_FPS = 2
}
