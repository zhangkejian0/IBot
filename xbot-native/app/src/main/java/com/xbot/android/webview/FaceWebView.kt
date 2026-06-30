package com.xbot.android.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟形象 WebView：加载 assets/dist 前端，暴露 [FaceBridge] 接收检测结果并注入 JS。
 *
 * 对应 Flutter 的 _VirtualPetWebView（camera_screen.dart）。
 *
 * **加载方式**：用 [WebViewAssetLoader]（https://appassets.androidplatform.net/）直接映射
 * assets，**无需起 HTTP server**（native-prototype 已验证比 Flutter 的 StaticServer 更优）。
 * dist/index.html 里的 JS 用绝对路径 /assets/index-*.js，assetLoader 把 /assets/ 映射到
 * assets/dist/assets/，路径正确解析。
 *
 * **JS 契约**（assets/html/src/face/runtime/controller.ts:206 暴露 window.__face）：
 *   - setState(state)              切虚拟形象状态（idle/gazing/listening/thinking/happy/sleepy...）
 *   - setGazeTarget(x, y)         瞳孔目标（-1..1，正=右/下），绕过弹簧零延迟跟随
 *   - setListeningLoudness(v)     聆听/说话时嘴部张合（0..1）
 */
class FaceWebView(context: Context) : WebView(context) {

    companion object {
        /** WebViewAssetLoader 的虚拟域名（所有请求都走这个 https 前缀）。 */
        private const val FACE_PAGE_URL = "https://appassets.androidplatform.net/dist/index.html?style=ambient"
    }

    /** 双击回调（由 MainScreenController 设置，触发语音助手）。 */
    var onDoubleTap: (() -> Unit)? = null

    private val pageLoaded = AtomicBoolean(false)

    /** 手势检测器：在 WebView 层直接捕获双击（绕过 Compose 手势被平台视图吞掉的问题）。 */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            android.util.Log.i("FaceWebView", "双击触发")
            onDoubleTap?.invoke()
            return true
        }
        // 不拦截单击（不阻碍 WebView 正常交互）。
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
    })

    /** JS 桥接：累积检测结果/语音状态，按节流间隔推送给前端。 */
    val bridge = FaceBridge(this)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun setup() {
        // 拦截触摸事件：转发给 GestureDetector 检测双击。
        // 不拦截单击（return false），让 WebView 正常处理。
        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // 不消费，WebView 仍可正常交互
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // 硬件加速合成（WebView 流畅的关键）。
        }
        // 禁用 favicon 图标（避免左上角显示默认 Android 图标）。
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) { /* 忽略 */ }
        }

        // 挂载根路径 "/"，保留完整子路径：
        //   /dist/index.html          → assets/dist/index.html
        //   /dist/assets/index-*.js   → assets/dist/assets/index-*.js
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded.set(true)
                bridge.onPageLoaded()
            }
        }
        setBackgroundColor(Color.parseColor("#0A0A0A"))
        // 横屏铺满。
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        loadUrl(FACE_PAGE_URL)
    }

    /** 页面是否已就绪（可注入 JS）。 */
    val isPageLoaded: Boolean get() = pageLoaded.get()

    /** 执行一段 JS（在主线程调用）。 */
    fun eval(js: String) {
        if (pageLoaded.get()) {
            evaluateJavascript(js, null)
        }
    }
}
