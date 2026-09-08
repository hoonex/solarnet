package com.hoonex.solarnet.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoonex.solarnet.bluetoothclassic.SolarBluetoothClassicBridge;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final String SERVICE_UUID = "7d8f3a10-7b8a-4f66-9c38-63f04cb61b0e";
    private static final String SERVICE_NAME = "SolarNet Transport Probe";

    private final AtomicLong nextRequestId = new AtomicLong();
    private final List<String> connections = new ArrayList<>();
    private SolarBluetoothClassicBridge bridge;
    private TextView statusView;
    private TextView logView;
    private TextView soakView;
    private LinearLayout deviceList;
    private ProbeSoakController soakController;
    private boolean hostMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        bridge = new SolarBluetoothClassicBridge(this, new ProbeCallback());
        soakController = new ProbeSoakController(bridge, this::nextId, new ProbeSoakController.Listener() {
            @Override
            public void onProgress(ProbeSoakStats.Snapshot snapshot) {
                runOnUiThread(() -> {
                    if (soakView != null) soakView.setText("Soak: " + snapshot.toSummary());
                });
            }

            @Override
            public void onFinished(ProbeSoakStats.Snapshot snapshot, String reason) {
                String result = buildSoakResultJson(snapshot, reason);
                Log.i("SolarNetProbe", "SOLARNET_PROBE_RESULT " + result);
                log("SOAK FINISH " + snapshot.toSummary() + " reason=" + reason);
            }

            @Override
            public void onLog(String message) {
                log(message);
            }
        });
        log("Probe ready. Pair the two phones in Android settings before the RFCOMM test.");
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("SolarNet Transport Probe");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bluetooth Classic RFCOMM • paired-device physical test");
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("Status: idle");
        statusView.setTextSize(17f);
        root.addView(statusView);

        root.addView(button("Request Bluetooth permission", v -> requestBluetoothPermission()));
        root.addView(button("Open Android Bluetooth settings", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
        root.addView(button("HOST: start RFCOMM server", v -> startHost()));
        root.addView(button("CLIENT: refresh paired devices", v -> refreshPairedDevices()));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        root.addView(button("Broadcast PING", v -> broadcastPing()));
        root.addView(button("CLIENT: start 10-minute soak", v -> startSoak()));
        root.addView(button("Stop soak", v -> stopSoak()));

        soakView = new TextView(this);
        soakView.setText("Soak: idle");
        soakView.setTextSize(14f);
        soakView.setTextIsSelectable(true);
        soakView.setPadding(0, dp(8), 0, 0);
        root.addView(soakView);

        root.addView(button("Stop all connections", v -> stopAll()));

        TextView logTitle = new TextView(this);
        logTitle.setText("Event log");
        logTitle.setTextSize(18f);
        logTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(13f);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        return scroll;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) {
            log("Bluetooth runtime permission is not required below Android 12.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            log("BLUETOOTH_CONNECT is already granted.");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        log("BLUETOOTH_CONNECT permission: " + (granted ? "granted" : "DENIED"));
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void startHost() {
        if (!hasConnectPermission()) {
            log("Grant BLUETOOTH_CONNECT first.");
            requestBluetoothPermission();
            return;
        }
        hostMode = true;
        setStatus("starting host");
        bridge.startServer(nextId(), SERVICE_NAME, SERVICE_UUID);
    }

    private void refreshPairedDevices() {
        if (!hasConnectPermission()) {
            log("Grant BLUETOOTH_CONNECT first.");
            requestBluetoothPermission();
            return;
        }
        hostMode = false;
        deviceList.removeAllViews();
        try {
            String[] devices = bridge.getBondedDevices();
            log("Paired devices: " + devices.length);
            if (devices.length == 0) {
                TextView empty = new TextView(this);
                empty.setText("No paired devices. Pair the phones in Android settings first.");
                deviceList.addView(empty);
                return;
            }
            for (String encoded : devices) addPairedDeviceButton(encoded);
        } catch (Throwable t) {
            log("ERROR paired devices: " + message(t));
        }
    }

    private void addPairedDeviceButton(String encoded) {
        int split = encoded.indexOf('\n');
        String address = split < 0 ? encoded : encoded.substring(0, split);
        String name = split < 0 ? encoded : encoded.substring(split + 1);
        Button connect = button("Connect to " + name + "  [" + address + "]", v -> connect(address));
        deviceList.addView(connect);
    }

    private void connect(String address) {
        hostMode = false;
        setStatus("connecting to " + address);
        bridge.connect(nextId(), address, SERVICE_UUID);
    }

    private void broadcastPing() {
        String payload = "PING " + System.currentTimeMillis();
        bridge.broadcastBytes(nextId(), payload.getBytes(StandardCharsets.UTF_8));
        log("TX broadcast: " + payload + " (connections=" + connections.size() + ")");
    }

    private void startSoak() {
        if (hostMode) {
            log("Start the timed soak on the CLIENT phone; the HOST only echoes probe PING packets.");
            return;
        }
        synchronized (connections) {
            if (connections.isEmpty()) {
                log("Connect to the host before starting the soak.");
                return;
            }
        }
        try {
            String runId = soakController.start();
            setStatus("soak running " + runId);
        } catch (Throwable t) {
            log("ERROR start soak: " + message(t));
        }
    }

    private void stopSoak() {
        if (soakController == null || !soakController.isRunning()) {
            log("No soak run is active.");
            return;
        }
        soakController.stop("manual-stop");
        setStatus("soak stopped");
    }

    private void stopAll() {
        if (soakController != null && soakController.isRunning()) soakController.stop("stop-all");
        bridge.stopAll(nextId());
        synchronized (connections) { connections.clear(); }
        setStatus("stopped");
    }

    private long nextId() {
        return nextRequestId.incrementAndGet();
    }

    private void setStatus(String status) {
        runOnUiThread(() -> statusView.setText("Status: " + status));
    }

    private void log(String message) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            String current = logView == null ? "" : logView.getText().toString();
            String next = current + time + "  " + message + "\n";
            if (next.length() > 24000) next = next.substring(next.length() - 24000);
            if (logView != null) logView.setText(next);
        });
    }

    private String buildSoakResultJson(ProbeSoakStats.Snapshot snapshot, String reason) {
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        return "{" +
                "\"transport\":\"bluetooth-classic\"," +
                "\"probeVersion\":\"0.2.0\"," +
                "\"deviceModel\":\"" + jsonEscape(device) + "\"," +
                "\"sdkInt\":" + Build.VERSION.SDK_INT + "," +
                "\"result\":" + snapshot.toJson(reason) +
                "}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.length() == 0 ? t.getClass().getSimpleName() : text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (soakController != null) {
            try { soakController.close(); } catch (Throwable ignored) { }
        }
        if (bridge != null) {
            try { bridge.stopAll(nextId()); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private final class ProbeCallback implements SolarBluetoothClassicBridge.Callback {
        @Override
        public void onOperationResult(long requestId, boolean success, String error) {
            if (soakController != null && soakController.onOperationResult(requestId, success, error)) return;
            log("op#" + requestId + " " + (success ? "OK" : "FAILED: " + error));
        }

        @Override
        public void onConnected(String connectionId, String deviceAddress, String deviceName) {
            synchronized (connections) {
                if (!connections.contains(connectionId)) connections.add(connectionId);
            }
            setStatus("connected " + deviceName);
            log("CONNECTED id=" + connectionId + " address=" + deviceAddress + " name=" + deviceName);
        }

        @Override
        public void onDisconnected(String connectionId) {
            if (soakController != null) soakController.onDisconnected();
            synchronized (connections) { connections.remove(connectionId); }
            setStatus("disconnected " + connectionId);
            log("DISCONNECTED id=" + connectionId);
        }

        @Override
        public void onBytesReceived(String connectionId, byte[] payload) {
            if (soakController != null && soakController.handleBytes(connectionId, payload, hostMode)) return;
            String text = new String(payload, StandardCharsets.UTF_8);
            log("RX " + connectionId + ": " + text);
            if (hostMode && text.startsWith("PING ")) {
                String pong = "PONG " + text.substring(5);
                bridge.sendBytes(nextId(), connectionId, pong.getBytes(StandardCharsets.UTF_8));
                log("TX " + connectionId + ": " + pong);
            }
        }

        @Override
        public void onError(String operation, String message) {
            if (soakController != null) soakController.onBridgeError(operation, message);
            setStatus("error: " + operation);
            log("ERROR " + operation + ": " + message);
        }
    }
}
