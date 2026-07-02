package com.xbot.android.ui

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xbot.android.R
import com.xbot.android.core.AppViewModel
import com.xbot.android.core.AssetDownloader
import com.xbot.android.core.AssetSpec
import com.xbot.android.core.DownloadPhase
import com.xbot.android.core.DownloadState
import com.xbot.android.core.ResourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "AssetDownloadScreen"

/** 功能介绍轮播单页数据。 */
private data class FeatureSlide(val icon: ImageVector, val title: String, val desc: String)

/** 轮播内容（下载期间滚动式介绍 app 功能）。 */
private val FEATURE_SLIDES = listOf(
    FeatureSlide(Icons.Filled.RecordVoiceOver, "声纹识别", "听声辨人，认出主人是谁"),
    FeatureSlide(Icons.Filled.Visibility, "视觉感知", "人脸、手势、物体，全场景识别"),
    FeatureSlide(Icons.Filled.GraphicEq, "实时语音对话", "自然开口，流畅交互"),
    FeatureSlide(Icons.Filled.PanTool, "语音记录", "时刻记录周围的每一句话"),
)

/** 轮播自动翻页间隔。 */
private const val SLIDE_INTERVAL_MS = 4000L

/** 字节数 → MB 字符串（保留 1 位小数）。如 165462184 → "157.5"。 */
private fun mb(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024.0))

/**
 * 首次启动资源下载页（游戏式分发）。
 *
 * 布局（横屏全屏，深色品牌）：
 * - 顶部 logo（复用 ic_launcher_foreground，带呼吸缩放动画）
 * - 中部：功能介绍自动轮播（[HorizontalPager] + 圆点指示器，每 4s 翻页）
 * - 底部：总进度条 + 当前文件名 + 百分比；失败态显示错误 + 重试按钮
 *
 * 下载编排（[startDownload]）：
 * 1. 拉取远端 manifest.json（失败时用本地缓存/兜底清单）
 * 2. 顺序下载每个 [AssetSpec]（[AssetDownloader]，支持断点续传）
 * 3. 全部完成后 [ResourceManager.verifyAll] 校验，通过则 [ResourceManager.markReady]
 * 4. 调用 [onComplete] 回到主流程
 *
 * @param appViewModel 提供 [ResourceManager]
 * @param onComplete 下载校验完成、资源就绪后回调（MainActivity 据此进 onboarding/ready）
 */
@Composable
fun AssetDownloadScreen(
    appViewModel: AppViewModel,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = appViewModel.resourceManager
    val state = remember { DownloadState() }

    // manifest 客户端（拉 manifest.json；与 AssetDownloader 独立，便于设置较短超时）。
    val manifestClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // 进入即自动开始下载；重试时把 phase 复位为 IDLE，本 effect 会重新触发。
    LaunchedEffect(state.phase) {
        if (state.phase == DownloadPhase.IDLE) {
            startDownload(scope, resources, state, manifestClient, onComplete)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 整体「上下」布局：上半部用 weight 占满，底部进度区固定高度。
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp),
        ) {
            // —— 上半部：左右分栏（左 Logo / 右功能轮播），weight=1 占满剩余空间 ——
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左：Logo + 品牌名（占一半宽度，垂直居中）。
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) { Logo() }
                // 右：功能轮播（占一半宽度）。
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) { FeatureCarousel() }
            }
            Spacer(Modifier.height(24.dp))
            // —— 最底部：下载进度区（固定，不参与 weight，永不被挤掉）——
            ProgressSection(state)
        }
    }
}

// ============================ 下载编排 ============================

/** 串行执行：拉 manifest → 逐文件下载 → 校验 → 标记就绪 → onComplete。 */
private fun startDownload(
    scope: kotlinx.coroutines.CoroutineScope,
    resources: ResourceManager,
    state: DownloadState,
    manifestClient: OkHttpClient,
    onComplete: () -> Unit,
) {
    scope.launch {
        try {
            // 已就绪（如从设置页跳来但文件其实都在）→ 直接完成。
            if (resources.isReady() && resources.verifyAll()) {
                state.phase = DownloadPhase.DONE
                onComplete()
                return@launch
            }

            state.phase = DownloadPhase.FETCHING_MANIFEST
            state.error = null
            val specs = fetchManifest(resources, state, manifestClient)

            // 初始化逐文件状态（用于 UI 列表/进度）。
            state.entries.clear()
            specs.forEach { state.entries.add(com.xbot.android.core.AssetProgress(it.path, total = it.size)) }

            state.phase = DownloadPhase.DOWNLOADING
            val downloader = AssetDownloader(ResourceManager.BASE_URL, resources)
            for (spec in specs) {
                state.currentFileName = spec.path.substringAfterLast('/')
                updateEntry(state, spec.path) { it.copy(status = com.xbot.android.core.AssetProgress.Status.DOWNLOADING) }
                try {
                    downloader.download(spec) { done, total ->
                        updateEntry(state, spec.path) {
                            it.copy(downloaded = done, total = if (total > 0) total else it.total)
                        }
                        state.recomputeOverall()
                    }
                    updateEntry(state, spec.path) {
                        it.copy(downloaded = it.total.coerceAtLeast(it.downloaded), status = com.xbot.android.core.AssetProgress.Status.DONE)
                    }
                    state.recomputeOverall()
                } catch (e: Exception) {
                    updateEntry(state, spec.path) { it.copy(status = com.xbot.android.core.AssetProgress.Status.FAILED) }
                    throw IOException("下载 ${spec.path} 失败: ${e.message}", e)
                }
            }

            // 最终校验（size + 可选 sha）。
            if (!resources.verifyAll()) {
                throw IOException("下载完成但校验未通过（文件 size 不符）")
            }
            resources.markReady()
            state.phase = DownloadPhase.DONE
            state.currentFileName = null
            Log.i(TAG, "所有资源下载校验完成")
            onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "资源下载失败: ${e.message}")
            state.phase = DownloadPhase.FAILED
            state.error = e.message ?: "未知错误"
        }
    }
}

