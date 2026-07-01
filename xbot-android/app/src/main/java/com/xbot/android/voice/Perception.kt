package com.xbot.android.voice

import android.graphics.RectF
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.Expression
import com.xbot.android.model.HandGesture
import org.json.JSONArray
import org.json.JSONObject

/**
 * 由当前检测结果构造 Pophie 感知上下文（表情/身份/手势/物体/场景）。
 * 对应 Flutter AppController._buildPerception + PophiePerception。
 *
 * 上传给大模型，让它「看得见」当前画面：谁在场、什么表情、拿着什么、在哪儿。
 * 仅携带非空字段；物体置信度 < [CONF_THRESHOLD] 不发送。
 */
object Perception {

    private const val CONF_THRESHOLD = 0.65

    /** 表情枚举 → 后端 7 类 key。 */
    private fun exprKey(e: Expression): String = when (e) {
        Expression.NEUTRAL -> "neutral"
        Expression.HAPPY -> "happy"
        Expression.SAD -> "sad"
        Expression.SURPRISED -> "surprise"
        Expression.ANGRY -> "angry"
        Expression.DISGUSTED -> "disgust"
        Expression.FEARFUL -> "fear"
    }

    /** 手势枚举 → 后端 key。 */
    private fun gestureKey(g: HandGesture?): String? = when (g) {
        HandGesture.THUMB_UP -> "thumbs_up"
        HandGesture.VICTORY -> "victory"
        HandGesture.CLOSED_FIST -> "fist"
        HandGesture.OPEN_PALM -> "open_palm"
        HandGesture.POINTING_UP -> "point"
        HandGesture.I_LOVE_YOU -> "heart"
        HandGesture.THUMB_DOWN, HandGesture.UNKNOWN, null -> null
    }

    /** 归一化中心点 → 中文位置描述（左/中/右·上/中/下）。 */
    private fun locationLabel(cx: Float, cy: Float): String {
        val h = if (cx < 0.34f) "左" else if (cx > 0.66f) "右" else "中"
        val v = if (cy < 0.34f) "上" else if (cy > 0.66f) "下" else "中"
        return if (h == "中" && v == "中") "画面中央" else "画面${v}${if (h == "中") "" else h}方"
    }

    /**
     * 构造感知上下文 JSON（仅非空字段）。
     * @param result 当前检测结果
     * @param mirror 前置镜像（影响左右位置描述）
     * @param speaker 声纹识别出的说话人姓名（可选）。人脸身份优先；无人脸时用声纹填 identity。
     */
    fun build(result: DetectionResult, mirror: Boolean, speaker: String? = null): JSONObject {
        val m = JSONObject()
        val face = result.face
        // 表情。
        face?.expression?.expression?.let { e ->
            m.put("facial_expression", exprKey(e))
        }
        // 身份：人脸优先（视觉身份更可靠），无人脸时回退声纹。
        val faceName = face?.identity?.person?.name?.takeIf { it.isNotEmpty() }
        val identity = faceName ?: speaker?.takeIf { it.isNotEmpty() }
        identity?.let { m.put("identity", it) }
        // 手势。
        gestureKey(result.hands.firstOrNull()?.gesture)?.let { g ->
            m.put("gesture", JSONObject().put("type", g))
        }
        // 物体（过滤低置信度）。
        val objects = result.objects.filter { it.confidence >= CONF_THRESHOLD }
        val names = mutableListOf<String>()
        val details = JSONArray()
        for (o in objects) {
            val label = o.label ?: continue
            if (label.isEmpty()) continue
            if (names.none { it == label }) names.add(label)
            val cx = if (mirror) 1f - o.center.x else o.center.x
            details.put(JSONObject().apply {
                put("name", label)
                put("confidence", (o.confidence * 100).toInt() / 100.0)
                put("location", locationLabel(cx, o.center.y))
                val b = if (mirror) RectF(1f - o.boundingBox.right, o.boundingBox.top, 1f - o.boundingBox.left, o.boundingBox.bottom) else o.boundingBox
                put("box", JSONArray().apply {
                    put((b.left * 1000).toInt() / 1000.0)
                    put((b.top * 1000).toInt() / 1000.0)
                    put((b.right * 1000).toInt() / 1000.0)
                    put((b.bottom * 1000).toInt() / 1000.0)
                })
            })
        }
        if (names.isNotEmpty()) m.put("objects", JSONArray(names))
        if (details.length() > 0) m.put("objects_detail", details)
        // 手持物体。
        val held = objects.filter { it.heldByHand }.maxByOrNull { it.confidence }?.label
        if (!held.isNullOrEmpty()) m.put("held_object", held)
        // 场景描述（自然语言）。
        buildSceneDescription(result, mirror, identity)?.let { m.put("scene", it) }
        return m
    }

    /** 拼装自然语言场景描述，利于大模型直接理解。 */
    private fun buildSceneDescription(result: DetectionResult, mirror: Boolean, identity: String?): String? {
        val who = identity ?: "用户"
        val parts = mutableListOf<String>()
        // 手持。
        val held = result.objects.filter { it.heldByHand && it.confidence >= CONF_THRESHOLD }
            .maxByOrNull { it.confidence }
        if (!held?.label.isNullOrEmpty()) {
            val cx = if (mirror) 1f - held!!.center.x else held.center.x
            parts.add("${who}手里拿着${held.label}（位于${locationLabel(cx, held.center.y)}）")
        }
        // 其余可见物体（去重，排除手持物本身）。
        val seen = mutableSetOf<String>()
        held?.label?.let { seen.add(it) }
        val others = mutableListOf<String>()
        for (o in result.objects.filter { it.confidence >= CONF_THRESHOLD }) {
            val l = o.label ?: continue
            if (l.isEmpty() || seen.contains(l)) continue
            seen.add(l)
            val cx = if (mirror) 1f - o.center.x else o.center.x
            others.add("$l（${locationLabel(cx, o.center.y)}）")
        }
        if (others.isNotEmpty()) {
            parts.add("${if (held != null) "画面中还能看到" else "画面中可见"}：${others.joinToString("、")}")
        }
        return if (parts.isEmpty()) null else parts.joinToString("；")
    }
}
