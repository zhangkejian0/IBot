package com.xbot.xbot.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.xbot.xbot.MainActivity;
import com.xbot.xbot.core.AppPhase;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.R;

/** Loading screen: permissions + {@link AppViewModel#initialize()}. */
public class LoadingFragment extends Fragment {
    private AppViewModel viewModel;
    private ProgressBar progressBar;
    private TextView messageView;
    private TextView errorView;
    private Button retryButton;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean camera = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean mic = Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO));
                if (!camera) {
                    viewModel.onPermissionsDenied();
                    showError(viewModel.getErrorMessage().getValue());
                    return;
                }
                if (mic) {
                    viewModel.getVoiceAssistant().markAvailable();
                } else {
                    viewModel.getVoiceAssistant().markUnavailable("未获得麦克风权限");
                }
                viewModel.initialize();
            });

    public LoadingFragment() {
        super(R.layout.fragment_loading);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        progressBar = view.findViewById(R.id.progress_bar);
        messageView = view.findViewById(R.id.loading_message);
        errorView = view.findViewById(R.id.error_message);
        retryButton = view.findViewById(R.id.btn_retry);

        retryButton.setOnClickListener(v -> requestPermissionsAndInit());

        viewModel.getLoadingProgress().observe(getViewLifecycleOwner(), p -> {
            if (p != null) {
                progressBar.setProgress((int) (p * 100));
            }
        });
        viewModel.getLoadingMessage().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null) {
                messageView.setText(msg);
            }
        });
        viewModel.getPhase().observe(getViewLifecycleOwner(), phase -> {
            if (phase == AppPhase.ERROR || phase == AppPhase.PERMISSION_DENIED) {
                showError(viewModel.getErrorMessage().getValue());
            }
        });

        requestPermissionsAndInit();
    }

    private void requestPermissionsAndInit() {
        errorView.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        messageView.setVisibility(View.VISIBLE);

        boolean hasCamera = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasMic = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasMic) {
            viewModel.getVoiceAssistant().markAvailable();
            viewModel.initialize();
            return;
        }
        if (hasCamera) {
            permissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
            return;
        }
        permissionLauncher.launch(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        });
    }

    private void showError(@Nullable String message) {
        progressBar.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(message != null ? message : getString(R.string.error_generic));
        retryButton.setVisibility(View.VISIBLE);
    }
}
