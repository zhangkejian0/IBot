package com.xbot.xbot.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.xbot.xbot.data.Gender;
import com.xbot.xbot.data.OwnerProfile;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.R;

/** First-run wizard: nickname, robot name, gender, save owner profile. */
public class OnboardingFragment extends Fragment {
    private AppViewModel viewModel;
    private EditText nicknameInput;
    private EditText robotNameInput;
    private Spinner genderSpinner;

    public OnboardingFragment() {
        super(R.layout.fragment_onboarding);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        nicknameInput = view.findViewById(R.id.input_nickname);
        robotNameInput = view.findViewById(R.id.input_robot_name);
        genderSpinner = view.findViewById(R.id.spinner_gender);
        Button saveButton = view.findViewById(R.id.btn_save);

        robotNameInput.setText("狗蛋");
        genderSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{Gender.MALE.label, Gender.FEMALE.label, Gender.OTHER.label}));

        saveButton.setOnClickListener(v -> saveProfile());
    }

    private void saveProfile() {
        String nickname = nicknameInput.getText().toString().trim();
        if (nickname.isEmpty()) {
            Toast.makeText(requireContext(), R.string.onboarding_nickname_required, Toast.LENGTH_SHORT).show();
            return;
        }
        String robotName = robotNameInput.getText().toString().trim();
        if (robotName.isEmpty()) {
            robotName = "狗蛋";
        }
        Gender gender = Gender.values()[genderSpinner.getSelectedItemPosition()];

        OwnerProfile profile = new OwnerProfile();
        profile.nickname = nickname;
        profile.robotName = robotName;
        profile.gender = gender;
        profile.faceRegistered = false;
        profile.createdAt = System.currentTimeMillis();

        try {
            viewModel.completeOnboarding(profile);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.error_generic) + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
