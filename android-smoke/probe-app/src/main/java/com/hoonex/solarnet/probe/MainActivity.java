package com.hoonex.solarnet.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import com.hoonex.solarnet.nearby.SolarNearbyBridge;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private enum TransportMode {
        BLUETOOTH_CLASSIC("bluetooth-classic", "Bluetooth Classic RFCOMM"),
        NEARBY("nearby-connections", "Google Nearby Connections");

        final String evidenceName;
        final String displayName;

        TransportMode(String evidenceName, String displayName) {
            this.evidenceName = evidenceName;
            this.displayName = displayName;
        }
    }

    private static final int REQUEST_RADIO_PERMISSIONS = 1001;
    private static final String BLUETOOTH_SERVICE_UUID = "7d8f3a10-7b8a-4f66-9c38-63f04cb61b0e";
    private static final String BLUETOOTH_SERVICE_NAME = "SolarNet Transport Probe";
    private static final String NEARBY_SERVICE_ID = "com.hoonex.solarnet.probe";
    private static final String NEARBY_STRATEGY = "STAR";
    private static final String PROBE_VERSION = "0.4.0";

    private final AtomicLong nextRequestId = new AtomicLong();
    private final List<String> bluetoothConnections = new ArrayList<>();
    private final List<String> nearbyConnections = new ArrayList<>();
    private final List<String> nearbyDiscovered = new ArrayList<>();

    private SolarBluetoothClassicBridge bluetoothBridge;
    private SolarNearbyBridge nearbyBridge;
    private ProbeSoakController bluetoothSoak;
    private ProbeSoakController nearbySoak;
    private TextView statusView;
    private TextView logView;
    private TextView soakView;
    private LinearLayout deviceList;
    private TransportMode activeMode;
    private boolean hostMode;
    private ProbeEvidenceSnapshot bluetoothStartEvidence;
    private ProbeEvidenceSnapshot nearbyStartEvidence;
    private volatile String installedApkSha256;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        new Thread(() -> {
            installedApkSha256 = ProbeDeviceEvidence.computeInstalledApkSha256(getApplicationContext());
            log("Evidence APK SHA-256 ready: " + installedApkSha256);
        }, "solarnet-probe-apk-hash").start();

        bluetoothBridge = new SolarBluetoothClassicBridge(this, new BluetoothCallback());
        nearbyBridge = new SolarNearbyBridge(this, new NearbyCallback());
        bluetoothSoak = new ProbeSoakController(
                new ProbeByteLink() {
                    @Override
                    public void sendBytes(long requestId, String connectionId, byte[] payload) {
                        bluetoothBridge.sendBytes(requestId, connectionId, payload);
                    }

                    @Override
                    public void broadcastBytes(long requestId, byte[] payload) {
                        bluetoothBridge.broadcastBytes(requestId, payload);
                    }
                },
                this::nextId,
                createSoakListener(TransportMode.BLUETOOTH_CLASSIC));
        nearbySoak = new ProbeSoakController(
                new ProbeByteLink() {
                    @Override
                    public void sendBytes(long requestId, String connectionId, byte[] payload) {
                        nearbyBridge.sendBytes(requestId, connectionId, payload);
                    }

                    @Override
                    public void broadcastBytes(long requestId, byte[] payload) {
                        nearbyBridge.broadcastBytes(requestId, payload);
                    }
                },
                this::nextId,
                createSoakListener(TransportMode.NEARBY));

        log("Probe ready. Choose Bluetooth Classic or Nearby, connect two phones, then run the client soak.");
    }

    private ProbeSoakController.Listener createSoakListener(TransportMode mode) {
        return new ProbeSoakController.Listener() {
            @Override
            public void onProgress(ProbeSoakStats.Snapshot snapshot) {
                runOnUiThread(() -> {
                    if (activeMode == mode && soakView != null)
                        soakView.setText("Soak: " + snapshot.toSummary());
                });
            }

            @Override
            public void onFinished(ProbeSoakStats.Snapshot snapshot, String reason) {
                ProbeEvidenceSnapshot start = startEvidence(mode);
                ProbeEvidenceSnapshot end = ProbeDeviceEvidence.capture(MainActivity.this, BuildConfig.SOURCE_SHA, installedApkSha256);
                String result = ProbeEvidenceEnvelope.toJson(
                        mode.evidenceName,
                        PROBE_VERSION,
                        Build.MANUFACTURER + " " + Build.MODEL,
                        Build.VERSION.SDK_INT,
                        Build.VERSION.RELEASE,
                        start,
                        end,
                        snapshot.toJson(reason));
                setStartEvidence(mode, null);
                Log.i("SolarNetProbe", "SOLARNET_PROBE_RESULT " + result);
                log("SOAK FINISH transport=" + mode.evidenceName + " " + snapshot.toSummary() + " reason=" + reason);
            }

            @Override
            public void onLog(String message) {
                log(mode.evidenceName + " " + message);
            }
        };
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
        subtitle.setText("Bluetooth Classic + Nearby • identical timed soak metrics");
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("Status: idle");
        statusView.setTextSize(17f);
        root.addView(statusView);

        root.addView(button("Request radio permissions", v -> requestRadioPermissions()));

        TextView bluetoothTitle = section("Bluetooth Classic RFCOMM");
        root.addView(bluetoothTitle);
        root.addView(button("Open Android Bluetooth settings", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
        root.addView(button("BT HOST: start RFCOMM server", v -> startBluetoothHost()));
        root.addView(button("BT CLIENT: refresh paired devices", v -> refreshPairedDevices()));

        TextView nearbyTitle = section("Google Nearby Connections");
        root.addView(nearbyTitle);
        root.addView(button("Nearby HOST: advertise", v -> startNearbyHost()));
        root.addView(button("Nearby CLIENT: discover", v -> startNearbyDiscovery()));

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(0, dp(6), 0, dp(6));
        root.addView(deviceList);

        TextView soakTitle = section("Measurement");
        root.addView(soakTitle);
        root.addView(button("Broadcast manual PING", v -> broadcastManualPing()));
        root.addView(button("CLIENT: start 10-minute soak", v -> startSoak()));
        root.addView(button("Stop soak", v -> stopSoak()));

        soakView = new TextView(this);
        soakView.setText("Soak: idle");
        soakView.setTextSize(14f);
        soakView.setTextIsSelectable(true);
        soakView.setPadding(0, dp(8), 0, 0);
        root.addView(soakView);

        root.addView(button("Stop all transports", v -> stopAll()));

        TextView logTitle = section("Event log");
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(13f);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        return scroll;
    }

    private TextView section(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(18f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(18), 0, dp(3));
        return view;
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

    private void requestRadioPermissions() {
        String[] required = requiredRadioPermissions();
        List<String> missing = new ArrayList<>();
        for (String permission : required) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);
        }
        if (missing.isEmpty()) {
            log("All runtime radio permissions are already granted for this Android version.");
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST_RADIO_PERMISSIONS);
    }

    private String[] requiredRadioPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            };
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        if (Build.VERSION.SDK_INT >= 29)
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RADIO_PERMISSIONS) return;
        int denied = 0;
        for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) denied++;
        log("Radio permissions: " + (denied == 0 ? "granted" : "DENIED count=" + denied));
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNearbyPermissions() {
        for (String permission : requiredRadioPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void startBluetoothHost() {
        if (!hasBluetoothPermission()) {
            log("Grant radio permissions first.");
            requestRadioPermissions();
            return;
        }
        activate(TransportMode.BLUETOOTH_CLASSIC, true);
        setStatus("BT host starting");
        bluetoothBridge.startServer(nextId(), BLUETOOTH_SERVICE_NAME, BLUETOOTH_SERVICE_UUID);
    }

    private void refreshPairedDevices() {
        if (!hasBluetoothPermission()) {
            log("Grant radio permissions first.");
            requestRadioPermissions();
            return;
        }
        activate(TransportMode.BLUETOOTH_CLASSIC, false);
        deviceList.removeAllViews();
        try {
            String[] devices = bluetoothBridge.getBondedDevices();
            log("Paired Bluetooth devices: " + devices.length);
            if (devices.length == 0) {
                TextView empty = new TextView(this);
                empty.setText("No paired devices. Pair the phones in Android settings first.");
                deviceList.addView(empty);
                return;
            }
            for (String encoded : devices) addPairedBluetoothButton(encoded);
        } catch (Throwable t) {
            log("ERROR paired devices: " + message(t));
        }
    }

    private void addPairedBluetoothButton(String encoded) {
        int split = encoded.indexOf('\n');
        String address = split < 0 ? encoded : encoded.substring(0, split);
        String name = split < 0 ? encoded : encoded.substring(split + 1);
        Button connect = button("BT connect: " + name + "  [" + address + "]", v -> {
            setStatus("BT connecting to " + address);
            bluetoothBridge.connect(nextId(), address, BLUETOOTH_SERVICE_UUID);
        });
        deviceList.addView(connect);
    }

    private void startNearbyHost() {
        if (!hasNearbyPermissions()) {
            log("Grant radio permissions first.");
            requestRadioPermissions();
            return;
        }
        activate(TransportMode.NEARBY, true);
        setStatus("Nearby advertising");
        nearbyBridge.startAdvertising(nextId(), NEARBY_SERVICE_ID, localEndpointName(), NEARBY_STRATEGY);
    }

    private void startNearbyDiscovery() {
        if (!hasNearbyPermissions()) {
            log("Grant radio permissions first.");
            requestRadioPermissions();
            return;
        }
        activate(TransportMode.NEARBY, false);
        deviceList.removeAllViews();
        setStatus("Nearby discovering");
        nearbyBridge.startDiscovery(nextId(), NEARBY_SERVICE_ID, localEndpointName(), NEARBY_STRATEGY);
    }

    private void addNearbyEndpointButton(String endpointId, String endpointName) {
        synchronized (nearbyDiscovered) {
            if (nearbyDiscovered.contains(endpointId)) return;
            nearbyDiscovered.add(endpointId);
        }
        runOnUiThread(() -> {
            Button connect = button(
                    "Nearby connect: " + endpointName + "  [" + endpointId + "]",
                    v -> {
                        setStatus("Nearby requesting " + endpointName);
                        nearbyBridge.requestConnection(nextId(), endpointId, localEndpointName());
                    });
            deviceList.addView(connect);
        });
    }

    private void showNearbyVerification(
            String endpointId,
            String endpointName,
            String authenticationDigits,
            boolean incoming) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Verify Nearby connection")
                .setMessage(
                        (incoming ? "Incoming from " : "Connecting to ") + endpointName +
                        "\n\nConfirm these digits on both phones:\n" + safe(authenticationDigits))
                .setCancelable(false)
                .setPositiveButton("Digits match", (dialog, which) ->
                        nearbyBridge.acceptConnection(nextId(), endpointId))
                .setNegativeButton("Reject", (dialog, which) ->
                        nearbyBridge.rejectConnection(nextId(), endpointId))
                .show());
    }

    private void broadcastManualPing() {
        if (activeMode == null) {
            log("Choose and connect a transport first.");
            return;
        }
        String payload = "PING " + System.currentTimeMillis();
        if (activeMode == TransportMode.BLUETOOTH_CLASSIC)
            bluetoothBridge.broadcastBytes(nextId(), payload.getBytes(StandardCharsets.UTF_8));
        else
            nearbyBridge.broadcastBytes(nextId(), payload.getBytes(StandardCharsets.UTF_8));
        log("TX manual " + activeMode.evidenceName + ": " + payload + " connections=" + connectionCount(activeMode));
    }

    private void startSoak() {
        if (activeMode == null) {
            log("Choose and connect a transport first.");
            return;
        }
        if (hostMode) {
            log("Start the timed soak on the CLIENT phone; the HOST only echoes framed PING packets.");
            return;
        }
        if (connectionCount(activeMode) == 0) {
            log("Connect to the host before starting the soak.");
            return;
        }
        if (installedApkSha256 == null) {
            log("Evidence APK SHA-256 is still preparing; retry start shortly.");
            return;
        }
        ProbeSoakController controller = controller(activeMode);
        try {
            setStartEvidence(activeMode, ProbeDeviceEvidence.capture(this, BuildConfig.SOURCE_SHA, installedApkSha256));
            String runId = controller.start();
            setStatus(activeMode.displayName + " soak " + runId);
        } catch (Throwable t) {
            setStartEvidence(activeMode, null);
            log("ERROR start soak: " + message(t));
        }
    }

    private void stopSoak() {
        ProbeSoakController controller = activeMode == null ? null : controller(activeMode);
        if (controller == null || !controller.isRunning()) {
            log("No soak run is active.");
            return;
        }
        controller.stop("manual-stop");
        setStatus("soak stopped");
    }

    private void activate(TransportMode mode, boolean host) {
        stopRunningSoaks("transport-switch");
        try { bluetoothBridge.stopAll(nextId()); } catch (Throwable ignored) { }
        try { nearbyBridge.stopAll(nextId()); } catch (Throwable ignored) { }
        synchronized (bluetoothConnections) { bluetoothConnections.clear(); }
        synchronized (nearbyConnections) { nearbyConnections.clear(); }
        synchronized (nearbyDiscovered) { nearbyDiscovered.clear(); }
        deviceList.removeAllViews();
        activeMode = mode;
        hostMode = host;
        soakView.setText("Soak: idle");
        log("ACTIVE transport=" + mode.evidenceName + " role=" + (host ? "host" : "client"));
    }

    private void stopAll() {
        stopRunningSoaks("stop-all");
        try { bluetoothBridge.stopAll(nextId()); } catch (Throwable t) { log("BT stop error: " + message(t)); }
        try { nearbyBridge.stopAll(nextId()); } catch (Throwable t) { log("Nearby stop error: " + message(t)); }
        synchronized (bluetoothConnections) { bluetoothConnections.clear(); }
        synchronized (nearbyConnections) { nearbyConnections.clear(); }
        synchronized (nearbyDiscovered) { nearbyDiscovered.clear(); }
        activeMode = null;
        hostMode = false;
        deviceList.removeAllViews();
        soakView.setText("Soak: idle");
        setStatus("stopped");
    }

    private void stopRunningSoaks(String reason) {
        if (bluetoothSoak != null && bluetoothSoak.isRunning()) bluetoothSoak.stop(reason);
        if (nearbySoak != null && nearbySoak.isRunning()) nearbySoak.stop(reason);
    }

    private ProbeSoakController controller(TransportMode mode) {
        return mode == TransportMode.BLUETOOTH_CLASSIC ? bluetoothSoak : nearbySoak;
    }

    private int connectionCount(TransportMode mode) {
        List<String> source = mode == TransportMode.BLUETOOTH_CLASSIC ? bluetoothConnections : nearbyConnections;
        synchronized (source) {
            return source.size();
        }
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

    private ProbeEvidenceSnapshot startEvidence(TransportMode mode) {
        return mode == TransportMode.BLUETOOTH_CLASSIC ? bluetoothStartEvidence : nearbyStartEvidence;
    }

    private void setStartEvidence(TransportMode mode, ProbeEvidenceSnapshot snapshot) {
        if (mode == TransportMode.BLUETOOTH_CLASSIC) bluetoothStartEvidence = snapshot;
        else nearbyStartEvidence = snapshot;
    }

    private static String localEndpointName() {
        String raw = "SolarNet Probe " + Build.MODEL;
        return raw.length() <= 40 ? raw : raw.substring(0, 40);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
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
        if (bluetoothSoak != null) {
            try { bluetoothSoak.close(); } catch (Throwable ignored) { }
        }
        if (nearbySoak != null) {
            try { nearbySoak.close(); } catch (Throwable ignored) { }
        }
        if (bluetoothBridge != null) {
            try { bluetoothBridge.stopAll(nextId()); } catch (Throwable ignored) { }
        }
        if (nearbyBridge != null) {
            try { nearbyBridge.stopAll(nextId()); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private final class BluetoothCallback implements SolarBluetoothClassicBridge.Callback {
        @Override
        public void onOperationResult(long requestId, boolean success, String error) {
            if (bluetoothSoak != null && bluetoothSoak.onOperationResult(requestId, success, error)) return;
            log("BT op#" + requestId + " " + (success ? "OK" : "FAILED: " + error));
        }

        @Override
        public void onConnected(String connectionId, String deviceAddress, String deviceName) {
            synchronized (bluetoothConnections) {
                if (!bluetoothConnections.contains(connectionId)) bluetoothConnections.add(connectionId);
            }
            if (activeMode == TransportMode.BLUETOOTH_CLASSIC) {
                setStatus("BT connected " + deviceName);
                log("BT CONNECTED id=" + connectionId + " address=" + deviceAddress + " name=" + deviceName);
            }
        }

        @Override
        public void onDisconnected(String connectionId) {
            if (activeMode == TransportMode.BLUETOOTH_CLASSIC && bluetoothSoak != null)
                bluetoothSoak.onDisconnected();
            synchronized (bluetoothConnections) { bluetoothConnections.remove(connectionId); }
            if (activeMode == TransportMode.BLUETOOTH_CLASSIC) {
                setStatus("BT disconnected " + connectionId);
                log("BT DISCONNECTED id=" + connectionId);
            }
        }

        @Override
        public void onBytesReceived(String connectionId, byte[] payload) {
            if (activeMode != TransportMode.BLUETOOTH_CLASSIC) return;
            if (bluetoothSoak != null && bluetoothSoak.handleBytes(connectionId, payload, hostMode)) return;
            String text = new String(payload, StandardCharsets.UTF_8);
            log("BT RX " + connectionId + ": " + text);
            if (hostMode && text.startsWith("PING ")) {
                String pong = "PONG " + text.substring(5);
                bluetoothBridge.sendBytes(nextId(), connectionId, pong.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onError(String operation, String message) {
            if (activeMode == TransportMode.BLUETOOTH_CLASSIC && bluetoothSoak != null)
                bluetoothSoak.onBridgeError(operation, message);
            if (activeMode == TransportMode.BLUETOOTH_CLASSIC) setStatus("BT error: " + operation);
            log("BT ERROR " + operation + ": " + message);
        }
    }

    private final class NearbyCallback implements SolarNearbyBridge.Callback {
        @Override
        public void onOperationResult(long requestId, boolean success, String error) {
            if (nearbySoak != null && nearbySoak.onOperationResult(requestId, success, error)) return;
            log("Nearby op#" + requestId + " " + (success ? "OK" : "FAILED: " + error));
        }

        @Override
        public void onEndpointFound(String endpointId, String endpointName) {
            if (activeMode != TransportMode.NEARBY || hostMode) return;
            log("Nearby FOUND id=" + endpointId + " name=" + endpointName);
            addNearbyEndpointButton(endpointId, endpointName);
        }

        @Override
        public void onEndpointLost(String endpointId) {
            synchronized (nearbyDiscovered) { nearbyDiscovered.remove(endpointId); }
            if (activeMode == TransportMode.NEARBY) log("Nearby LOST id=" + endpointId);
        }

        @Override
        public void onVerificationRequired(
                String endpointId,
                String endpointName,
                String authenticationDigits,
                boolean incoming) {
            if (activeMode != TransportMode.NEARBY) {
                nearbyBridge.rejectConnection(nextId(), endpointId);
                return;
            }
            showNearbyVerification(endpointId, endpointName, authenticationDigits, incoming);
        }

        @Override
        public void onConnected(String endpointId) {
            synchronized (nearbyConnections) {
                if (!nearbyConnections.contains(endpointId)) nearbyConnections.add(endpointId);
            }
            if (activeMode != TransportMode.NEARBY) return;
            if (hostMode) nearbyBridge.stopAdvertising(nextId());
            else nearbyBridge.stopDiscovery(nextId());
            setStatus("Nearby connected " + endpointId);
            log("Nearby CONNECTED id=" + endpointId + " discovery/advertising stopped for measurement");
        }

        @Override
        public void onDisconnected(String endpointId) {
            if (activeMode == TransportMode.NEARBY && nearbySoak != null) nearbySoak.onDisconnected();
            synchronized (nearbyConnections) { nearbyConnections.remove(endpointId); }
            if (activeMode == TransportMode.NEARBY) {
                setStatus("Nearby disconnected " + endpointId);
                log("Nearby DISCONNECTED id=" + endpointId);
            }
        }

        @Override
        public void onBytesReceived(String endpointId, byte[] payload) {
            if (activeMode != TransportMode.NEARBY) return;
            if (nearbySoak != null && nearbySoak.handleBytes(endpointId, payload, hostMode)) return;
            String text = new String(payload, StandardCharsets.UTF_8);
            log("Nearby RX " + endpointId + ": " + text);
            if (hostMode && text.startsWith("PING ")) {
                String pong = "PONG " + text.substring(5);
                nearbyBridge.sendBytes(nextId(), endpointId, pong.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onConnectionFailed(String endpointId, int statusCode) {
            synchronized (nearbyConnections) { nearbyConnections.remove(endpointId); }
            if (activeMode == TransportMode.NEARBY) setStatus("Nearby connection failed " + statusCode);
            log("Nearby CONNECTION FAILED id=" + endpointId + " status=" + statusCode);
        }

        @Override
        public void onError(String operation, String message) {
            if (activeMode == TransportMode.NEARBY && nearbySoak != null)
                nearbySoak.onBridgeError(operation, message);
            if (activeMode == TransportMode.NEARBY) setStatus("Nearby error: " + operation);
            log("Nearby ERROR " + operation + ": " + message);
        }
    }
}
