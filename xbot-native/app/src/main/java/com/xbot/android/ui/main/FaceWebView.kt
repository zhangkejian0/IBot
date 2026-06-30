package com.xbot.android.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import com.xbot.android.behavior.GazeZoneDetector

/**
 * 创建表情页 WebView。在主线程调用。
 */
@SuppressLint("SetJavaScriptEnabled")
fun createFaceWebView(
    context: Context,
    onPageLoaded: () -> Unit = {}
): WebView {
    val webView = WebView(context)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
    }

    // 用 "/" 前缀:URL 路径直接映射到 assets/ 根目录。
    // dist/index.html → assets/dist/index.html
    // dist/assets/index-xxx.js → assets/dist/assets/index-xxx.js
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/", AssetsPathHandler(context))
        .build()

    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
            Log.d("FaceWebView", "JS: ${cm?.messageLevel()}: ${cm?.message()}")
            return true
        }
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val uri = request?.url ?: return null
            val resp = assetLoader.shouldInterceptRequest(uri)
            Log.d("FaceWebView", "拦截: ${request.url} -> ${if (resp != null) "命中" else "跳过"}")
            return resp
        }
        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            Log.e("FaceWebView", "加载错误: ${request?.url} code=${error?.errorCode} desc=${error?.description}")
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            onPageLoaded()
            Log.i("FaceWebView", "表情页加载完成: $url")
        }
    }

    webView.setBackgroundColor(0xFF0A0A0A.toInt())
    webView.loadUrl("https://appassets.androidplatform.net/dist/index.html")

    return webView
}

/**
 * WebView JS 注入工具。所有 JS 调用通过此类统一管理。
 */
class FaceWebViewBridge(private val webView: WebView) {

    private val gazeZoneDetector = GazeZoneDetector()

    fun pushGazeTarget(faceCenterX: Float, faceCenterY: Float, isFrontCamera: Boolean) {
        var x = 2f * faceCenterX - 1f
        val y = 2f * faceCenterY - 1f
        if (isFrontCamera) x = -x

        gazeZoneDetector.update(x, y)
        val zone = gazeZoneDetector.currentZoneCenter ?: return
        val js = "var f=window.__face;if(f){f.setGazeTarget(${zone.x.toInt()},${zone.y.toInt()});}"
        webView.evaluateJavascript(js, null)
    }

    fun pushState(state: String) {
        val js = "var f=window.__face;if(f){f.setState('$state');}"
        webView.evaluateJavascript(js, null)
    }

    fun pushListeningLoudness(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val js = "var f=window.__face;if(f){f.setListeningLoudness(${String.format("%.3f", clamped)});}"
        webView.evaluateJavascript(js, null)
    }

    fun resetGaze() {
        gazeZoneDetector.reset()
    }
}
