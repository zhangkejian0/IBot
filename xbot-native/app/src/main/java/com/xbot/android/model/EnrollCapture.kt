package com.xbot.android.model

/**
 * 一次人脸采样的结果（用于「认识我」录入）。对应 Flutter EnrollCapture。
 *
 * @param jpgBytes 裁剪后的人脸 JPEG 字节（质量 90）
 * @param embedding MobileFaceNet 特征向量；为空表示 embed 失败
 */
data class EnrollCapture(
    val jpgBytes: ByteArray,
    val embedding: List<Float>?,
) {
    val hasEmbedding: Boolean get() = !embedding.isNullOrEmpty()

    // data class 的 ByteArray 需手写 equals/hashCode（否则按引用比较）。
    override fun equals(other: Any?): Boolean =
        this === other || (other is EnrollCapture && jpgBytes.contentEquals(other.jpgBytes) && embedding == other.embedding)
    override fun hashCode(): Int = jpgBytes.contentHashCode() * 31 + (embedding?.hashCode() ?: 0)
}
