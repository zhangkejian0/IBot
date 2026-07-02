package com.xbot.android.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 单个资源条目的实时下载状态。 */
data class AssetProgress(
    val path: String,
    /** 已下载字节。 */
    val downloaded: Long = 0L,
    /** 总字节（manifest 给定；未知为 -1）。 */
    val total: Long = -1L,
    val status: Status = Status.PENDING,
) {
    enum class Status { PENDING, DOWNLOADING, DONE, FAILED }

    /** 0..1 进度。total 未知时返回 0（避免显示误导性百分比）。 */
    val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f

    /** 用于 UI 显示的短文件名。 */
    val displayName: String get() = path.substringAfterLast('/')
}

/** 下载整体阶段。 */
enum class DownloadPhase { IDLE, FETCHING_MANIFEST, DOWNLOADING, DONE, FAILED }

/**
 * 整个资源下载流程的可观察状态（Compose 订阅）。
 *
 * 沿用 VoiceLogStore / ConversationLogger 的 mutableStateListOf + mutableStateOf 范式，
 * 线程安全靠 [AssetDownloader] 的串行协程调度（同一时刻仅一个文件在下载，
 * 单写者更新本状态，无需同步原语）。
 */
class DownloadState {
    /** 整体阶段。 */
    var phase by mutableStateOf(DownloadPhase.IDLE)
        internal set

    /** manifest 拉取/下载失败的错误信息（phase=FAILED 时非空）。 */
    var error by mutableStateOf<String?>(null)
        internal set

    /** 当前正在下载的文件名（UI 高亮显示）。 */
    var currentFileName by mutableStateOf<String?>(null)
        internal set

    /** 总进度 0..1（按各文件总字节加权）。 */
    var overallProgress by mutableFloatStateOf(0f)
        internal set

    /** 每个文件的状态（顺序与 manifest 一致）。 */
    val entries = mutableStateListOf<AssetProgress>()

    /** 按 entries 总字节加权计算整体进度。 */
    internal fun recomputeOverall() {
        var sumDone = 0L
        var sumTotal = 0L
        for (e in entries) {
            sumDone += e.downloaded
            if (e.total > 0) sumTotal += e.total
        }
        overallProgress = if (sumTotal > 0) (sumDone.toFloat() / sumTotal).coerceIn(0f, 1f) else 0f
    }
}
