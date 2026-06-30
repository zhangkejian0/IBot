package com.xbot.xbot.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.view.PreviewView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.xbot.xbot.R;
import com.xbot.xbot.data.FamilyRelation;
import com.xbot.xbot.data.PersonEntity;
import com.xbot.xbot.data.RoomConverters;
import com.xbot.xbot.model.IdentityMatch;
import com.xbot.xbot.viewmodel.AppViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Face enrollment screen with Face-ID style layout (mirrors Flutter
 * {@code FaceRegistrationScreen}).
 */
public class FaceEnrollmentFragment extends Fragment {
    private static final int REQUIRED_SAMPLES = 5;
    private static final long SAMPLE_TIMEOUT_MS = 6000L;
    private static final long TOTAL_DEADLINE_MS = 20000L;
    private static final long SAMPLE_GAP_MS = 350L;

    private enum EnrollState { IDLE, DETECTING, COLLECTING, SUCCESS, FAILED, DUPLICATE }

    private final ExecutorService enrollExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppViewModel viewModel;
    private FaceIdRingView scanRing;
    private PreviewView preview;
    private View resultOverlay;
    private View resultTint;
    private TextView resultIcon;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private EditText nameInput;
    private TextView relationValue;
    private TextView startButton;
    private View relationRow;

    private FamilyRelation selectedRelation = FamilyRelation.FRIEND;
    private EnrollState state = EnrollState.IDLE;
    private boolean running;
    private int collected;

