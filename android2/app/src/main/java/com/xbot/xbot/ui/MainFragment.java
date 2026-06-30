package com.xbot.xbot.ui;

import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.xbot.xbot.MainActivity;
import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.model.DetectionResult;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.web.VirtualPetWebView;
import com.xbot.xbot.R;

/** Main screen: debug preview overlay or virtual pet WebView. */
public class MainFragment extends Fragment {
    private AppViewModel viewModel;
    private FrameLayout contentContainer;
    private PreviewView previewView;
    private DetectionOverlayView overlayView;
    private VirtualPetWebView virtualPetWebView;
    private View doubleTapOverlay;

    public MainFragment() {
        super(R.layout.fragment_main);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        contentContainer = view.findViewById(R.id.content_container);
        doubleTapOverlay = view.findViewById(R.id.double_tap_overlay);
        ImageButton settingsButton = view.findViewById(R.id.btn_settings);

        settingsButton.setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openSettings();
            }
        });

        GestureDetector detector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        viewModel.triggerVoiceFromDoubleTap();
                        return true;
                    }
                });
        doubleTapOverlay.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });

        viewModel.getDisplaySettings().observe(getViewLifecycleOwner(), this::applyDisplayMode);
        viewModel.getDetectionResult().observe(getViewLifecycleOwner(), this::updateOverlay);
    }

    private void applyDisplayMode(DisplaySettings settings) {
        if (settings == null) {
            return;
        }
        contentContainer.removeAllViews();
        if (settings.debugMode) {
            showDebugMode();
        } else {
            showVirtualPet();
        }
    }

    private void showDebugMode() {
        previewView = new PreviewView(requireContext());
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        overlayView = new DetectionOverlayView(requireContext());

        contentContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        contentContainer.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        viewModel.getPerceptionPipeline().bindCamera(getViewLifecycleOwner(), previewView);
        updateOverlay(viewModel.getDetectionResult().getValue());
    }

    private void showVirtualPet() {
        viewModel.getPerceptionPipeline().startAnalysis(getViewLifecycleOwner());

        virtualPetWebView = new VirtualPetWebView(requireContext());
        contentContainer.addView(virtualPetWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        virtualPetWebView.bind(viewModel, getViewLifecycleOwner());
    }

    private void updateOverlay(@Nullable DetectionResult result) {
        if (overlayView == null || viewModel == null) {
            return;
        }
        DisplaySettings settings = viewModel.getSettings();
        overlayView.setData(
                result != null ? result : new DetectionResult(),
                settings,
                viewModel.getGazeZoneDetector());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        DisplaySettings settings = viewModel.getSettings();
        if (settings == null) {
            return;
        }
        if (settings.debugMode && previewView != null) {
            viewModel.getPerceptionPipeline().bindCamera(getViewLifecycleOwner(), previewView);
        } else if (!settings.debugMode) {
            viewModel.getPerceptionPipeline().startAnalysis(getViewLifecycleOwner());
        }
    }

    @Override
    public void onDestroyView() {
        if (virtualPetWebView != null) {
            virtualPetWebView.unbind();
        }
        super.onDestroyView();
    }
}
