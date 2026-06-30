package com.xbot.android.core

/**
 * 集中管理 App 的全部「调参常量」（编译期 const）。
 *
 * 从 Flutter App 的 lib/core/app_tuning.dart 1:1 移植。所有感知/交互相关的经验阈值、
 * 节流间隔、采样参数都在这里一眼可见。
 *
 * 注：原生方案下相机取流+推理全程在后台线程，主线程只做 WebView 合成与 JS 注入，
 * 故不再需要 Flutter 的「按需采样」补丁（sampleInterval），可恢复持续帧流 + KEEP_ONLY_LATEST 丢帧。
 */
object AppTuning {
    // ===========================================================================
    // 注视触发对话（无需唤醒词，持续注视机器人自动发起交互）
    // ===========================================================================
    /** 正视机器人的 gaze 特征点 X（由真实采样校准）。 */
    const val GAZE_CENTER_X = -0.27
    /** 正视机器人的 gaze 特征点 Y。 */
    const val GAZE_CENTER_Y = 0.31
    /** 注视触发圆半径（gaze 到正视特征点的距离 < 此值才算正视）。 */
    const val GAZE_TRIGGER_RADIUS = 0.12
    /** 持续注视多久（秒）后触发对话。 */
    const val GAZE_TRIGGER_SECONDS = 5L
    /** 触发后冷却时长（秒）。 */
    const val GAZE_COOLDOWN_SECONDS = 60L
    /** 容错：短暂出圆不超过此时长（秒）不重置注视计时。 */
    const val GAZE_TOLERANCE_SECONDS = 1L
    /** 人脸需较居中：face 中心 x 在 [0.5 ± faceCenterTolerance] 范围内。 */
    const val FACE_CENTER_TOLERANCE = 0.22

    // ===========================================================================
    // 身份识别与节流
    // ===========================================================================
    /** 身份识别节流间隔（ms）。 */
    const val IDENTITY_INTERVAL_MS = 1200L
    /** 物体检测独立节流（ms）。 */
    const val OBJECT_INTERVAL_MS = 700L

    // ===========================================================================
    // 物体与感知
    // ===========================================================================
    /** 「手持物体」判定：物体框与手框的中心距离阈值（归一化 0..1）。 */
    const val HELD_DISTANCE = 0.22
    /** 发往后端感知的物体置信度下限。 */
    const val PERCEPTION_OBJECT_CONFIDENCE = 0.65
    /** 身份 slot TTL（ms）。 */
    const val IDENTITY_TTL_MS = 3000L
    /** 中心点距离阈值（归一化），超过则视为不同位置的人脸。 */
    const val SLOT_MATCH_DISTANCE = 0.15

    /** 模型/相机加载完成后的固定缓冲（ms）。 */
    const val READY_BUFFER_MS = 2000L
    /** 感知日志最小间隔（ms）。 */
    const val PERCEPTION_MIN_INTERVAL_MS = 2000L

    // ===========================================================================
    // WebView JS 推送
    // ===========================================================================
    /** JS 推送节流（ms，~30fps）。合并 setState+setGazeTarget 为一次 evaluateJavascript。 */
    const val JS_PUSH_INTERVAL_MS = 33L
    /** 同一表情态至少 dwell 多久才允许切换（ms）。 */
    const val FACE_STATE_MIN_DWELL_MS = 600L

    // ===========================================================================
    // 坐标系/旋转
    // ===========================================================================
    /** 前置摄像头是否需水平镜像（Android 取流未镜像，需翻转贴合自拍）。 */
    const val FLIP_FRONT_CAMERA_HORIZONTAL = true
}
