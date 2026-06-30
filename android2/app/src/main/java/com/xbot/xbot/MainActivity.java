package com.xbot.xbot;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.xbot.xbot.core.AppPhase;
import com.xbot.xbot.ui.LoadingFragment;
import com.xbot.xbot.ui.MainFragment;
import com.xbot.xbot.ui.OnboardingFragment;
import com.xbot.xbot.viewmodel.AppViewModel;
import com.xbot.xbot.R;

/** Landscape fullscreen single-activity host; swaps fragments by {@link AppPhase}. */
public class MainActivity extends AppCompatActivity {
    private AppViewModel viewModel;
    private AppPhase currentPhase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.hide(WindowInsetsCompat.Type.systemBars());
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        viewModel = new ViewModelProvider(this).get(AppViewModel.class);
        viewModel.getPhase().observe(this, this::onPhaseChanged);

        if (savedInstanceState == null) {
            showPhase(AppPhase.LOADING);
        }
    }

    public AppViewModel getAppViewModel() {
        return viewModel;
    }

    private void onPhaseChanged(AppPhase phase) {
        if (phase == null || phase == currentPhase) {
            return;
        }
        if (phase == AppPhase.READY && currentPhase == AppPhase.READY) {
            return;
        }
        if (phase == AppPhase.ONBOARDING) {
            getSupportFragmentManager().popBackStack(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        currentPhase = phase;
        showPhase(phase);
    }

    private void showPhase(AppPhase phase) {
        Fragment fragment;
        switch (phase) {
            case ONBOARDING:
                fragment = new OnboardingFragment();
                break;
            case READY:
                fragment = new MainFragment();
                break;
            case LOADING:
            case ERROR:
            case PERMISSION_DENIED:
            default:
                fragment = new LoadingFragment();
                break;
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    public void openSettings() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new com.xbot.xbot.ui.SettingsFragment())
                .addToBackStack("settings")
                .commit();
    }

    public void popBackStack() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else if (viewModel.getPhase().getValue() == AppPhase.READY) {
            showPhase(AppPhase.READY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), decor);
            insets.hide(WindowInsetsCompat.Type.systemBars());
        }
    }
}
