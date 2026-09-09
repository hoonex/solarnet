package com.hoonex.solarnet.gridduel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import com.hoonex.solarnet.bluetoothclassic.SolarBluetoothClassicBridge;
import com.hoonex.solarnet.nearby.SolarNearbyBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private enum GameMode {
        PRACTICE_AI,
        LOCAL_TWO,
        BLUETOOTH,
        NEARBY
    }

    private static final int REQUEST_RADIO_PERMISSIONS = 1901;
    private static final String BT_SERVICE_UUID = "7d8f3a10-7b8a-4f66-9c38-63f04cb61b0e";
    private static final String BT_SERVICE_NAME = "Grid Duel";
    private static final String NEARBY_SERVICE_ID = "com.hoonex.solarnet.gridduel";
    private static final String NEARBY_STRATEGY = "STAR";

    private static final int BG = Color.rgb(9, 11, 16);
    private static final int PANEL = Color.rgb(20, 24, 34);
    private static final int PANEL_2 = Color.rgb(27, 32, 45);
    private static final int TEXT = Color.rgb(241, 244, 250);
    private static final int MUTED = Color.rgb(151, 159, 177);
    private static final int CYAN = Color.rgb(110, 231, 255);
    private static final int SUN = Color.rgb(255, 184, 77);
    private static final int MOON = Color.rgb(124, 140, 255);
    private static final int DANGER = Color.rgb(255, 107, 122);

    private final AtomicLong requestIds = new AtomicLong();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GridDuelGame game = new GridDuelGame();
    private final List<String> nearbyDiscovered = new ArrayList<>();
    private final List<String> nearbyConnections = new ArrayList<>();
    private final List<String> bluetoothConnections = new ArrayList<>();

    private SolarBluetoothClassicBridge bluetoothBridge;
    private SolarNearbyBridge nearbyBridge;

    private GameMode mode;
    private boolean networkHost;
    private boolean awaitingHostState;
    private boolean aiThinking;
    private String networkConnectionId;

    private GridBoardView boardView;
    private TextView statusView;
    private TextView sunHpView;
    private TextView moonHpView;
    private TextView instructionView;
    private TextView lobbyStatusView;
    private LinearLayout deviceList;
    private Button rematchButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        bluetoothBridge = new SolarBluetoothClassicBridge(this, new BluetoothCallback());
        nearbyBridge = new SolarNearbyBridge(this, new NearbyCallback());
        showHome();
    }

    private void showHome() {
        stopNetworking();
        handler.removeCallbacksAndMessages(null);
        aiThinking = false;
        mode = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(34), dp(22), dp(30));
        scroll.addView(root);

        TextView eyebrow = text("SOLARNET ORIGINAL", 12, CYAN, true);
        eyebrow.setLetterSpacing(0.16f);
        root.addView(eyebrow);

        TextView title = text("GRID\nDUEL", 46, TEXT, true);
        title.setLineSpacing(-dp(5), 0.92f);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);

        TextView subtitle = text("한 칸 이동. 한 칸 공격. 먼저 상대 체력을 0으로 만들면 승리.", 16, MUTED, false);
        subtitle.setPadding(0, dp(10), 0, dp(26));
        root.addView(subtitle);

        root.addView(modeButton("AI 연습전", "혼자 바로 플레이", SUN, v -> startOffline(GameMode.PRACTICE_AI)));
        root.addView(modeButton("한 폰 2인", "SUN / MOON 번갈아 플레이", CYAN, v -> startOffline(GameMode.LOCAL_TWO)));
        root.addView(modeButton("Bluetooth 대전", "인터넷 없이 페어링한 두 폰", MOON, v -> showNetworkLobby(GameMode.BLUETOOTH)));
        root.addView(modeButton("Nearby 대전", "근처 폰 검색 + 인증 코드", Color.rgb(113, 233, 160), v -> showNetworkLobby(GameMode.NEARBY)));

        TextView rules = text(
                "5×5 보드  ·  체력 3  ·  SUN 선공\n" +
                "현재 유닛에서 상하좌우 한 칸을 탭하세요. 빈 칸이면 이동, 상대가 있으면 공격합니다.",
                13, MUTED, false);
        rules.setPadding(dp(4), dp(24), dp(4), 0);
        root.addView(rules);

        setContentView(scroll);
    }

    private void startOffline(GameMode selectedMode) {
        stopNetworking();
        mode = selectedMode;
        networkHost = false;
        networkConnectionId = null;
        awaitingHostState = false;
        aiThinking = false;
        game.reset();
        showGame();
    }

    private void showNetworkLobby(GameMode selectedMode) {
        stopNetworking();
        mode = selectedMode;
        networkConnectionId = null;
        awaitingHostState = false;
        networkHost = false;

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(24), dp(20), dp(22));

        Button back = compactButton("← 홈", PANEL_2, TEXT, v -> showHome());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(92), dp(44));
        root.addView(back, backParams);

        TextView title = text(selectedMode == GameMode.BLUETOOTH ? "Bluetooth 대전" : "Nearby 대전", 31, TEXT, true);
        title.setPadding(0, dp(22), 0, dp(5));
        root.addView(title);

        TextView help = text(
                selectedMode == GameMode.BLUETOOTH
                        ? "두 폰을 Android Bluetooth 설정에서 먼저 페어링한 뒤 방을 만들거나 참가하세요."
                        : "한 폰에서 방을 만들고 다른 폰에서 검색합니다. 인증 숫자가 양쪽에서 같은지 반드시 확인하세요.",
                14, MUTED, false);
        help.setPadding(0, 0, 0, dp(18));
        root.addView(help);

        lobbyStatusView = text("연결 대기", 15, CYAN, true);
        lobbyStatusView.setBackground(rounded(PANEL, dp(14)));
        lobbyStatusView.setPadding(dp(14), dp(13), dp(14), dp(13));
        root.addView(lobbyStatusView, matchWrap(dp(8)));

        root.addView(primaryButton("방 만들기", selectedMode == GameMode.BLUETOOTH ? MOON : Color.rgb(91, 209, 142), v -> startNetworkHost()));
        root.addView(primaryButton("방 참가하기", PANEL_2, v -> startNetworkClient()));

        if (selectedMode == GameMode.BLUETOOTH) {
            Button settings = compactButton("Bluetooth 설정 열기", PANEL, MUTED,
                    v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            root.addView(settings, matchWrap(dp(4)));
        }

        TextView devicesTitle = text("연결할 기기", 14, MUTED, true);
        devicesTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(devicesTitle);

        ScrollView deviceScroll = new ScrollView(this);
        deviceList = column();
        deviceScroll.addView(deviceList);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(deviceScroll, deviceParams);

        setContentView(root);
    }

    private void startNetworkHost() {
        if (!hasRadioPermissions()) {
            requestRadioPermissions();
            setLobbyStatus("권한 승인 후 다시 방 만들기를 눌러주세요.", DANGER);
            return;
        }
        activateNetwork(true);
        if (mode == GameMode.BLUETOOTH) {
            setLobbyStatus("Bluetooth 방 여는 중…", CYAN);
            bluetoothBridge.startServer(nextRequestId(), BT_SERVICE_NAME, BT_SERVICE_UUID);
        } else {
            setLobbyStatus("Nearby 광고 중… 다른 폰에서 검색하세요.", CYAN);
            nearbyBridge.startAdvertising(nextRequestId(), NEARBY_SERVICE_ID, endpointName(), NEARBY_STRATEGY);
        }
    }

    private void startNetworkClient() {
        if (!hasRadioPermissions()) {
            requestRadioPermissions();
            setLobbyStatus("권한 승인 후 다시 참가하기를 눌러주세요.", DANGER);
            return;
        }
        activateNetwork(false);
        deviceList.removeAllViews();
        if (mode == GameMode.BLUETOOTH) {
            setLobbyStatus("페어링된 기기를 불러오는 중…", CYAN);
            refreshBluetoothDevices();
        } else {
            setLobbyStatus("Nearby 기기 검색 중…", CYAN);
            nearbyBridge.startDiscovery(nextRequestId(), NEARBY_SERVICE_ID, endpointName(), NEARBY_STRATEGY);
        }
    }

    private void activateNetwork(boolean host) {
        try { bluetoothBridge.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        try { nearbyBridge.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        synchronized (nearbyDiscovered) { nearbyDiscovered.clear(); }
        synchronized (nearbyConnections) { nearbyConnections.clear(); }
        synchronized (bluetoothConnections) { bluetoothConnections.clear(); }
        networkConnectionId = null;
        networkHost = host;
    }

    private void refreshBluetoothDevices() {
        deviceList.removeAllViews();
        try {
            String[] devices = bluetoothBridge.getBondedDevices();
            if (devices.length == 0) {
                addDeviceMessage("페어링된 기기가 없습니다. Bluetooth 설정에서 먼저 두 폰을 페어링하세요.");
                return;
            }
            for (String encoded : devices) {
                int split = encoded.indexOf('\n');
                String address = split < 0 ? encoded : encoded.substring(0, split);
                String name = split < 0 ? encoded : encoded.substring(split + 1);
                Button connect = compactButton(
                        (name == null || name.isEmpty() ? "Android 기기" : name) + "\n" + address,
                        PANEL_2,
                        TEXT,
                        v -> {
                            setLobbyStatus("연결 중: " + address, CYAN);
                            bluetoothBridge.connect(nextRequestId(), address, BT_SERVICE_UUID);
                        });
                connect.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                connect.setPadding(dp(16), dp(10), dp(16), dp(10));
                deviceList.addView(connect, matchWrap(dp(5)));
            }
        } catch (Throwable t) {
            setLobbyStatus("기기 목록 오류: " + safeMessage(t), DANGER);
        }
    }

    private void addNearbyEndpoint(String endpointId, String endpointName) {
        synchronized (nearbyDiscovered) {
            if (nearbyDiscovered.contains(endpointId)) return;
            nearbyDiscovered.add(endpointId);
        }
        runOnUiThread(() -> {
            if (deviceList == null) return;
            Button connect = compactButton(
                    endpointName + "\n" + endpointId,
                    PANEL_2,
                    TEXT,
                    v -> {
                        setLobbyStatus("Nearby 연결 요청 중…", CYAN);
                        nearbyBridge.requestConnection(nextRequestId(), endpointId, endpointName());
                    });
            connect.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            connect.setPadding(dp(16), dp(10), dp(16), dp(10));
            deviceList.addView(connect, matchWrap(dp(5)));
        });
    }

    private void showVerification(String endpointId, String endpointName, String digits, boolean incoming) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("연결 코드 확인")
                .setMessage((incoming ? "상대: " : "연결 대상: ") + endpointName +
                        "\n\n양쪽 폰에 같은 숫자가 보이면 승인하세요.\n\n" + digits)
                .setCancelable(false)
                .setPositiveButton("숫자 일치", (dialog, which) ->
                        nearbyBridge.acceptConnection(nextRequestId(), endpointId))
                .setNegativeButton("거절", (dialog, which) ->
                        nearbyBridge.rejectConnection(nextRequestId(), endpointId))
                .show());
    }

    private void beginNetworkMatch(String connectionId) {
        networkConnectionId = connectionId;
        game.reset();
        awaitingHostState = !networkHost;
        showGame();
        if (networkHost) sendAuthoritativeState();
        else {
            refreshGameUi("HOST의 게임 상태를 기다리는 중…");
            sendNetworkPayload(GameProtocol.encodeReady());
        }
    }

    private void showGame() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(18), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button home = compactButton("← 나가기", PANEL_2, TEXT, v -> showHome());
        top.addView(home, new LinearLayout.LayoutParams(dp(98), dp(43)));
        Space spacer = new Space(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView modeLabel = text(modeName(), 12, MUTED, true);
        top.addView(modeLabel);
        root.addView(top);

        statusView = text("", 22, TEXT, true);
        statusView.setPadding(dp(4), dp(18), dp(4), dp(12));
        root.addView(statusView);

        LinearLayout health = new LinearLayout(this);
        health.setOrientation(LinearLayout.HORIZONTAL);
        sunHpView = playerCard("SUN", SUN);
        moonHpView = playerCard("MOON", MOON);
        health.addView(sunHpView, new LinearLayout.LayoutParams(0, dp(76), 1f));
        LinearLayout.LayoutParams moonParams = new LinearLayout.LayoutParams(0, dp(76), 1f);
        moonParams.leftMargin = dp(8);
        health.addView(moonHpView, moonParams);
        root.addView(health);

        boardView = new GridBoardView(this);
        boardView.bind(game);
        boardView.setListener(this::onBoardTapped);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        boardParams.topMargin = dp(8);
        boardParams.bottomMargin = dp(6);
        root.addView(boardView, boardParams);

        instructionView = text("", 14, MUTED, false);
        instructionView.setGravity(Gravity.CENTER);
        instructionView.setPadding(dp(8), dp(4), dp(8), dp(10));
        root.addView(instructionView);

        rematchButton = primaryButton("다시 시작", PANEL_2, v -> rematch());
        root.addView(rematchButton);

        setContentView(root);
        refreshGameUi(null);
    }

    private void onBoardTapped(int x, int y) {
        if (game.getWinner() != GridDuelGame.Player.NONE) return;

        if (mode == GameMode.PRACTICE_AI) {
            if (aiThinking || game.getCurrentPlayer() != GridDuelGame.Player.SUN) return;
            GridDuelGame.Outcome outcome = game.tap(GridDuelGame.Player.SUN, x, y);
            if (!outcome.accepted) return;
            refreshGameUi(outcome.attack ? "공격 성공" : "이동 완료");
            if (game.getWinner() == GridDuelGame.Player.NONE) scheduleAiTurn();
            return;
        }

        if (mode == GameMode.LOCAL_TWO) {
            GridDuelGame.Player actor = game.getCurrentPlayer();
            GridDuelGame.Outcome outcome = game.tap(actor, x, y);
            if (!outcome.accepted) return;
            refreshGameUi(outcome.attack ? actor + " 공격" : actor + " 이동");
            return;
        }

        if (isNetworkMode()) {
            if (networkConnectionId == null) return;
            if (networkHost) {
                if (game.getCurrentPlayer() != GridDuelGame.Player.SUN) return;
                GridDuelGame.Outcome outcome = game.tap(GridDuelGame.Player.SUN, x, y);
                if (!outcome.accepted) return;
                sendAuthoritativeState();
                refreshGameUi(outcome.attack ? "SUN 공격" : "SUN 이동");
            } else {
                if (awaitingHostState || game.getCurrentPlayer() != GridDuelGame.Player.MOON) return;
                if (!game.legalTargets(GridDuelGame.Player.MOON).contains(GridDuelGame.encodeCell(x, y))) return;
                awaitingHostState = true;
                sendNetworkPayload(GameProtocol.encodeAction(game.getTurnIndex(), x, y));
                refreshGameUi("HOST 판정 대기 중…");
            }
        }
    }

    private void scheduleAiTurn() {
        aiThinking = true;
        refreshGameUi("MOON이 생각 중…");
        handler.postDelayed(() -> {
            if (mode != GameMode.PRACTICE_AI || game.getCurrentPlayer() != GridDuelGame.Player.MOON) {
                aiThinking = false;
                return;
            }
            int cell = game.chooseAiTarget(GridDuelGame.Player.MOON);
            if (cell >= 0) {
                game.tap(GridDuelGame.Player.MOON, GridDuelGame.decodeX(cell), GridDuelGame.decodeY(cell));
            }
            aiThinking = false;
            refreshGameUi(null);
        }, 430L);
    }

    private void rematch() {
        if (isNetworkMode() && !networkHost) return;
        game.reset();
        awaitingHostState = false;
        aiThinking = false;
        if (isNetworkMode()) sendAuthoritativeState();
        refreshGameUi("새 게임 시작");
    }

    private void refreshGameUi(String transientMessage) {
        if (boardView == null || statusView == null) return;

        GridDuelGame.Player winner = game.getWinner();
        if (winner != GridDuelGame.Player.NONE) {
            statusView.setText((winner == GridDuelGame.Player.SUN ? "☀ SUN" : "◐ MOON") + " 승리");
            statusView.setTextColor(winner == GridDuelGame.Player.SUN ? SUN : MOON);
            instructionView.setText("상대 체력을 모두 깎았습니다.");
        } else if (transientMessage != null) {
            statusView.setText(transientMessage);
            statusView.setTextColor(TEXT);
            instructionView.setText(turnInstruction());
        } else {
            GridDuelGame.Player current = game.getCurrentPlayer();
            statusView.setText((current == GridDuelGame.Player.SUN ? "SUN" : "MOON") + " 차례");
            statusView.setTextColor(current == GridDuelGame.Player.SUN ? SUN : MOON);
            instructionView.setText(turnInstruction());
        }

        sunHpView.setText("☀  SUN\n" + hearts(game.getSunHp()));
        moonHpView.setText("◐  MOON\n" + hearts(game.getMoonHp()));

        boolean canInput = false;
        if (winner == GridDuelGame.Player.NONE) {
            if (mode == GameMode.LOCAL_TWO) canInput = true;
            else if (mode == GameMode.PRACTICE_AI)
                canInput = !aiThinking && game.getCurrentPlayer() == GridDuelGame.Player.SUN;
            else if (isNetworkMode() && networkConnectionId != null) {
                canInput = networkHost
                        ? game.getCurrentPlayer() == GridDuelGame.Player.SUN
                        : !awaitingHostState && game.getCurrentPlayer() == GridDuelGame.Player.MOON;
            }
        }
        boardView.setInputEnabled(canInput);
        boardView.refresh();

        if (isNetworkMode() && !networkHost) {
            rematchButton.setEnabled(false);
            rematchButton.setAlpha(0.45f);
            rematchButton.setText("HOST만 다시 시작 가능");
        } else {
            rematchButton.setEnabled(true);
            rematchButton.setAlpha(1f);
            rematchButton.setText("다시 시작");
        }
    }

    private String turnInstruction() {
        if (game.getWinner() != GridDuelGame.Player.NONE) return "게임 종료";
        if (mode == GameMode.PRACTICE_AI && aiThinking) return "AI가 다음 수를 고르고 있습니다.";
        if (isNetworkMode() && networkConnectionId == null) return "연결이 끊겼습니다.";
        if (isNetworkMode() && !networkHost && awaitingHostState) return "HOST의 확정 상태를 기다립니다.";
        GridDuelGame.Player current = game.getCurrentPlayer();
        if (isNetworkMode()) {
            GridDuelGame.Player mine = networkHost ? GridDuelGame.Player.SUN : GridDuelGame.Player.MOON;
            if (current != mine) return "상대 차례입니다.";
        }
        return "빛나는 칸을 탭하세요 · 빈 칸=이동 · 상대 칸=공격";
    }

    private void handleNetworkPayload(byte[] payload) {
        try {
            GameProtocol.Frame frame = GameProtocol.decode(payload);
            if (frame.type == GameProtocol.Frame.Type.READY) {
                if (networkHost) sendAuthoritativeState();
                return;
            }
            if (frame.type == GameProtocol.Frame.Type.STATE) {
                if (networkHost) return;
                frame.applyTo(game);
                awaitingHostState = false;
                refreshGameUi(null);
                return;
            }

            if (!networkHost) return;
            if (frame.turnIndex != game.getTurnIndex()) {
                sendAuthoritativeState();
                return;
            }
            GridDuelGame.Outcome outcome = game.tap(GridDuelGame.Player.MOON, frame.x, frame.y);
            if (outcome.accepted) {
                sendAuthoritativeState();
                refreshGameUi(outcome.attack ? "MOON 공격" : "MOON 이동");
            } else {
                sendAuthoritativeState();
            }
        } catch (Throwable ignored) {
            if (networkHost) sendAuthoritativeState();
        }
    }

    private void sendAuthoritativeState() {
        if (!isNetworkMode() || !networkHost || networkConnectionId == null) return;
        sendNetworkPayload(GameProtocol.encodeState(game));
    }

    private void sendNetworkPayload(byte[] payload) {
        if (networkConnectionId == null) return;
        if (mode == GameMode.BLUETOOTH)
            bluetoothBridge.sendBytes(nextRequestId(), networkConnectionId, payload);
        else if (mode == GameMode.NEARBY)
            nearbyBridge.sendBytes(nextRequestId(), networkConnectionId, payload);
    }

    private void onNetworkConnected(String connectionId, String label) {
        runOnUiThread(() -> {
            networkConnectionId = connectionId;
            if (mode == GameMode.NEARBY) {
                try {
                    if (networkHost) nearbyBridge.stopAdvertising(nextRequestId());
                    else nearbyBridge.stopDiscovery(nextRequestId());
                } catch (Throwable ignored) { }
            }
            if (lobbyStatusView != null) setLobbyStatus("연결 완료 · " + label, Color.rgb(113, 233, 160));
            beginNetworkMatch(connectionId);
        });
    }

    private void onNetworkDisconnected(String connectionId) {
        runOnUiThread(() -> {
            if (connectionId != null && connectionId.equals(networkConnectionId)) networkConnectionId = null;
            if (statusView != null) {
                statusView.setText("연결 끊김");
                statusView.setTextColor(DANGER);
                if (instructionView != null) instructionView.setText("홈으로 나가서 다시 연결하세요.");
                if (boardView != null) boardView.setInputEnabled(false);
            }
        });
    }

    private void stopNetworking() {
        networkConnectionId = null;
        awaitingHostState = false;
        synchronized (nearbyDiscovered) { nearbyDiscovered.clear(); }
        synchronized (nearbyConnections) { nearbyConnections.clear(); }
        synchronized (bluetoothConnections) { bluetoothConnections.clear(); }
        if (bluetoothBridge != null) {
            try { bluetoothBridge.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        }
        if (nearbyBridge != null) {
            try { nearbyBridge.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        }
    }

    private boolean isNetworkMode() {
        return mode == GameMode.BLUETOOTH || mode == GameMode.NEARBY;
    }

    private String modeName() {
        if (mode == GameMode.PRACTICE_AI) return "AI PRACTICE";
        if (mode == GameMode.LOCAL_TWO) return "LOCAL 2P";
        if (mode == GameMode.BLUETOOTH) return networkHost ? "BLUETOOTH · SUN" : "BLUETOOTH · MOON";
        if (mode == GameMode.NEARBY) return networkHost ? "NEARBY · SUN" : "NEARBY · MOON";
        return "GRID DUEL";
    }

    private void requestRadioPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredRadioPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);
        }
        if (!missing.isEmpty())
            requestPermissions(missing.toArray(new String[0]), REQUEST_RADIO_PERMISSIONS);
    }

    private boolean hasRadioPermissions() {
        for (String permission : requiredRadioPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private String[] requiredRadioPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            };
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
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
        if (denied == 0) setLobbyStatus("권한 승인 완료. 다시 방 만들기/참가를 눌러주세요.", CYAN);
        else setLobbyStatus("필요한 권한이 거부됐습니다.", DANGER);
    }

    private String endpointName() {
        String raw = "Grid Duel " + Build.MODEL;
        return raw.length() <= 40 ? raw : raw.substring(0, 40);
    }

    private long nextRequestId() {
        return requestIds.incrementAndGet();
    }

    private void setLobbyStatus(String message, int color) {
        runOnUiThread(() -> {
            if (lobbyStatusView == null) return;
            lobbyStatusView.setText(message);
            lobbyStatusView.setTextColor(color);
        });
    }

    private void addDeviceMessage(String message) {
        TextView view = text(message, 14, MUTED, false);
        view.setPadding(dp(4), dp(8), dp(4), dp(8));
        deviceList.addView(view);
    }

    private TextView playerCard(String label, int accent) {
        TextView view = text(label, 17, TEXT, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = rounded(PANEL, dp(17));
        bg.setStroke(dp(1), withAlpha(accent, 90));
        view.setBackground(bg);
        return view;
    }

    private View modeButton(String title, String subtitle, int accent, View.OnClickListener click) {
        LinearLayout box = column();
        GradientDrawable bg = rounded(PANEL, dp(18));
        bg.setStroke(dp(1), withAlpha(accent, 70));
        box.setBackground(bg);
        box.setPadding(dp(18), dp(15), dp(18), dp(15));
        box.setOnClickListener(click);
        box.setClickable(true);
        box.setFocusable(true);

        TextView titleView = text(title, 18, TEXT, true);
        TextView subtitleView = text(subtitle, 13, MUTED, false);
        subtitleView.setPadding(0, dp(3), 0, 0);
        box.addView(titleView);
        box.addView(subtitleView);

        LinearLayout.LayoutParams params = matchWrap(dp(8));
        box.setLayoutParams(params);
        return box;
    }

    private Button primaryButton(String label, int background, View.OnClickListener click) {
        Button button = compactButton(label, background, TEXT, click);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextSize(16f);
        button.setAllCaps(false);
        button.setHeight(dp(54));
        button.setLayoutParams(matchWrap(dp(8)));
        return button;
    }

    private Button compactButton(String label, int background, int foreground, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(background, dp(14)));
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hearts(int hp) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < GridDuelGame.MAX_HP; i++) builder.append(i < hp ? "♥" : "·");
        return builder.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopNetworking();
        super.onDestroy();
    }

    private final class BluetoothCallback implements SolarBluetoothClassicBridge.Callback {
        @Override
        public void onOperationResult(long requestId, boolean success, String error) {
            if (!success && mode == GameMode.BLUETOOTH)
                setLobbyStatus("Bluetooth 오류: " + error, DANGER);
        }

        @Override
        public void onConnected(String connectionId, String deviceAddress, String deviceName) {
            synchronized (bluetoothConnections) {
                if (!bluetoothConnections.contains(connectionId)) bluetoothConnections.add(connectionId);
            }
            if (mode == GameMode.BLUETOOTH)
                onNetworkConnected(connectionId, deviceName == null ? deviceAddress : deviceName);
        }

        @Override
        public void onDisconnected(String connectionId) {
            synchronized (bluetoothConnections) { bluetoothConnections.remove(connectionId); }
            if (mode == GameMode.BLUETOOTH) onNetworkDisconnected(connectionId);
        }

        @Override
        public void onBytesReceived(String connectionId, byte[] payload) {
            if (mode != GameMode.BLUETOOTH) return;
            runOnUiThread(() -> handleNetworkPayload(payload));
        }

        @Override
        public void onError(String operation, String message) {
            if (mode == GameMode.BLUETOOTH)
                setLobbyStatus("Bluetooth " + operation + ": " + message, DANGER);
        }
    }

    private final class NearbyCallback implements SolarNearbyBridge.Callback {
        @Override
        public void onOperationResult(long requestId, boolean success, String error) {
            if (!success && mode == GameMode.NEARBY)
                setLobbyStatus("Nearby 오류: " + error, DANGER);
        }

        @Override
        public void onEndpointFound(String endpointId, String endpointName) {
            if (mode != GameMode.NEARBY || networkHost) return;
            addNearbyEndpoint(endpointId, endpointName);
        }

        @Override
        public void onEndpointLost(String endpointId) {
            synchronized (nearbyDiscovered) { nearbyDiscovered.remove(endpointId); }
        }

        @Override
        public void onVerificationRequired(
                String endpointId,
                String endpointName,
                String authenticationDigits,
                boolean incoming) {
            if (mode != GameMode.NEARBY) {
                nearbyBridge.rejectConnection(nextRequestId(), endpointId);
                return;
            }
            showVerification(endpointId, endpointName, authenticationDigits, incoming);
        }

        @Override
        public void onConnected(String endpointId) {
            synchronized (nearbyConnections) {
                if (!nearbyConnections.contains(endpointId)) nearbyConnections.add(endpointId);
            }
            if (mode == GameMode.NEARBY) onNetworkConnected(endpointId, "Nearby peer");
        }

        @Override
        public void onDisconnected(String endpointId) {
            synchronized (nearbyConnections) { nearbyConnections.remove(endpointId); }
            if (mode == GameMode.NEARBY) onNetworkDisconnected(endpointId);
        }

        @Override
        public void onBytesReceived(String endpointId, byte[] payload) {
            if (mode != GameMode.NEARBY) return;
            runOnUiThread(() -> handleNetworkPayload(payload));
        }

        @Override
        public void onConnectionFailed(String endpointId, int statusCode) {
            if (mode == GameMode.NEARBY)
                setLobbyStatus("Nearby 연결 실패: " + statusCode, DANGER);
        }

        @Override
        public void onError(String operation, String message) {
            if (mode == GameMode.NEARBY)
                setLobbyStatus("Nearby " + operation + ": " + message, DANGER);
        }
    }
}