/** 拉取远端 manifest.json；失败回退本地缓存/兜底清单（不阻断下载流程）。 */
private suspend fun fetchManifest(
    resources: ResourceManager,
    state: DownloadState,
    client: OkHttpClient,
): List<AssetSpec> = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url(ResourceManager.MANIFEST_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("manifest HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("manifest 空响应")
            val manifest = resources.applyRemoteManifest(body)
            Log.i(TAG, "远端 manifest 拉取成功（v${manifest.version}，${manifest.assets.size} 项）")
            manifest.assets
        }
    } catch (e: Exception) {
        Log.w(TAG, "远端 manifest 拉取失败，用本地缓存/兜底清单: ${e.message}")
        // applyRemoteManifest 已更新或 loadCachedManifest 早已加载；取当前 manifest。
        resources.manifest?.assets ?: ResourceManager.HEAVY_ASSETS.map { AssetSpec(it, 0L, null) }
    }
}

/** 更新某个文件的进度条目（线程安全：从协程分发，单写者改 list）。 */
private fun updateEntry(state: DownloadState, path: String, transform: (com.xbot.android.core.AssetProgress) -> com.xbot.android.core.AssetProgress) {
    val idx = state.entries.indexOfFirst { it.path == path }
    if (idx >= 0) state.entries[idx] = transform(state.entries[idx])
}

// ============================ UI 组件 ============================

/** 顶部 logo + 呼吸动画。 */
@Composable
private fun Logo() {
    val transition = rememberInfiniteTransition(label = "logo-breath")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "XBot",
            modifier = Modifier.size(132.dp).scale(scale).clip(CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "XBot",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 功能介绍自动轮播 + 圆点指示器。 */
@Composable
private fun FeatureCarousel() {
    val pagerState = rememberPagerState(pageCount = { FEATURE_SLIDES.size })
    val scope = rememberCoroutineScope()

    // 自动翻页：每 SLIDE_INTERVAL_MS 推进一页，循环。
    LaunchedEffect(pagerState.pageCount) {
        while (true) {
            delay(SLIDE_INTERVAL_MS)
            val next = (pagerState.currentPage + 1) % pagerState.pageCount
            pagerState.animateScrollToPage(next)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val slide = FEATURE_SLIDES[page]
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = slide.icon,
                    contentDescription = slide.title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = slide.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = slide.desc,
                    color = Color(0xFFB0B0B8),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            repeat(FEATURE_SLIDES.size) { i ->
                val active = i == pagerState.currentPage
                val alpha by animateFloatAsState(if (active) 1f else 0.3f, label = "dot-$i")
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
                )
            }
        }
    }
}

/** 底部进度区：下载中显示进度条；失败显示错误 + 重试；等待 manifest 显示转圈。 */
@Composable
private fun ProgressSection(state: DownloadState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state.phase) {
            DownloadPhase.FETCHING_MANIFEST -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text("正在准备资源清单…", color = Color(0xFFB0B0B8), fontSize = 14.sp)
            }
            DownloadPhase.DOWNLOADING -> {
                // 总已下载字节 / 总字节（按 manifest 加权）。
                val totalBytes = state.entries.sumOf { if (it.total > 0) it.total else 0L }
                val doneBytes = state.entries.sumOf { it.downloaded }
                val pct = (state.overallProgress * 100).toInt()
                // 当前文件行：文件名 + 已下载/总（MB）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.currentFileName ?: "准备中…",
                        color = Color(0xFFC8C8D0),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = "${mb(doneBytes)} / ${mb(totalBytes)} MB",
                        color = Color(0xFF9090A0),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 大百分比 + 进度条（加粗到 14dp，圆角，醒目）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${pct}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "下载中…",
                        color = Color(0xFF707078),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF2A2A30),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "首次使用需下载语音模型（约 254MB），请保持网络畅通",
                    color = Color(0xFF707078),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DownloadPhase.FAILED -> {
                Text("下载失败", color = Color(0xFFFF6B6B), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.error ?: "",
                    color = Color(0xFF9090A0),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    // 复位为 IDLE 触发顶部的 LaunchedEffect 重新下载（断点续传）。
                    state.error = null
                    state.phase = DownloadPhase.IDLE
                }) {
                    Text("重试")
                }
            }
            DownloadPhase.DONE -> {
                Text("资源准备完成", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            }
            DownloadPhase.IDLE -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
        }
    }
}
