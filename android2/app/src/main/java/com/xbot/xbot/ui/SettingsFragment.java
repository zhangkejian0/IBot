package com.xbot.xbot.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.xbot.xbot.MainActivity;
import com.xbot.xbot.R;
import com.xbot.xbot.core.DisplaySettings;
import com.xbot.xbot.data.OwnerProfile;
import com.xbot.xbot.viewmodel.AppViewModel;

/** Settings toggles for perception, voice, identity, and debug mode. */
public class SettingsFragment extends Fragment {
    private AppViewModel viewModel;
    private boolean suppressToggleEvents;

    private View ownerRow;
    private TextView ownerSubtitle;
    private TextView knowMeSubtitle;
    private View friendsRow;
    private TextView friendsSubtitle;
    private View resetOwnerRow;
    private TextView modelStatusView;
    private SwitchCompat identitySwitch;

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).popBackStack();
            } else {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });

        ownerRow = view.findViewById(R.id.row_owner);
        ownerSubtitle = view.findViewById(R.id.owner_subtitle);
        knowMeSubtitle = view.findViewById(R.id.know_me_subtitle);
        friendsRow = view.findViewById(R.id.row_friends);
        friendsSubtitle = view.findViewById(R.id.friends_subtitle);
        resetOwnerRow = view.findViewById(R.id.row_reset_owner);
        modelStatusView = view.findViewById(R.id.identity_model_status);
        identitySwitch = view.findViewById(R.id.switch_identity);

        bindSwitch(view, R.id.switch_debug, (s, checked) -> s.debugMode = checked);
        bindSwitch(view, R.id.switch_face, (s, checked) -> s.faceEnabled = checked);
        bindSwitch(view, R.id.switch_hand, (s, checked) -> s.handEnabled = checked);
        bindSwitch(view, R.id.switch_identity, (s, checked) -> s.identityEnabled = checked);
        bindSwitch(view, R.id.switch_pose, (s, checked) -> s.poseEnabled = checked);
        bindSwitch(view, R.id.switch_voice, (s, checked) -> s.voiceEnabled = checked);
        bindSwitch(view, R.id.switch_wake_word, (s, checked) -> s.wakeWordEnabled = checked);

        view.findViewById(R.id.row_face_enrollment).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new FaceEnrollmentFragment())
                        .addToBackStack("face_enrollment")
                        .commit());

        friendsRow.setOnClickListener(v -> new FriendListBottomSheet().show(
                getParentFragmentManager(), "friends"));

        resetOwnerRow.setOnClickListener(v -> confirmResetOwner());

        view.findViewById(R.id.row_detail).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new SettingsDetailFragment())
                        .addToBackStack("settings_detail")
                        .commit());

        view.findViewById(R.id.row_base_debug).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new BaseDebugFragment())
                        .addToBackStack("base_debug")
                        .commit());

        viewModel.getDisplaySettings().observe(getViewLifecycleOwner(), this::applySettings);
        viewModel.getPeopleRevision().observe(getViewLifecycleOwner(), rev -> refreshIdentitySection());
        refreshIdentitySection();
    }

    private void confirmResetOwner() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reset_owner)
                .setMessage(R.string.settings_reset_owner_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_owner_action, (d, w) ->
                        viewModel.resetOwnerAndRestartOnboarding())
                .show();
    }

    private void refreshIdentitySection() {
        if (getView() == null) {
            return;
        }
        int peopleCount = viewModel.getPersonRepository().getPeople().size();
        knowMeSubtitle.setText(getString(R.string.settings_know_me_subtitle, peopleCount));

        boolean ownerRegistered = viewModel.isOwnerRegistered();
        ownerRow.setVisibility(ownerRegistered ? View.VISIBLE : View.GONE);
        resetOwnerRow.setVisibility(ownerRegistered ? View.VISIBLE : View.GONE);
        if (ownerRegistered) {
            OwnerProfile owner = viewModel.getOwnerProfile();
            ownerSubtitle.setText(owner != null && owner.nickname != null ? owner.nickname : "—");
        }

        friendsRow.setVisibility(peopleCount > 0 ? View.VISIBLE : View.GONE);
        if (peopleCount > 0) {
            friendsSubtitle.setText(getString(R.string.settings_friends_subtitle, peopleCount));
        }

        boolean modelReady = viewModel.isFaceRecognitionAvailable();
        identitySwitch.setEnabled(modelReady);
        String status = viewModel.getFaceRecognitionStatus();
        modelStatusView.setText(status != null ? status : "");
    }

    private interface SettingMutator {
        void apply(DisplaySettings settings, boolean checked);
    }

    private void bindSwitch(View root, int id, SettingMutator mutator) {
        SwitchCompat toggle = root.findViewById(id);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (suppressToggleEvents || !button.isPressed()) {
                return;
            }
            viewModel.updateSettings(() -> mutator.apply(viewModel.getSettings(), checked));
        });
    }

    private void applySettings(DisplaySettings settings) {
        if (settings == null || getView() == null) {
            return;
        }
        suppressToggleEvents = true;
        ((SwitchCompat) requireView().findViewById(R.id.switch_debug)).setChecked(settings.debugMode);
        ((SwitchCompat) requireView().findViewById(R.id.switch_face)).setChecked(settings.faceEnabled);
        ((SwitchCompat) requireView().findViewById(R.id.switch_hand)).setChecked(settings.handEnabled);
        ((SwitchCompat) requireView().findViewById(R.id.switch_identity)).setChecked(settings.identityEnabled);
        ((SwitchCompat) requireView().findViewById(R.id.switch_pose)).setChecked(settings.poseEnabled);
        ((SwitchCompat) requireView().findViewById(R.id.switch_voice)).setChecked(settings.voiceEnabled);
        ((SwitchCompat) requireView().findViewById(R.id.switch_wake_word)).setChecked(settings.wakeWordEnabled);
        suppressToggleEvents = false;
        refreshIdentitySection();
    }
}