    public FaceEnrollmentFragment() {
        super(R.layout.fragment_face_enrollment);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (!running) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });

        scanRing = view.findViewById(R.id.scan_ring);
        preview = view.findViewById(R.id.preview);
        resultOverlay = view.findViewById(R.id.result_overlay);
        resultTint = view.findViewById(R.id.result_tint);
        resultIcon = view.findViewById(R.id.result_icon);
        statusTitle = view.findViewById(R.id.status_title);
        statusSubtitle = view.findViewById(R.id.status_subtitle);
        nameInput = view.findViewById(R.id.input_name);
        relationValue = view.findViewById(R.id.relation_value);
        startButton = view.findViewById(R.id.btn_start);
        relationRow = view.findViewById(R.id.row_relation);

        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        clipPreviewCircle(preview);
        viewModel.getPerceptionPipeline().bindCamera(getViewLifecycleOwner(), preview);

        relationValue.setText(selectedRelation.label);
        relationRow.setOnClickListener(v -> {
            if (!running) {
                pickRelation();
            }
        });

        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateStartButton();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        startButton.setOnClickListener(v -> startEnrollment());
        updateUi();
    }

    private void clipPreviewCircle(View target) {
        target.setClipToOutline(true);
        target.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        target.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                v.invalidateOutline());
    }

    private void pickRelation() {
        FamilyRelation[] options = FamilyRelation.selectableValues();
        CharSequence[] labels = new CharSequence[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i].label;
        }
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == selectedRelation) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.enroll_relation_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedRelation = options[which];
                    relationValue.setText(selectedRelation.label);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startEnrollment() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            toast(R.string.enroll_name_required);
            return;
        }
        if (!viewModel.isFaceRecognitionAvailable()) {
            toast(R.string.enroll_model_unavailable);
            return;
        }

        running = true;
        collected = 0;
        state = EnrollState.DETECTING;
        resultOverlay.setVisibility(View.GONE);
        scanRing.startScanAnimation();
        updateUi();

        enrollExecutor.execute(() -> runEnrollmentLoop(name));
    }

    private void runEnrollmentLoop(String name) {
        List<List<Double>> embeddings = new ArrayList<>();
        Bitmap avatarThumb = null;
        long deadline = System.currentTimeMillis() + TOTAL_DEADLINE_MS;

        while (embeddings.size() < REQUIRED_SAMPLES && System.currentTimeMillis() < deadline) {
            postState(EnrollState.COLLECTING);
            final boolean[] done = {false};
            final AppViewModel.FaceCapture[] capture = {null};
            final String[] error = {null};

            mainHandler.post(() -> viewModel.captureFaceSample(SAMPLE_TIMEOUT_MS, (result, err) -> {
                capture[0] = result;
                error[0] = err;
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
            }));

            synchronized (done) {
                while (!done[0]) {
                    try {
                        done.wait(SAMPLE_TIMEOUT_MS + 500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            if (capture[0] == null || capture[0].embedding == null || capture[0].embedding.isEmpty()) {
                continue;
            }

            IdentityMatch existing = viewModel.findExistingIdentity(capture[0].embedding);
            if (existing != null) {
                finishEnrollment(EnrollState.DUPLICATE, getString(R.string.enroll_duplicate));
                return;
            }

            embeddings.add(capture[0].embedding);
            if (avatarThumb == null && capture[0].thumb != null) {
                avatarThumb = capture[0].thumb;
            } else if (capture[0].thumb != null) {
                capture[0].thumb.recycle();
            }

            int count = embeddings.size();
            mainHandler.post(() -> {
                collected = count;
                scanRing.setProgress((float) count / REQUIRED_SAMPLES);
                updateUi();
            });

            if (embeddings.size() < REQUIRED_SAMPLES) {
                try {
                    Thread.sleep(SAMPLE_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (embeddings.isEmpty()) {
            finishEnrollment(EnrollState.FAILED, getString(R.string.enroll_failed_no_face));
            return;
        }

        PersonEntity person = new PersonEntity();
        person.id = UUID.randomUUID().toString();
        person.name = name;
        person.relation = selectedRelation.key;
        person.embeddingsJson = RoomConverters.embeddingsToJson(embeddings);
        person.createdAt = System.currentTimeMillis();

        Bitmap thumb = avatarThumb;
        mainHandler.post(() -> {
            try {
                if (thumb != null) {
                    person.avatarPath = viewModel.saveAvatar(person.id, thumb);
                    thumb.recycle();
                }
                viewModel.savePerson(person, () -> {
                    state = EnrollState.SUCCESS;
                    running = false;
                    scanRing.stopScanAnimation();
                    showResultOverlay(true);
                    updateUi();
                    toast(getString(R.string.enroll_success_fmt, name));
                    mainHandler.postDelayed(() -> {
                        if (isAdded()) {
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }, 600L);
                });
            } catch (Exception e) {
                finishEnrollment(EnrollState.FAILED, getString(R.string.enroll_failed_generic));
            }
        });
    }

    private void finishEnrollment(EnrollState next, String message) {
        mainHandler.post(() -> {
            state = next;
            running = false;
            scanRing.stopScanAnimation();
            showResultOverlay(next == EnrollState.SUCCESS);
            updateUi();
            toast(message);
        });
    }

    private void postState(EnrollState next) {
        mainHandler.post(() -> {
            state = next;
            updateUi();
        });
    }

    private void showResultOverlay(boolean success) {
        if (state != EnrollState.SUCCESS && state != EnrollState.FAILED && state != EnrollState.DUPLICATE) {
            resultOverlay.setVisibility(View.GONE);
            return;
        }
        resultOverlay.setVisibility(View.VISIBLE);
        if (success) {
            resultTint.setBackgroundResource(R.drawable.bg_result_overlay_success);
            resultIcon.setText("✓");
            resultIcon.setTextColor(getResources().getColor(R.color.accent_green, null));
        } else {
            resultTint.setBackgroundResource(R.drawable.bg_result_overlay_error);
            resultIcon.setText("!");
            resultIcon.setTextColor(getResources().getColor(R.color.accent_red, null));
        }
    }

    private void updateUi() {
        int ringColor = getRingColor();
        scanRing.setRingColors(ringColor, getResources().getColor(R.color.separator, null));
        statusTitle.setText(getInstructionTitle());
        statusTitle.setTextColor(ringColor);
        statusSubtitle.setText(getInstructionSubtitle());
        updateStartButton();
        relationRow.setEnabled(!running);
        nameInput.setEnabled(!running);
        requireView().findViewById(R.id.toolbar).setEnabled(!running);
    }

    private void updateStartButton() {
        boolean canStart = !nameInput.getText().toString().trim().isEmpty() && !running;
        startButton.setEnabled(canStart);
        startButton.setBackgroundResource(canStart
                ? R.drawable.bg_enroll_button
                : R.drawable.bg_enroll_button_disabled);
        startButton.setTextColor(getResources().getColor(
                canStart ? R.color.text_primary : R.color.text_secondary, null));
        if (running) {
            startButton.setText(getString(R.string.enroll_collecting_fmt, collected, REQUIRED_SAMPLES));
        } else {
            startButton.setText(R.string.enroll_start);
        }
    }

    private int getRingColor() {
        switch (state) {
            case FAILED:
            case DUPLICATE:
                return getResources().getColor(R.color.accent_red, null);
            case SUCCESS:
                return getResources().getColor(R.color.accent_green, null);
            default:
                return getResources().getColor(R.color.accent_blue, null);
        }
    }

    private String getInstructionTitle() {
        switch (state) {
            case DETECTING:
                return getString(R.string.enroll_status_detecting_title);
            case COLLECTING:
                return getString(R.string.enroll_status_collecting_title);
            case SUCCESS:
                return getString(R.string.enroll_status_success_title);
            case FAILED:
                return getString(R.string.enroll_status_failed_title);
            case DUPLICATE:
                return getString(R.string.enroll_status_duplicate_title);
            case IDLE:
            default:
                return running
                        ? getString(R.string.enroll_status_ready_title)
                        : getString(R.string.enroll_status_idle_title);
        }
    }

    private String getInstructionSubtitle() {
        switch (state) {
            case DETECTING:
            case COLLECTING:
                return running
                        ? getString(R.string.enroll_status_collecting_subtitle, collected, REQUIRED_SAMPLES)
                        : getString(R.string.enroll_status_detecting_subtitle);
            case FAILED:
                return getString(R.string.enroll_status_failed_subtitle);
            case DUPLICATE:
                return getString(R.string.enroll_status_duplicate_subtitle);
            case SUCCESS:
                return getString(R.string.enroll_status_success_subtitle);
            case IDLE:
            default:
                return getString(R.string.enroll_status_idle_subtitle, REQUIRED_SAMPLES);
        }
    }

    private void toast(int resId) {
        toast(getString(resId));
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        scanRing.stopScanAnimation();
        enrollExecutor.shutdownNow();
        viewModel.getPerceptionPipeline().unbindCamera();
        super.onDestroyView();
    }
}
