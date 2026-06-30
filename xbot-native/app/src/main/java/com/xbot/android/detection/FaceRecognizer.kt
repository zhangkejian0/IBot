package com.xbot.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xbot.android.model.IdentityMatch
import com.xbot.android.model.Person
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 人脸身份识别:MobileFaceNet TFLite 模型,输出 192 维 embedding。
 * 直接翻译自 Flutter 的 FaceRecognitionService。
 *
 * 在后台线程调用。不阻塞主线程。
 */
class FaceRecognizer(
    private val context: Context,
    private val modelPath: String = "models/mobilefacenet.tflite"
) {
    companion object {
        private const val TAG = "FaceRecognizer"
        private const val INPUT_SIZE = 112
        private const val DEFAULT_EMBEDDING_SIZE = 192
    }

    private var interpreter: Interpreter? = null
    private var embeddingSize = DEFAULT_EMBEDDING_SIZE

    /** 余弦相似度阈值:低于此值视为未识别 */
    var matchThreshold = 0.62f

    val isInitialized: Boolean get() = interpreter != null

    fun initialize() {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
            // 自动检测 embedding 维度
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            if (outputShape.size >= 2) {
                embeddingSize = outputShape[1]
            }
            Log.i(TAG, "MobileFaceNet 初始化完成,embedding 维度=$embeddingSize")
        } catch (e: Exception) {
            Log.e(TAG, "MobileFaceNet 初始化失败: ${e.message}")
        }
    }

    /**
     * 从裁剪后的人脸图像提取 embedding 向量。
     * @param faceCrop 裁剪后的人脸 Bitmap
     * @return 192 维归一化向量,失败返回 null
     */
    fun embed(faceCrop: Bitmap): List<Float>? {
        val interp = interpreter ?: return null
        return try {
            // 缩放到 112x112
            val resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)

            // 提取 RGB 字节并归一化到 [-1, 1]
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 128f)
            }

            // 推理
            val outputBuffer = ByteBuffer.allocateDirect(embeddingSize * 4)
            outputBuffer.order(ByteOrder.nativeOrder())
            interp.run(inputBuffer, outputBuffer)

            // 提取 embedding 并 L2 归一化
            outputBuffer.rewind()
            val embedding = FloatArray(embeddingSize)
            outputBuffer.asFloatBuffer().get(embedding)
            l2Normalize(embedding)
            embedding.toList()
        } catch (e: Exception) {
            Log.e(TAG, "embed 异常: ${e.message}")
            null
        }
    }

    /**
     * 在人物库中查找最匹配的身份。
     * @param embedding 待匹配的 embedding
     * @param people 已录入的人物列表
     * @return IdentityMatch? 最佳匹配,null=未识别
     */
    fun identify(embedding: List<Float>, people: List<Person>): IdentityMatch? {
        var bestMatch: IdentityMatch? = null
        for (person in people) {
            for (personEmbedding in person.embeddings) {
                val sim = cosineSimilarity(embedding, personEmbedding)
                if (sim >= matchThreshold) {
                    if (bestMatch == null || sim > bestMatch.similarity) {
                        bestMatch = IdentityMatch(person, sim)
                    }
                }
            }
        }
        return bestMatch
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun l2Normalize(arr: FloatArray) {
        var norm = 0f
        for (v in arr) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in arr.indices) arr[i] /= norm
        }
    }
}
