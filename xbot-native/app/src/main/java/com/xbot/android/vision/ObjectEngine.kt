package com.xbot.android.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.xbot.android.model.ObjectOverlay
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 物体识别引擎：YOLO26（int8 TFLite）。
 *
 * 对应 Flutter 的 object_engine.dart（ultralytics_yolo 插件，YOLO26 COCO 80 类）。
 *
 * **YOLO26 端到端无 NMS 导出**：模型已内嵌 NMS，输出张量是已过滤的检测结果，
 * 形如 [1, num_detections, 6]，每行 = [x_center, y_center, width, height, conf, cls]
 *（坐标归一化到 0..1）。故本引擎**不需要再实现 NMS**，直接读结果 + 置信度过滤即可。
 *
 * 若遇到旧式 [1, 4+nc, anchors] 布局（需 NMS），[decode] 会按形状自动分支处理。
 *
 * 跳过 person 类（人的检测/身份由人脸引擎负责）。标签经 [COCO_ZH] 映射为中文。
 *
 * @param modelPath assets 中的模型路径（yolo26n_int8.tflite）
 */
class ObjectEngine(
    context: Context,
    modelPath: String,
    /** 单帧最多返回的物体数（按面积降序截断）。 */
    private val maxObjects: Int = 10,
    /** 置信度阈值（低于此值过滤）。 */
    private val confidenceThreshold: Float = 0.35f,
    /** NMS 的 IoU 阈值（仅旧式布局用到）。 */
    private val iouThreshold: Float = 0.45f,
    private val inputSize: Int = 640,
) {
    companion object {
        private const val TAG = "ObjectEngine"

        /** COCO 80 类英文（顺序即类别 index）。 */
        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
        )

        /** COCO 80 类英文 → 中文（与 Flutter object_engine.dart 一致）。 */
        val COCO_ZH: Map<String, String> = mapOf(
            "person" to "人", "bicycle" to "自行车", "car" to "汽车", "motorcycle" to "摩托车",
            "airplane" to "飞机", "bus" to "公交车", "train" to "火车", "truck" to "卡车",
            "boat" to "船", "traffic light" to "红绿灯", "fire hydrant" to "消防栓",
            "stop sign" to "停车标志", "parking meter" to "停车计时器", "bench" to "长椅",
            "bird" to "鸟", "cat" to "猫", "dog" to "狗", "horse" to "马", "sheep" to "羊",
            "cow" to "牛", "elephant" to "大象", "bear" to "熊", "zebra" to "斑马",
            "giraffe" to "长颈鹿", "backpack" to "背包", "umbrella" to "伞", "handbag" to "手提包",
            "tie" to "领带", "suitcase" to "行李箱", "frisbee" to "飞盘", "skis" to "滑雪板",
            "snowboard" to "单板滑雪", "sports ball" to "球", "kite" to "风筝",
            "baseball bat" to "棒球棒", "baseball glove" to "棒球手套", "skateboard" to "滑板",
            "surfboard" to "冲浪板", "tennis racket" to "网球拍", "bottle" to "瓶子",
            "wine glass" to "酒杯", "cup" to "杯子", "fork" to "叉子", "knife" to "刀",
            "spoon" to "勺子", "bowl" to "碗", "banana" to "香蕉", "apple" to "苹果",
            "sandwich" to "三明治", "orange" to "橙子", "broccoli" to "西兰花", "carrot" to "胡萝卜",
            "hot dog" to "热狗", "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕",
            "chair" to "椅子", "couch" to "沙发", "potted plant" to "盆栽", "bed" to "床",
            "dining table" to "餐桌", "toilet" to "马桶", "tv" to "电视", "laptop" to "笔记本电脑",
            "mouse" to "鼠标", "remote" to "遥控器", "keyboard" to "键盘", "cell phone" to "手机",
            "microwave" to "微波炉", "oven" to "烤箱", "toaster" to "烤面包机", "sink" to "水槽",
            "refrigerator" to "冰箱", "book" to "书", "clock" to "时钟", "vase" to "花瓶",
            "scissors" to "剪刀", "teddy bear" to "玩偶熊", "hair drier" to "吹风机", "toothbrush" to "牙刷",
        )
    }

    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    init {
        try {
            val modelBuffer = loadModelFile(context, modelPath)
            val itp = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(4))
            interpreter = itp
            isInitialized = true
            val inShape = itp.getInputTensor(0).shape().toList()
            val outShape = itp.getOutputTensor(0).shape().toList()
            Log.i(TAG, "YOLO26 已加载，inShape=$inShape outShape=$outShape")
        } catch (e: Exception) {
            Log.e(TAG, "物体识别模型加载失败: ${e.message}")
            isInitialized = false
        }
    }

    /**
     * 对一张摆正后的 bitmap 做物体检测。返回归一化（0..1）物体列表，按面积降序、
     * 至多 [maxObjects] 个。
     */
    fun detect(bitmap: Bitmap): List<ObjectOverlay> {
        val itp = interpreter ?: return emptyList()
        return try {
            // YOLO 输入：640x640 RGB，归一化 /255（letterbox 缩放保持比例）。
            val letterboxed = letterbox(bitmap, inputSize)
            val input = buildInput(letterboxed)
            // 输出布局自适应：先按 outShape(0) 判断。
            val outShape = itp.getOutputTensor(0).shape()
            val detections = when {
                // 端到端无 NMS：[1, N, 6] 或 [1, N, 4+1+1]
                outShape.size == 3 && outShape[2] <= 6 && outShape[1] > outShape[2] -> {
                    val out = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
                    itp.run(arrayOf(input), out)
                    decodeEndToEnd(out[0], letterboxed.scaleX, letterboxed.scaleY, letterboxed.padX, letterboxed.padY)
                }
                // 旧式：[1, 4+nc, anchors] 需 NMS
                outShape.size == 3 && outShape[1] >= 4 + 80 -> {
                    val nc = outShape[1] - 4
                    val anchors = outShape[2]
                    val out = Array(1) { Array(outShape[1]) { FloatArray(anchors) } }
                    itp.run(arrayOf(input), out)
                    decodeClassic(out[0], nc, anchors, letterboxed.scaleX, letterboxed.scaleY, letterboxed.padX, letterboxed.padY)
                }
                else -> {
                    Log.w(TAG, "未知输出布局 outShape=$outShape，跳过")
                    emptyList()
                }
            }
            detections
                .filterNot { it.label.equals("person", ignoreCase = true) || it.label == "人" }
                .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                .take(maxObjects)
        } catch (e: Exception) {
            Log.e(TAG, "detect 异常: ${e.message}")
            emptyList()
        }
    }

    /** 端到端无 NMS 解码：每行 [cx, cy, w, h, conf, cls]，坐标相对 letterbox 后的 640x640。 */
    private fun decodeEndToEnd(
        rows: Array<FloatArray>,
        scaleX: Float,
        scaleY: Float,
        padX: Float,
        padY: Float,
    ): List<ObjectOverlay> {
        val out = ArrayList<ObjectOverlay>()
        for (row in rows) {
            if (row.size < 6) continue
            val conf = row[4]
            if (conf < confidenceThreshold) continue
            val clsIdx = row[5].toInt()
            // 反 letterbox：去掉 padding + 缩放回原图归一化空间。
            val cx = (row[0] - padX) * scaleX
            val cy = (row[1] - padY) * scaleY
            val w = row[2] * scaleX
            val h = row[3] * scaleY
            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)
            val rawLabel = COCO_LABELS.getOrElse(clsIdx) { "object_$clsIdx" }
            out.add(
                ObjectOverlay(
                    boundingBox = RectF(left, top, right, bottom),
                    label = COCO_ZH[rawLabel] ?: rawLabel,
                    confidence = conf,
                )
            )
        }
        return out
    }

    /** 旧式 [4+nc, anchors] 解码 + NMS。 */
    private fun decodeClassic(
        data: Array<FloatArray>,
        nc: Int,
        anchors: Int,
        scaleX: Float,
        scaleY: Float,
        padX: Float,
        padY: Float,
    ): List<ObjectOverlay> {
        // data[row][anchor]，row = 0..3 是 xywh，row = 4..4+nc-1 是各类置信度。
        val candidates = ArrayList<ObjectOverlay>()
        for (a in 0 until anchors) {
            // 找最高类别置信度。
            var bestCls = -1
            var bestConf = confidenceThreshold
            for (c in 0 until nc) {
                val conf = data[4 + c][a]
                if (conf > bestConf) {
                    bestConf = conf
                    bestCls = c
                }
            }
            if (bestCls < 0) continue
            val cx = (data[0][a] - padX) * scaleX
            val cy = (data[1][a] - padY) * scaleY
            val w = data[2][a] * scaleX
            val h = data[3][a] * scaleY
            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)
            val rawLabel = COCO_LABELS.getOrElse(bestCls) { "object_$bestCls" }
            candidates.add(
                ObjectOverlay(
                    boundingBox = RectF(left, top, right, bottom),
                    label = COCO_ZH[rawLabel] ?: rawLabel,
                    confidence = bestConf,
                )
            )
        }
        return nms(candidates, iouThreshold)
    }

    /** NMS（非极大值抑制）。 */
    private fun nms(boxes: List<ObjectOverlay>, iouThreshold: Float): List<ObjectOverlay> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val result = ArrayList<ObjectOverlay>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > iouThreshold }
        }
        return result
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

    /** letterbox 信息：缩放后的 bitmap + 反变换系数。 */
    private data class Letterbox(
        val bitmap: Bitmap,
        val scaleX: Float, // 640空间坐标 → 原图归一化：scaleX = 1 / (有效宽 / 原宽)
        val scaleY: Float,
        val padX: Float,   // 640空间下的左 padding（归一化）
        val padY: Float,
    )

    /** 等比缩放到 640x640，短边填充灰色(114)，保持比例。记录反变换参数。 */
    private fun letterbox(src: Bitmap, size: Int): Letterbox {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // 填充灰色 114。
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val padX = (size - newW) / 2f
        val padY = (size - newH) / 2f
        canvas.drawBitmap(scaled, padX, padY, null)
        // 反变换：640空间坐标(x) → 原图归一化：(x - padX)/(newW) ... 但乘以 1/scale 得原图像素，再 /原宽。
        // 归一化空间下：scaleX = (原图宽) / (newW) （把 640 有效宽还原成原图宽占比）。
        val scaleX = src.width.toFloat() / newW
        val scaleY = src.height.toFloat() / newH
        return Letterbox(out, scaleX, scaleY, padX / size, padY / size)
    }

    /** 构造 YOLO 输入：1x640x640x3 float32，归一化 /255。 */
    private fun buildInput(lb: Letterbox): Array<Array<Array<FloatArray>>> {
        val pixels = IntArray(size = inputSize * inputSize)
        lb.bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val out = Array(inputSize) {
            Array(inputSize) { FloatArray(3) }
        }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = pixels[y * inputSize + x]
                out[y][x][0] = ((px shr 16) and 0xFF) / 255f
                out[y][x][1] = ((px shr 8) and 0xFF) / 255f
                out[y][x][2] = (px and 0xFF) / 255f
            }
        }
        return arrayOf(out)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelPath)
        FileInputStream(fd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength,
            ).order(ByteOrder.nativeOrder()) as MappedByteBuffer
        }
    }
}
