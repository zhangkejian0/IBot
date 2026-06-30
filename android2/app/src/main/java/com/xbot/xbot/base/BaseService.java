package com.xbot.xbot.base;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Classic Bluetooth SPP (RFCOMM) base controller service.
 */
public class BaseService {
    private static final String TAG = "BaseService";
    /** Standard SPP UUID (Serial Port Profile). */
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Gson GSON = new Gson();

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    public static class BaseStatus {
        public int dof = 2;
        public String mode = "idle";
        public double yaw;
        public double pitch;
        public double roll;

        public BaseStatus copyWith(
                @Nullable Integer dof,
                @Nullable String mode,
                @Nullable Double yaw,
                @Nullable Double pitch,
                @Nullable Double roll) {
            BaseStatus s = new BaseStatus();
            s.dof = dof != null ? dof : this.dof;
            s.mode = mode != null ? mode : this.mode;
            s.yaw = yaw != null ? yaw : this.yaw;
            s.pitch = pitch != null ? pitch : this.pitch;
            s.roll = roll != null ? roll : this.roll;
            return s;
        }
    }

    private final BaseProtocol protocol = new BaseProtocol();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<ConnectionState> connectionState =
            new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<BaseStatus> status = new MutableLiveData<>(new BaseStatus());
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);

    @Nullable private BluetoothSocket socket;
    @Nullable private InputStream input;
    @Nullable private OutputStream output;
    @Nullable private BluetoothDevice device;
    @Nullable private FrameListener frameListener;

    public interface FrameListener {
        void onFrame(Map<String, Object> frame);
    }

    public LiveData<ConnectionState> getConnectionState() {
        return connectionState;
    }

    public LiveData<BaseStatus> getStatus() {
        return status;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    public BluetoothDevice getDevice() {
        return device;
    }

    public boolean isConnected() {
        return connectionState.getValue() == ConnectionState.CONNECTED;
    }

    public void setFrameListener(@Nullable FrameListener listener) {
        this.frameListener = listener;
    }

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getBondedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return new ArrayList<>();
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        return bonded != null ? new ArrayList<>(bonded) : new ArrayList<>();
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice target) {
        if (connectionState.getValue() == ConnectionState.CONNECTING) {
            return;
        }
        device = target;
        connectionState.postValue(ConnectionState.CONNECTING);
        io.execute(() -> {
            try {
                disconnectInternal("reconnect");
                BluetoothSocket s = target.createRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
                socket = s;
                input = s.getInputStream();
                output = s.getOutputStream();
                protocol.reset();
                connectionState.postValue(ConnectionState.CONNECTED);
                errorMessage.postValue(null);
                startReader();
                Log.i(TAG, "connected " + target.getAddress());
            } catch (IOException e) {
                Log.e(TAG, "connect failed", e);
                disconnectInternal("连接失败: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        io.execute(() -> disconnectInternal("主动断开"));
    }

    public boolean send(String cmd, Map<String, Object> params) {
        if (!isConnected() || output == null) {
            Log.w(TAG, "send while disconnected");
            return false;
        }
        byte[] bytes = protocol.encode(cmd, params);
        try {
            output.write(bytes);
            output.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "send failed", e);
            disconnectInternal("发送失败: " + e.getMessage());
            return false;
        }
    }

    private void startReader() {
        if (!readerRunning.compareAndSet(false, true)) {
            return;
        }
        io.execute(() -> {
            byte[] buf = new byte[4096];
            try {
                InputStream in = input;
                while (readerRunning.get() && in != null) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    List<Map<String, Object>> frames = protocol.feed(buf, 0, n);
                    for (Map<String, Object> frame : frames) {
                        handleFrame(frame);
                        FrameListener listener = frameListener;
                        if (listener != null) {
                            listener.onFrame(frame);
                        }
                        Log.d(TAG, "RX " + GSON.toJson(frame));
                    }
                }
            } catch (IOException e) {
                if (readerRunning.get()) {
                    Log.e(TAG, "read error", e);
                }
            } finally {
                readerRunning.set(false);
                disconnectInternal("设备断开");
            }
        });
    }

    private void handleFrame(Map<String, Object> frame) {
        Object cmdObj = frame.get("cmd");
        if (!(cmdObj instanceof String)) {
            return;
        }
        String cmd = (String) cmdObj;
        BaseStatus current = status.getValue();
        if (current == null) {
            current = new BaseStatus();
        }
        switch (cmd) {
            case "status":
                BaseStatus updated = new BaseStatus();
                updated.dof = intVal(frame.get("dof"), current.dof);
                updated.mode = stringVal(frame.get("mode"), current.mode);
                updated.yaw = numVal(frame.get("yaw"), current.yaw);
                updated.pitch = numVal(frame.get("pitch"), current.pitch);
                updated.roll = numVal(frame.get("roll"), current.roll);
                status.postValue(updated);
                break;
            case "version":
                Object dof = frame.get("dof");
                if (dof instanceof Number) {
                    status.postValue(current.copyWith(((Number) dof).intValue(), null, null, null, null));
                }
                break;
            default:
                break;
        }
    }

    private void disconnectInternal(String reason) {
        readerRunning.set(false);
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        input = null;
        output = null;
        socket = null;
        protocol.reset();
        connectionState.postValue(ConnectionState.DISCONNECTED);
        errorMessage.postValue(reason);
        Log.i(TAG, reason);
    }

    public void release() {
        disconnect();
        io.shutdownNow();
    }

    private static int intVal(@Nullable Object v, int fallback) {
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static double numVal(@Nullable Object v, double fallback) {
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    private static String stringVal(@Nullable Object v, String fallback) {
        return v instanceof String ? (String) v : fallback;
    }
}
