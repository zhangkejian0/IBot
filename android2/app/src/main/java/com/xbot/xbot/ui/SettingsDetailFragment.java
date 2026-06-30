package com.xbot.xbot.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.xbot.xbot.MainActivity;
import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.R;

/** Advanced settings: base URL, wake word, log server toggle. */
public class SettingsDetailFragment extends Fragment {
    private AppViewModel viewModel;
    private EditText baseUrlInput;
    private EditText wakeWordInput;
    private SwitchCompat logServerSwitch;

    public SettingsDetailFragment() {
        super(R.layout.fragment_settings_detail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        baseUrlInput = view.findViewById(R.id.input_base_url);
        wakeWordInput = view.findViewById(R.id.input_wake_word);
        logServerSwitch = view.findViewById(R.id.switch_log_server);
        Button saveButton = view.findViewById(R.id.btn_save);

        baseUrlInput.setText(viewModel.getPophieBaseUrl());
        wakeWordInput.setText(viewModel.getSettings().wakeWord);

        DisplaySettings settings = viewModel.getSettings();
        logServerSwitch.setChecked(settings.personaLogServerEnabled);
        logServerSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (button.isPressed()) {
                viewModel.updateSettings(() -> viewModel.getSettings().personaLogServerEnabled = checked);
            }
        });

        saveButton.setOnClickListener(v -> save());

        viewModel.getDisplaySettings().observe(getViewLifecycleOwner(), s -> {
            if (s != null && !logServerSwitch.isPressed()) {
                logServerSwitch.setChecked(s.personaLogServerEnabled);
                if (wakeWordInput.getText().toString().isEmpty()) {
                    wakeWordInput.setText(s.wakeWord);
                }
            }
        });
    }

    private void save() {
        String baseUrl = baseUrlInput.getText().toString().trim();
        String wakeWord = wakeWordInput.getText().toString().trim();
        viewModel.setPophieBaseUrl(baseUrl);
        viewModel.updateSettings(() -> {
            if (!wakeWord.isEmpty()) {
                viewModel.getSettings().wakeWord = wakeWord;
            }
        });
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).popBackStack();
        } else {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }
}
