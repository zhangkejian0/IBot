package com.xbot.android.webview

import android.os.Handler
import android.os.Looper
import com.xbot.android.core.AppTuning
import com.xbot.android.model.FaceOverlay

/**
 * 虚拟形象 JS 注入桥：把检测结果 + 语音状态合并成 JS 调用推给前端 window.__face。
 *
 * 对应 Flutter camera_screen.dart 的 _VirtualPetWebViewState._pushAll。
 *
 * 推送规则（与 Flutter 1:1 对齐）：
 * 1. **JS 推送节流**（~30fps）：合并 setState + setGazeTarget 为一次 evaluateJavascript。
 * 2. **语音优先级最高**：活跃时接管表情 + 嘴部（阶段 3 接入，阶段 0 不触发）。
 * 3. **视觉态**：注视（人脸中心→九宫格→格中心 -1/0/1，前置镜像 x 取反）；状态仅注意力态
 *    驱动（drowsy→sleepy, focused→gazing, 余 idle），同一态 dwell 600ms 才切换。
 *
 * 线程：所有 JS 注入必须切到主线程（WebView API 要求）。
 */
class FaceBridge(private val webView: FaceWebView) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val zoneDetector = GazeZoneDetector()

    private var lastPushAt = 0L

    // 上一帧推给前端的状态及其确认时刻（用于 dwell 判断）。
    private var lastSentState: String? = null
    private var lastSentAt = 0L

    // 阶段 3 起接入的语音状态（阶段 0 恒为不活跃）。
    @Volatile
    var voiceActive: Boolean = false
    @Volatile
    var voiceState: String = "idle"     // 后端 robotState 或本地阶段映射
    @Volatile
    var voiceLevel: Float = 0f
    private var lastVoiceActive = false

    /** 页面就绪回调：立即把当前状态推一次，避免首屏停留。 */
    fun onPageLoaded() {
        mainHandler.post {
            lastPushAt = System.currentTimeMillis()
            pushAll(face = null, behaviorState = null)
        }
    }

    /**
     * 接收一帧检测结果（**后台线程回调**）→ 节流 + 切主线程推送。
     * @param face 主脸（无人脸为 null）
     * @param behaviorState 注意力态（focused/drowsy/...，阶段 1 起接入；阶段 0 传 null）
     */
    fun onFrame(face: FaceOverlay?, behaviorState: String?) {
        val now = System.currentTimeMillis()
        if (now - lastPushAt < AppTuning.JS_PUSH_INTERVAL_MS) return
        lastPushAt = now
        mainHandler.post { pushAll(face, behaviorState) }
    }

    private fun pushAll(face: FaceOverlay?, behaviorState: String?) {
        val voiceActiveNow = voiceActive

        // —— 语音刚结束过渡：强制把前端表情切回 idle + 嘴部归零 ——
        val justEnded = lastVoiceActive && !voiceActiveNow
        lastVoiceActive = voiceActiveNow
        if (justEnded) {
            webView.eval("var f=window.__face;if(f){f.setState('idle');f.setListeningLoudness(0);}")
            lastSentState = null
        }

        // —— 语音优先级最高：活跃时接管表情 + 嘴部，跳过视觉情绪态 ——
        if (voiceActiveNow) {
            val fs = voiceState
            val lvl = voiceLevel
            val js = "var f=window.__face;if(f){" +
                (if (fs.isNotEmpty()) "f.setState('$fs');" else "") +
                "f.setListeningLoudness(${"%.3f".format(lvl)});}"
            webView.eval(js)
            return
        }

        // —— 视觉态：注视 + 状态 ——
        val gazeJs = buildGazeJs(face)
        val stateJs = buildStateJs(face, behaviorState)
        if (gazeJs.isEmpty() && stateJs.isEmpty()) return
        // 合并成一次 JS 调用：取出 __face 引用，依次 setState / setGazeTarget。
        webView.eval("var f=window.__face;if(f){$stateJs$gazeJs}")
    }

    /** 注视方向：人脸中心 → 九宫格量化 → 瞳孔极限位置（-1/0/1）。 */
    private fun buildGazeJs(face: FaceOverlay?): String {
        if (face == null) {
            zoneDetector.reset()
            return ""
        }
        // 人脸中心归一化坐标（-1..1，画面中心→0，右→+1，下→+1）
        var x = 2f * face.boundingBox.centerX() - 1f
        val y = 2f * face.boundingBox.centerY() - 1f
        // 前置摄像头水平镜像：贴合自拍视角。
        if (AppTuning.FLIP_FRONT_CAMERA_HORIZONTAL) x = -x
        zoneDetector.update(x, y)
        val zone = zoneDetector.currentZoneCenter ?: return ""
        return "f.setGazeTarget(${zone.first.format(1)},${zone.second.format(1)});"
    }

    /**
     * 状态映射：仅注意力态驱动（阶段 1 起由 BehaviorTracker 给出）。
     * drowsy→sleepy, focused→gazing, 其余→idle。
     * 同一态至少 dwell 600ms 才允许切换。
     */
    private fun buildStateJs(face: FaceOverlay?, behaviorState: String?): String {
        if (face == null || behaviorState == null) {
            if (face == null) lastSentState = null
            return ""
        }
        val state = when (behaviorState) {
            "drowsy" -> "sleepy"
            "focused" -> "gazing"
            else -> "idle"
        }
        val now = System.currentTimeMillis()
        val changed = state != lastSentState
        val dwellOk = lastSentState == null || now - lastSentAt >= AppTuning.FACE_STATE_MIN_DWELL_MS
        if (changed && dwellOk) {
            lastSentState = state
            lastSentAt = now
            return "f.setState('$state');"
        }
        return ""
    }

    private fun Float.format(digits: Int): String = "%.${digits}f".format(this)
}
