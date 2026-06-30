package com.xbot.prototype

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 原型主界面。
 *
 * 验证目标:**原生后台线程做相机+MediaPipe 推理时,WebView 表情是否流畅**。
 *
 * 架构(对照 Flutter 方案的根本差异):
 * ```
 *   后台线程(Executors.newSingleThreadExecutor()):
 *     CameraX ImageProxy → FaceAnalyzer → MediaPipe FaceLandmarker
 *                                        │ 轻量结果(人脸中心坐标)
 *                                        ▼
 *   主线程(Handler main):  onResult → JS 注入 → WebView 表情注视
 * ```
 * 相机取流与推理全程在后台线程,主线程只负责 WebView 合成 + 接收几个坐标。
 * CameraX ImageAnalysis 用 STRATEGY_KEEP_ONLY_LATEST 自动丢帧,无 session 重建。
 *
 * 对比要点(看 debugOverlay 与 logcat):
 * - 推理延迟(后台线程,不阻塞主线程)
 * - WebView 是否仍有 Skipped frames(logcat 的 Choreographer)
 * - 表情注视跟随是否流畅
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XBotProto"
        // MediaPipe 人脸模型。需从官方下载放进 app/src/main/assets/。
        // 下载:https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
        private const val FACE_MODEL_PATH = "face_landmarker.task"
        // WebView 加载的表情页。复用现有 Flutter 项目的 assets/html/dist 产物。
        // 通过 WebViewAssetLoader 让 /assets/ 路径正确映射到 dist/assets/ 子目录,
        // 否则 index.html 里的 /assets/index-xxx.js 会 404。
        private const val FACE_PAGE_URL = "https://appassets.androidplatform.net/dist/index.html"
    }

    private lateinit var faceWebView: WebView
    private lateinit var debugOverlay: TextView
    private lateinit var permissionHint: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var faceAnalyzer: FaceAnalyzer? = null

    // —— 统计:用于对比判断原生方案的延迟/帧率 ——
    private val frameCount = AtomicInteger(0)
    private var lastStatTime = System.currentTimeMillis()
    private var lastFaceCenterX = 0.5f
    private var lastFaceCenterY = 0.5f
    private var lastFaceCount = 0
    private var lastInferMs = 0L
    private var pageLoaded = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraPipeline() else permissionHint.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        debugOverlay = findViewById(R.id.debugOverlay)
        permissionHint = findViewById(R.id.permissionHint)
        faceWebView = findViewById(R.id.faceWebView)

        setupWebView()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPipeline()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * 配置 WebView:用 [WebViewAssetLoader] 加载真实表情页(assets/dist/index.html)。
     *
     * 关键:dist/index.html 里的 JS 用绝对路径 `/assets/index-xxx.js`。
     * [WebViewAssetLoader] 拦截 `https://appassets.androidplatform.net/` 请求,
     * 把 `/dist/assets/` 映射到 `assets/dist/assets/`,让 Vite 产物路径正确解析。
     * 同时用自定义 [WebViewClient] 把拦截结果路由给 [assetLoader]。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        faceWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // 硬件加速合成(WebView 流畅的关键)。
            loadWithOverviewMode = true
            useWideViewPort = true
            // 禁用默认图标加载,避免左上角显示Android图标
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        // 禁用favicon图标显示
        faceWebView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                // 忽略favicon,不显示任何图标
            }
        }
        // 挂载根路径 "/"，保留完整子路径：
        //   /dist/index.html            → assets/dist/index.html
        //   /dist/assets/index-xxx.js   → assets/dist/assets/index-xxx.js
        // 若挂载 "/dist/" 会剥离前缀，导致 assets/index.html 找不到文件。
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", AssetsPathHandler(this))
            .build()
        // 手动覆写 shouldInterceptRequest,把请求路由给 assetLoader 拦截。
        // createWebViewClient() 在 webkit 1.12.1 不存在,此方式是所有版本通用的。
        faceWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                Log.i(TAG, "表情页加载完成,开始注入注视")
            }
        }
        faceWebView.setBackgroundColor(0xFF0A0A0A.toInt())
        faceWebView.loadUrl(FACE_PAGE_URL)
    }

    /** 启动相机管线:绑定 ImageAnalysis 到后台 executor(核心!)。 */
    private fun startCameraPipeline() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // ImageAnalysis:STRATEGY_KEEP_ONLY_LATEST 自动丢弃忙时的帧,无堆积。
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 输出旋转:Crop to front-camera,陪伴场景横屏正视用户。
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            faceAnalyzer = FaceAnalyzer(this, FACE_MODEL_PATH) { cx, cy, count, inferMs ->
                // 此回调在 MediaPipe 后台线程触发。统计 + 切到主线程更新 WebView。
                frameCount.incrementAndGet()
                lastFaceCenterX = cx
                lastFaceCenterY = cy
                lastFaceCount = count
                lastInferMs = inferMs
                mainHandler.post { pushGazeToWebView(cx, cy) }
            }
            imageAnalysis.setAnalyzer(analysisExecutor, faceAnalyzer!!)

            // Preview 不绑定到 UI(陪伴机器人不显示相机预览),只绑 ImageAnalysis:
            // 相机帧直接进后台线程推理,无预览开销。
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                // 只绑 ImageAnalysis:相机帧直接进后台线程推理,无预览开销。
                cameraProvider.bindToLifecycle(
                    this, selector, imageAnalysis
                )
                Log.i(TAG, "相机管线已启动(后台线程推理)")
                startStatsTicker()
            } catch (e: Exception) {
                Log.e(TAG, "相机绑定失败: ${e.message}")
                debugOverlay.text = "相机绑定失败: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 把人脸中心经 JS 注入 WebView,驱动表情的注视跟随(对应 Flutter 的 setGazeTarget)。
     * 在主线程调用(由 mainHandler.post 切换)。
     */
    private fun pushGazeToWebView(cx: Float, cy: Float) {
        if (!pageLoaded) return
        // 人脸中心(0..1) → 九宫格量化(-1/0/1),与 Flutter 侧 GazeZoneDetector 一致。
        // 前置摄像头水平镜像:用户右移 → 屏幕眼神向左,故 x 取反贴合自拍视角。
        val gx = quantize(0.5f - cx)
        val gy = quantize(cy - 0.5f)
        val js = "var f=window.__face;if(f){f.setGazeTarget($gx,$gy);}"
        faceWebView.evaluateJavascript(js, null)
    }

    private fun quantize(v: Float): Float {
        if (v < -0.17f) return -1f
        if (v > 0.17f) return 1f
        return 0f
    }

    /** 定时统计并刷新调试浮层:推理帧率 + 人脸数。用于对比判断。 */
    private fun startStatsTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                val now = System.currentTimeMillis()
                val elapsed = now - lastStatTime
                if (elapsed >= 1000) {
                    val frames = frameCount.getAndSet(0)
                    val fps = frames * 1000f / elapsed
                    debugOverlay.text =
                        "原生后台线程\n推理: ${"%.1f".format(fps)} fps\n" +
                        "耗时: $lastInferMs ms/帧\n人脸: $lastFaceCount\n" +
                        "中心: ${"%.2f".format(lastFaceCenterX)},${"%.2f".format(lastFaceCenterY)}"
                    lastStatTime = now
                }
                mainHandler.postDelayed(this, 500)
            }
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        faceAnalyzer?.close()
    }
}
