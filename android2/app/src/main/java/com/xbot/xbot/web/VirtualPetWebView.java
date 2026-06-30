package com.xbot.xbot.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.graphics.Color;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import com.xbot.xbot.face.EmotionMapper;
import com.xbot.xbot.face.GazeZoneDetector;
import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.model.FaceOverlay;
import com.xbot.xbot.model.FaceState;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.voice.VoiceAssistant;
import com.xbot.xbot.voice.VoiceState;

/**
 * Virtual pet WebView with {@link WebViewAssetLoader} and throttled JS bridge.
 */
public class VirtualPetWebView extends FrameLayout {
    private static final String TAG = "VirtualPetWebView";
    private static final long PUSH_INTERVAL_MS = 50;
    private static final long MIN_DWELL_MS = 600;

    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppViewModel viewModel;
    private boolean pageLoaded;
    private long lastPushAt;
    private boolean lastVoiceActive;
    private FaceState lastSentState;
    private long lastSentStateAt;

    private final Runnable pushRunnable = this::pushAll;

    public VirtualPetWebView(@NonNull Context context) {
        this(context, null);
    }

    public VirtualPetWebView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        webView = new WebView(context);
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        configureWebView(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(Context context) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        WebViewAssetLoader assetLoader = VirtualPetAssetLoader.create(context);

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                lastPushAt = System.currentTimeMillis();
                evaluate(FaceJsBridge.buildPushScript(
                        FaceJsBridge.setState("idle"), null, null));
                pushAll();
            }

            @Override
            @Nullable
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                android.webkit.WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(request.getUrl());
                if (response == null && request != null) {
                    Log.w(TAG, "asset miss: " + request.getUrl());
                }
                return response;
            }
        });

        webView.setBackgroundColor(Color.parseColor("#0A0A0A"));
        webView.loadUrl(VirtualPetAssetLoader.entryUrl());
    }

    public void bind(@NonNull AppViewModel vm, @NonNull LifecycleOwner owner) {
        if (viewModel == vm) {
            return;
        }
        unbind();
        viewModel = vm;

        vm.getDetectionResult().observe(owner, onDetectionChanged);
        vm.getBehaviorState().observe(owner, onBehaviorChanged);
        vm.getVoiceAssistant().getState().observe(owner, onVoiceChanged);
        vm.getVoiceAssistant().getLevel().observe(owner, onVoiceLevelChanged);
    }

    public void unbind() {
        viewModel = null;
    }

    private final Observer<DetectionResult> onDetectionChanged = result -> schedulePush();
    private final Observer<BehaviorState> onBehaviorChanged = state -> schedulePush();
    private final Observer<VoiceState> onVoiceChanged = state -> schedulePush();
    private final Observer<Float> onVoiceLevelChanged = level -> schedulePush();

    private void schedulePush() {
        if (!pageLoaded || viewModel == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPushAt < PUSH_INTERVAL_MS) {
            mainHandler.removeCallbacks(pushRunnable);
            mainHandler.postDelayed(pushRunnable, PUSH_INTERVAL_MS - (now - lastPushAt));
            return;
        }
        pushAll();
    }

    private void pushAll() {
        if (!pageLoaded || viewModel == null) {
            return;
        }
        lastPushAt = System.currentTimeMillis();
        mainHandler.removeCallbacks(pushRunnable);

        VoiceAssistant voice = viewModel.getVoiceAssistant();
        VoiceState voiceState = voice.getState().getValue();
        if (voiceState == null) {
            voiceState = VoiceState.IDLE;
        }
        boolean voiceActive = voice.isRunning() && voiceState.isActive();
        boolean justEnded = lastVoiceActive && !voiceActive;
        lastVoiceActive = voiceActive;

        if (justEnded) {
            evaluate(FaceJsBridge.buildVoiceEndScript());
            lastSentState = null;
        }

        if (voiceActive) {
            String stateWire = voiceState.getFaceStateWireName();
            Float level = voice.getLevel().getValue();
            float loudness = level != null ? level : 0f;
            String js = FaceJsBridge.buildPushScript(
                    FaceJsBridge.setState(stateWire),
                    null,
                    FaceJsBridge.setListeningLoudness(loudness));
            evaluate(js);
            return;
        }

        DetectionResult result = viewModel.getDetectionResult().getValue();
        FaceOverlay face = result != null ? result.getPrimaryFace() : null;
        GazeZoneDetector zoneDetector = viewModel.getGazeZoneDetector();

        String gazeJs = "";
        if (face != null && face.boundingBox != null) {
            float cx = face.boundingBox.centerX();
            float cy = face.boundingBox.centerY();
            double x = 2 * cx - 1;
            double y = 2 * cy - 1;
            if (viewModel.isFrontCamera()) {
                x = -x;
            }
            zoneDetector.update(x, y);
            float[] zone = zoneDetector.getCurrentZoneCenter();
            if (zone != null) {
                gazeJs = FaceJsBridge.setGazeTarget(zone[0], zone[1]);
            }
        } else {
            zoneDetector.reset();
        }

        String stateJs = "";
        if (face != null) {
            BehaviorState behavior = viewModel.getBehaviorState().getValue();
            FaceState mapped = EmotionMapper.fromBehavior(behavior);
            long now = System.currentTimeMillis();
            boolean changed = mapped != lastSentState;
            boolean dwellOk = lastSentState == null || now - lastSentStateAt >= MIN_DWELL_MS;
            if (changed && dwellOk) {
                lastSentState = mapped;
                lastSentStateAt = now;
                stateJs = FaceJsBridge.setState(mapped.toWireName());
            }
        }

        String js = FaceJsBridge.buildPushScript(stateJs, gazeJs, null);
        if (!js.isEmpty()) {
            evaluate(js);
        }
    }

    private void evaluate(String js) {
        if (js == null || js.isEmpty()) {
            return;
        }
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(pushRunnable);
        super.onDetachedFromWindow();
    }
}
