package com.xbot.android.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * 相机预览 Compose 组件：绑定 CameraX Preview（可见预览）+ 可选 ImageAnalysis（后台帧分析）。
 *
 * Preview 用 [PreviewView]（SurfaceView/TextureView 实现，自带旋转处理），保证横屏铺满且方向正确。
 * ImageAnalysis 的 analyzer 在 [analysisExecutor] 上回调（后台线程）。
 *
 * @param useFront 是否前置摄像头
 * @param analysisExecutor 帧分析 executor；为 null 则不绑 ImageAnalysis
 * @param analyzer 帧分析回调（后台线程）；为 null 则不绑
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    useFront: Boolean = true,
    /** 是否激活绑定（含 Preview + 可选 ImageAnalysis）。权限未授予时应传 false。
     *  变化时会重新绑定，确保 analyzer 在权限授予后真正挂上。 */
    active: Boolean = true,
    /** 兼容模式：用 TextureView 实现，便于父级裁剪为圆形/圆角（SurfaceView 不随父级裁剪）。 */
    compatibleMode: Boolean = false,
    analysisExecutor: Executor? = null,
    analyzer: ((ImageProxy) -> Unit)? = null,
    onCameraBound: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            if (compatibleMode) implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // key 同时依赖 useFront 与 active：active 由 false→true（权限授予）时必须重新绑定，
    // 否则 ImageAnalysis 不会挂上（analyzer 永远不触发）。
    DisposableEffect(useFront, active) {
        if (!active) {
            onDispose { /* 未激活，无需解绑 */ }
        } else {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    // Preview use case：渲染到 previewView。
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val useCases = ArrayList<androidx.camera.core.UseCase>()
                    useCases.add(preview)
                    // 可选 ImageAnalysis。
                    if (analysisExecutor != null && analyzer != null) {
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor, analyzer)
                        useCases.add(analysis)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                    onCameraBound?.invoke()
                } catch (_: Exception) {
                    // 单帧绑定失败不影响 UI。
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                try {
                    val provider = future.get()
                    provider.unbindAll()
                } catch (_: Exception) {}
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
