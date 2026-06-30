package com.xbot.android.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.xbot.android.core.AppTuning
import com.xbot.android.model.DetectionResult
import com.xbot.android.model.FaceOverlay
import com.xbot.android.model.IdentityMatch
import com.xbot.android.model.ObjectOverlay
import com.xbot.android.model.Person
import kotlinx.coroutines.runBlocking
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 视觉管线：协调全部识别引擎，把一帧 bitmap 聚合成 [DetectionResult]。
 *
 * 对应 Flutter AppController._processFrame。在后台线程调用（CameraX analysis executor）。
 *
 * 职责：
 * 1. 并行跑 face(MediaPipe 478+blendshape) + hand(GestureRecognizer) + pose(PoseLandmarker)；
 *    多脸框(ML Kit)按 IoU 嫁接到 MediaPipe 主脸。
 * 2. 身份识别节流（每 [AppTuning.IDENTITY_INTERVAL_MS]）+ 身份槽续身份。
 * 3. 物体检测独立节流（每 [AppTuning.OBJECT_INTERVAL_MS]），节流间复用上一帧 objects。
 * 4. 手持物体空间叠加（物体框与手框重叠/贴近 → heldByHand）。
 *
 * @param peopleProvider 当前人物库（身份识别用）
 */
class VisionPipeline(
    private val faceEngine: FaceLandmarkEngine,
    private val mlkitEngine: MlKitFaceEngine,
    private val handEngine: GestureEngine,
    private val poseEngine: PoseLandmarkEngine?,
    private val objectEngine: ObjectEngine?,
    private val faceRecognizer: FaceRecognizer?,
    private val peopleProvider: () -> List<Person>,
) {
    private val slotTracker = IdentitySlotTracker()

    // —— 节流状态 ——
    private var lastIdentityRun = 0L
    private var lastObjectRun = 0L
    @Volatile
    private var lastObjects: List<ObjectOverlay> = emptyList()
    @Volatile
    private var objectBusy = false

    /** 物体识别是否在后台跑（避免上一帧未完成又触发堆积）。 */
    val isObjectBusy: Boolean get() = objectBusy

    /**
     * 处理一帧（后台线程）。返回聚合结果。
     *
     * @param now 当前时间（ms）
     * @param objectEnabled 是否启用物体识别
     */
    fun process(
        bitmap: Bitmap,
        now: Long,
        objectEnabled: Boolean,
        faceEnabled: Boolean = true,
        handEnabled: Boolean = true,
        identityEnabled: Boolean = true,
    ): DetectionResult {
        // —— 1. 主脸（MediaPipe：478 点 + blendshape → 表情/gaze/eyeBlink/mouthOpenness）——
        // faceEnabled 关闭仅停 MediaPipe 表情/网格；ML Kit 框仍保留以驱动虚拟形象注视。
        val mediapipeFace = if (faceEnabled) faceEngine.detect(bitmap) else null

        // —— 2. 多脸框（ML Kit）——
        val mlkitBoxes = runBlocking { mlkitEngine.detectBoxes(bitmap) }

        // —— 3. 手势（GestureRecognizer：21 点 + 手势）——
        val hands = if (handEnabled) handEngine.detect(bitmap) else emptyList()

        // —— 4. 姿态（PoseLandmarker：33 点）——
        val poses = poseEngine?.detect(bitmap)?.let { listOf(it) } ?: emptyList()

        // —— 5. 多脸列表：ML Kit 框为主，MediaPipe 主脸按 IoU 嫁接 ——
        val faces = ArrayList<FaceOverlay>()
        if (mlkitBoxes.isNotEmpty()) {
            for (box in mlkitBoxes) {
                faces.add(
                    FaceOverlay(
                        landmarks = emptyList(),
                        boundingBox = box,
                    )
                )
            }
            if (mediapipeFace != null) {
                // 选 IoU 最高的脸嫁接网格与表情。
                var bestIdx = -1
                var bestIou = 0f
                for (i in faces.indices) {
                    val iou = iou(faces[i].boundingBox, mediapipeFace.boundingBox)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestIdx = i
                    }
                }
                if (bestIdx >= 0) {
                    // 以 MediaPipe 主脸（关键点空间一致）覆盖该框，网格更贴合。
                    faces[bestIdx] = mediapipeFace
                }
            }
        } else if (mediapipeFace != null) {
            // ML Kit 漏检但 MediaPipe 命中：降级单脸。
            faces.add(mediapipeFace)
        }

        // —— 6. 身份识别（节流）+ 录入采样 + slot 绑定 ——
        val identityDue = identityEnabled && faceRecognizer != null && faceRecognizer.isAvailable &&
            now - lastIdentityRun >= AppTuning.IDENTITY_INTERVAL_MS
        if (faces.isNotEmpty() && identityDue) {
            lastIdentityRun = now
            val frameIdentities = LinkedHashMap<RectF, IdentityMatch?>()
            for (f in faces) {
                val crop = ImageUtils.cropNormalized(bitmap, f.boundingBox)
                val embedding = faceRecognizer!!.embed(crop)
                val match = embedding?.let { faceRecognizer.identify(it, peopleProvider()) }
                frameIdentities[f.boundingBox] = match
            }
            slotTracker.assignSlots(frameIdentities, now)
        }

        // 附着 slot 身份（TTL 内有效）。
        val slotForFace = slotTracker.matchSlotsToBoxes(faces.map { it.boundingBox }, now)
        val resultFaces = ArrayList<FaceOverlay>(faces.size)
        for (i in faces.indices) {
            val f = faces[i]
            val identity = slotForFace.getOrNull(i)
            resultFaces.add(if (identity != null) f.copy(identity = identity) else f)
        }

        // —— 7. 物体检测（独立节流，后台异步；本帧复用 lastObjects）——
        if (!objectEnabled) lastObjects = emptyList()
        val objects = if (objectEnabled) markHeldObjects(lastObjects, hands) else emptyList()

        return DetectionResult(
            faces = resultFaces,
            hands = hands,
            objects = objects,
            poses = poses,
        )
    }

    /**
     * 触发一次后台物体检测（独立节流）。调用方在 fire-and-forget 调用，
     * 完成后更新 [lastObjects]。不阻塞主帧循环。
     */
    fun runObjectDetection(bitmap: Bitmap, now: Long, objectEnabled: Boolean) {
        if (!objectEnabled || objectEngine == null || !objectEngine.isInitialized) return
        if (now - lastObjectRun < AppTuning.OBJECT_INTERVAL_MS) return
        if (objectBusy) return
        objectBusy = true
        lastObjectRun = now
        // 注意：调用方应在后台线程执行此 lambda 内的 detect。
        val objs = try {
            objectEngine.detect(bitmap)
        } catch (_: Exception) {
            emptyList()
        }
        lastObjects = objs
        objectBusy = false
    }

    /** 把物体标记为「正被手持」：物体框与任一手框重叠(IoU>0)或中心距够近。 */
    private fun markHeldObjects(
        objects: List<ObjectOverlay>,
        hands: List<com.xbot.android.model.HandOverlay>,
    ): List<ObjectOverlay> {
        if (objects.isEmpty() || hands.isEmpty()) return objects
        return objects.map { o ->
            var held = false
            for (h in hands) {
                val overlap = iou(o.boundingBox, h.boundingBox) > 0f
                val near = hypot(
                    o.center.x - h.boundingBox.centerX(),
                    o.center.y - h.boundingBox.centerY(),
                ) < AppTuning.HELD_DISTANCE
                if (overlap || near) {
                    held = true
                    break
                }
            }
            if (held) o.copy(heldByHand = true) else o
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interW = right - left
        val interH = bottom - top
        if (interW <= 0 || interH <= 0) return 0f
        val inter = interW * interH
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun reset() {
        slotTracker.clear()
        lastObjects = emptyList()
        lastIdentityRun = 0L
        lastObjectRun = 0L
    }
}
