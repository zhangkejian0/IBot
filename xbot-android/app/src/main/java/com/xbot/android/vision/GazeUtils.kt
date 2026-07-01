package com.xbot.android.vision

/**
 * 从 MediaPipe 52 blendshapes 派生注视方向 / 眨眼程度（对应 Flutter FaceResultHelper）。
 *
 * Flutter 的 kwon_mediapipe_landmarker 插件用 8 个 eyeLookIn/Out/Up/Down blendshapes
 * 计算 horizontalGazeDirection / verticalGazeDirection。这里按同一组 blendshapes 复现：
 *  - 水平注视：eyeLookOutLeft/Right 推正向（向右），eyeLookInLeft/Right 推负向（向左）
 *  - 垂直注视：eyeLookUp 推负向（向上，因屏幕 y 向下），eyeLookDown 推正向（向下）
 *
 * 返回值范围 -1..1，正=右/下（与 Flutter 一致）。左右眼取均值。
 */
object GazeUtils {
    /**
     * @return FloatArray(2) = [gazeX, gazeY]，范围 -1..1
     */
    fun gazeFromBlendshapes(b: Map<String, Float>): FloatArray {
        fun v(k: String): Float = b[k] ?: 0f

        // 水平：左眼看右 = eyeLookOutLeft，左眼看左 = eyeLookInLeft；
        //       右眼看右 = eyeLookInRight，右眼看左 = eyeLookOutRight。
        val lookOutL = v("eyeLookOutLeft")
        val lookInL = v("eyeLookInLeft")
        val lookOutR = v("eyeLookOutRight")
        val lookInR = v("eyeLookInRight")
        val gazeX = ((lookOutL - lookInL) + (lookInR - lookOutR)) / 2f

        // 垂直：向上 = eyeLookUp，向下 = eyeLookDown（左右眼取均值）。
        val lookUpL = v("eyeLookUpLeft")
        val lookUpR = v("eyeLookUpRight")
        val lookDownL = v("eyeLookDownLeft")
        val lookDownR = v("eyeLookDownRight")
        // 正=下，故 down 减 up。
        val gazeY = ((lookDownL - lookUpL) + (lookDownR - lookUpR)) / 2f

        return floatArrayOf(gazeX.coerceIn(-1f, 1f), gazeY.coerceIn(-1f, 1f))
    }

    /** 眼睛闭合程度（左右 eyeBlink 均值，0..1，1=完全闭眼）。 */
    fun eyeBlinkFromBlendshapes(b: Map<String, Float>): Float {
        val blinkL = (b["eyeBlinkLeft"] ?: 0f).coerceIn(0f, 1f)
        val blinkR = (b["eyeBlinkRight"] ?: 0f).coerceIn(0f, 1f)
        return (blinkL + blinkR) / 2f
    }
}
