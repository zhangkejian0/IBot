package com.xbot.android.vision

import android.graphics.RectF
import com.xbot.android.core.AppTuning
import com.xbot.android.model.IdentityMatch
import com.xbot.android.model.Person

/**
 * 位置身份槽：多人脸场景按归一化中心点维持稳定身份，避免逐帧串脸。
 * 对应 Flutter AppController 的 _IdentitySlot / _assignSlots / _matchSlotsToBoxes。
 *
 * 识别帧用独占贪心最近邻匹配把识别结果绑定到 slot；非识别帧按位置续身份（TTL 内有效）。
 */
class IdentitySlotTracker {

    private data class Slot(
        var centerX: Float,
        var centerY: Float,
        var identity: IdentityMatch?,
        var lastSeen: Long,
    )

    private val slots = ArrayList<Slot>()

    /**
     * 识别帧：把本帧各脸的识别结果绑定到 slot（贪心最近邻，独占）。
     * @param frameIdentities 本帧每张脸的（人脸框 → 识别结果或 null）
     */
    fun assignSlots(frameIdentities: Map<RectF, IdentityMatch?>, now: Long) {
        // 清理过期 slot。
        slots.removeAll { now - it.lastSeen > AppTuning.IDENTITY_TTL_MS }
        // 候选配对：(距离, 脸框, slot)，仅保留距离 < 阈值者。
        data class Pair(val dist: Float, val box: RectF, val slot: Slot)
        val pairs = ArrayList<Pair>()
        for ((box, _) in frameIdentities) {
            val c = box.center()
            for (s in slots) {
                val d = dist(s.centerX, s.centerY, c.first, c.second)
                if (d < AppTuning.SLOT_MATCH_DISTANCE) pairs.add(Pair(d, box, s))
            }
        }
        pairs.sortBy { it.dist }
        val usedBoxes = HashSet<RectF>()
        val usedSlots = HashSet<Slot>()
        for (p in pairs) {
            if (usedBoxes.contains(p.box) || usedSlots.contains(p.slot)) continue
            usedBoxes.add(p.box)
            usedSlots.add(p.slot)
            p.slot.centerX = p.box.center().first
            p.slot.centerY = p.box.center().second
            p.slot.lastSeen = now
            val match = frameIdentities[p.box]
            if (match != null) p.slot.identity = match
        }
        // 未匹配的脸：新建 slot。
        for ((box, match) in frameIdentities) {
            if (usedBoxes.contains(box)) continue
            val c = box.center()
            slots.add(Slot(c.first, c.second, match, now))
        }
    }

    /**
     * 非识别帧：把本帧各脸框匹配到 TTL 内的 slot（独占），返回与 [boxes] 顺序对应的 slot 身份列表。
     */
    fun matchSlotsToBoxes(boxes: List<RectF>, now: Long): List<IdentityMatch?> {
        data class Pair(val dist: Float, val faceIndex: Int, val slot: Slot)
        val pairs = ArrayList<Pair>()
        for ((i, box) in boxes.withIndex()) {
            val c = box.center()
            for (s in slots) {
                if (now - s.lastSeen > AppTuning.IDENTITY_TTL_MS) continue
                val d = dist(s.centerX, s.centerY, c.first, c.second)
                if (d < AppTuning.SLOT_MATCH_DISTANCE) pairs.add(Pair(d, i, s))
            }
        }
        pairs.sortBy { it.dist }
        val result = ArrayList<IdentityMatch?>(boxes.size)
        for (i in boxes.indices) result.add(null)
        val usedSlots = HashSet<Slot>()
        for (p in pairs) {
            if (result[p.faceIndex] != null || usedSlots.contains(p.slot)) continue
            result[p.faceIndex] = p.slot.identity
            usedSlots.add(p.slot)
        }
        return result
    }

    fun clear() = slots.clear()

    private fun RectF.center(): Pair<Float, Float> = Pair(centerX(), centerY())
    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()).toFloat()
}
