package com.xbot.android.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xbot.android.model.IdentityMatch
import com.xbot.android.model.Person
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * 人脸身份识别服务：MobileFaceNet（TFLite）。
 *
 * 对应 Flutter 的 face_recognition_service.dart。
 *
 * 把人脸裁剪图转换为定长特征向量（embedding），通过余弦相似度与已录入人物比对得到身份。
 *
 * - 输入：1x112x112x3 float32，归一化 (px-127.5)/128
 * - 输出：embedding（动态维度，通常 192），L2 归一化
 * - threads=4（CPU + XNNPACK）
 * - 余弦相似度阈值 0.62
 *
 * @param modelPath assets 中的模型路径（mobilefacenet.tflite）
 */
class FaceRecognizer(
    context: Context,
    modelPath: String,
    /** 识别命中阈值（余弦相似度，-1..1）。 */
    var matchThreshold: Float = 0.62f,
) {
    companion object {
        private const val TAG = "FaceRecognizer"
        private const val INPUT_SIZE = 112
    }

    private var interpreter: Interpreter? = null
    private var available = false
    var embeddingSize = 192
        private set

    /** 输入缓冲区：112*112*3 float32。预分配避免每次 embed 重建。 */
    private val inputBuffer: FloatArray = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

    val isAvailable: Boolean get() = available

    init {
        try {
            val modelBuffer = loadModelFile(context, modelPath)
            val options = Interpreter.Options().setNumThreads(4)
            val itp = Interpreter(modelBuffer, options)

            // 校验输入契约：1x112x112x3。
            val inShape = itp.getInputTensor(0).shape()
            val expected = intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3)
            val shapeOk = inShape.size == 4 &&
                inShape[0] == expected[0] && inShape[1] == expected[1] &&
                inShape[2] == expected[2] && inShape[3] == expected[3]
            if (!shapeOk) {
                Log.e(TAG, "输入形状不符（$inShape），身份识别停用")
                itp.close()
            } else {
                // 输出维度：不同导出可能是 [1,192] / [1,1,1,192]，取总元素数。
                val outShape = itp.getOutputTensor(0).shape()
                var embDim = 1
                for (d in outShape) embDim *= if (d > 0) d else 1
                if (embDim <= 0) embDim = 192
                embeddingSize = embDim
                interpreter = itp
                available = true
                Log.i(TAG, "MobileFaceNet 已加载（embedding 维度 $embDim）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "身份识别模型加载异常: ${e.message}")
            available = false
        }
    }

    /** 把一张已裁剪的人脸图转为归一化后的特征向量。失败返回 null。 */
    fun embed(faceCrop: Bitmap): List<Float>? {
        val itp = interpreter ?: return null
        return try {
            embedInternal(itp, faceCrop)
        } catch (e: Exception) {
            Log.e(TAG, "embed 异常: ${e.message}")
            null
        }
    }

    private fun embedInternal(itp: Interpreter, faceCrop: Bitmap): List<Float> {
        // resize 到 112x112。
        val resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
        val n = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(n)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        // 批量取 RGB，归一化 (px-127.5)/128 → [-1,1]。
        for (i in 0 until n) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF)
            val g = ((px shr 8) and 0xFF)
            val b = (px and 0xFF)
            val j = i * 3
            inputBuffer[j] = (r - 127.5f) / 128f
            inputBuffer[j + 1] = (g - 127.5f) / 128f
            inputBuffer[j + 2] = (b - 127.5f) / 128f
        }
        // 输入 reshape 成 [1,112,112,3]，输出按 [1, embeddingSize]。
        val input4d = inputBuffer.reshapeTo4d()
        val output = Array(1) { FloatArray(embeddingSize) }
        itp.run(input4d, output)
        return l2Normalize(output[0])
    }

    /** 在已录入人物中寻找最相似者；不足阈值返回 null。 */
    fun identify(embedding: List<Float>, people: List<Person>): IdentityMatch? {
        var best: Person? = null
        var bestSim = -1f
        for (person in people) {
            for (sample in person.embeddings) {
                val sim = cosine(embedding, sample)
                if (sim > bestSim) {
                    bestSim = sim
                    best = person
                }
            }
        }
        if (best == null || bestSim < matchThreshold) return null
        return IdentityMatch(best, bestSim)
    }

    private fun l2Normalize(v: FloatArray): List<Float> {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val norm = sqrt(sum)
        if (norm == 0.0) return v.toList()
        return v.map { (it / norm).toFloat() }
    }

    /** 余弦相似度（两侧均已 L2 归一化，点积即余弦）。 */
    private fun cosine(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0
        for (i in a.indices) dot += (a[i] * b[i]).toDouble()
        return dot.toFloat()
    }

    /** 把 [inputBuffer] 包装成 Array(1){Array(112){Array(112){FloatArray(3)}}}。 */
    private fun FloatArray.reshapeTo4d(): Array<Array<Array<FloatArray>>> {
        val out = Array(1) {
            Array(INPUT_SIZE) {
                Array(INPUT_SIZE) { FloatArray(3) }
            }
        }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val base = (y * INPUT_SIZE + x) * 3
                out[0][y][x][0] = this[base]
                out[0][y][x][1] = this[base + 1]
                out[0][y][x][2] = this[base + 2]
            }
        }
        return out
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

    fun close() {
        interpreter?.close()
        interpreter = null
        available = false
    }
}
