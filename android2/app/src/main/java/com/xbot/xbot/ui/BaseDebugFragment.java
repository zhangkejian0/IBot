package com.xbot.xbot.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.xbot.xbot.R;
import com.xbot.xbot.base.BaseService;
import com.xbot.xbot.viewmodel.AppViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Classic Bluetooth SPP base debug / manual control screen. */
public class BaseDebugFragment extends Fragment {
    private AppViewModel viewModel;
    private TextView connectionStatus;
    private TextView baseStatusView;
    private Spinner deviceSpinner;
    private List<BluetoothDevice> bondedDevices;

    public BaseDebugFragment() {
        super(R.layout.fragment_base_debug);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        BaseService base = viewModel.getBaseService();

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        connectionStatus = view.findViewById(R.id.connection_status);
        baseStatusView = view.findViewById(R.id.base_status);
        deviceSpinner = view.findViewById(R.id.device_spinner);
        Button connectButton = view.findViewById(R.id.btn_connect);
        Button disconnectButton = view.findViewById(R.id.btn_disconnect);

        refreshDeviceList();
        connectButton.setOnClickListener(v -> {
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                return;
            }
            int idx = deviceSpinner.getSelectedItemPosition();
            if (idx >= 0 && idx < bondedDevices.size()) {
                base.connect(bondedDevices.get(idx));
            }
        });
        disconnectButton.setOnClickListener(v -> base.disconnect());

        base.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            if (state != null) {
                connectionStatus.setText(getString(R.string.base_connection_fmt, state.name()));
            }
        });
        base.getStatus().observe(getViewLifecycleOwner(), status -> {
            if (status != null) {
                baseStatusView.setText(String.format(Locale.US,
                        "dof=%d mode=%s yaw=%.1f pitch=%.1f roll=%.1f",
                        status.dof, status.mode, status.yaw, status.pitch, status.roll));
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void refreshDeviceList() {
        bondedDevices = viewModel.getBaseService().getBondedDevices();
        String[] labels = new String[bondedDevices.size()];
        for (int i = 0; i < bondedDevices.size(); i++) {
            BluetoothDevice d = bondedDevices.get(i);
            String name = d.getName();
            labels[i] = (name != null ? name : d.getAddress()) + " (" + d.getAddress() + ")";
        }
        deviceSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, labels));
    }
}
