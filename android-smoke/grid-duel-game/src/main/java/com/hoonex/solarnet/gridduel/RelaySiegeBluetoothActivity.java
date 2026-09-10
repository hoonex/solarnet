package com.hoonex.solarnet.gridduel;

import android.Manifest;
import android.app.Activity;
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
import android.widget.TextView;

import com.hoonex.solarnet.bluetoothclassic.SolarBluetoothClassicBridge;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Actual two-phone Relay Siege over paired-device Bluetooth Classic RFCOMM. */
public final class RelaySiegeBluetoothActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 2901;
    private static final String SERVICE_UUID = "0e7c3a63-bced-4ae1-a8a1-79f5a4df3127";
    private static final String SERVICE_NAME = "Relay Siege";

    private static final int BG = Color.rgb(6, 10, 17);
    private static final int PANEL = Color.rgb(17, 24, 35);
    private static final int PANEL_2 = Color.rgb(24, 33, 47);
    private static final int TEXT = Color.rgb(242, 246, 251);
    private static final int MUTED = Color.rgb(148, 160, 181);
    private static final int GREEN = Color.rgb(94, 225, 157);
    private static final int CYAN = Color.rgb(96, 224, 255);
    private static final int SUN = Color.rgb(255, 176, 73);
    private static final int MOON = Color.rgb(118, 135, 255);
    private static final int RED = Color.rgb(255, 92, 116);

    private final AtomicLong requestIds = new AtomicLong();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hostTick = new Runnable() {
        @Override public void run() {
            if (!matchSurfaceActive || session == null) return;
            if (session.getRole() == RelaySiegeBluetoothSession.Role.HOST)
                session.hostAdvanceOneTick();
            handler.postDelayed(this, 100L);
        }
    };

    private SolarBluetoothClassicBridge bluetooth;
    private RelaySiegeBluetoothSession session;
    private String selectedDeckId;
    private String connectionId;
    private String lastDeviceAddress;
    private boolean hostRole;
    private boolean matchSurfaceActive;
    private boolean intentionalExit;
    private boolean reconnectOnResume;

    private TextView lobbyStatus;
    private LinearLayout deviceList;
    private RelaySiegeNetworkArenaView arena;
    private TextView matchStatus;
    private TextView objectiveStatus;
    private TextView selectionStatus;
    private TextView fluxStatus;
    private TextView nextStatus;
    private Button reconnectButton;
    private final Button[] handButtons = new Button[4];
    private String lastMessage = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        bluetooth = new SolarBluetoothClassicBridge(this, new BluetoothCallback());
        showDeckSelect();
    }

    @Override protected void onPause() {
        if (matchSurfaceActive && connectionId != null && !intentionalExit) {
            reconnectOnResume = !hostRole && lastDeviceAddress != null;
            try { bluetooth.disconnect(nextRequestId(), connectionId); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (reconnectOnResume && !hostRole && session != null && connectionId == null && lastDeviceAddress != null) {
            reconnectOnResume = false;
            handler.postDelayed(() -> reconnectClient(), 350L);
        }
    }

    @Override protected void onDestroy() {
        intentionalExit = true;
        handler.removeCallbacksAndMessages(null);
        try { bluetooth.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private void showDeckSelect() {
        leaveCurrentMatch(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(21), dp(27), dp(21), dp(30));
        scroll.addView(root);

        Button back = compactButton("← RELAY SIEGE", PANEL_2, TEXT, v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(142), dp(44)));
        TextView eyebrow = text("BLUETOOTH 2P", 12, CYAN, true);
        eyebrow.setLetterSpacing(0.14f);
        eyebrow.setPadding(0, dp(27), 0, dp(6));
        root.addView(eyebrow);
        root.addView(text("CHOOSE\nYOUR DECK", 39, TEXT, true));
        TextView help = text(
                "각 폰에서 자기 덱을 고릅니다. HOST는 SUN, 참가자는 MOON으로 시작합니다. 상대 손패는 전송하지 않습니다.",
                14, MUTED, false);
        help.setPadding(0, dp(11), 0, dp(18));
        root.addView(help);

        for (RelaySiegeCards.Deck deck : RelaySiegeCards.starterDecks()) {
            LinearLayout card = column();
            GradientDrawable bg = rounded(PANEL, dp(16));
            bg.setStroke(dp(1), Color.argb(80, 96, 224, 255));
            card.setBackground(bg);
            card.setPadding(dp(15), dp(14), dp(15), dp(14));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> showLobby(deck.id));
            card.addView(text(deck.name, 19, TEXT, true));
            TextView archetype = text(deck.archetype + "  ·  AVG " + String.format(Locale.US, "%.1f", deck.averageFlux()),
                    12, GREEN, true);
            archetype.setPadding(0, dp(4), 0, dp(5));
            card.addView(archetype);
            card.addView(text(deck.gamePlan, 12.5f, MUTED, false));
            root.addView(card, matchWrap(dp(8)));
        }
        setContentView(scroll);
    }

    private void showLobby(String deckId) {
        leaveCurrentMatch(false);
        selectedDeckId = deckId;
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck(deckId);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(24), dp(20), dp(22));
        Button back = compactButton("← 덱 선택", PANEL_2, TEXT, v -> showDeckSelect());
        root.addView(back, new LinearLayout.LayoutParams(dp(104), dp(44)));
        TextView title = text("Bluetooth 대전", 31, TEXT, true);
        title.setPadding(0, dp(22), 0, dp(3));
        root.addView(title);
        root.addView(text(deck.name + " · " + deck.archetype, 13, GREEN, true));

        TextView help = text(
                "두 폰을 Android Bluetooth 설정에서 먼저 페어링하세요. 방을 만든 폰이 authoritative HOST가 되어 전투 tick을 결정합니다.",
                13.5f, MUTED, false);
        help.setPadding(0, dp(10), 0, dp(15));
        root.addView(help);

        lobbyStatus = text("연결 대기", 14, CYAN, true);
        lobbyStatus.setBackground(rounded(PANEL, dp(13)));
        lobbyStatus.setPadding(dp(13), dp(12), dp(13), dp(12));
        root.addView(lobbyStatus, matchWrap(dp(7)));

        root.addView(primaryButton("방 만들기 · SUN", SUN, BG, v -> startHost()), matchWrap(dp(7)));
        root.addView(primaryButton("방 참가하기 · MOON", MOON, TEXT, v -> startClientBrowser()), matchWrap(dp(7)));
        root.addView(compactButton("Android Bluetooth 설정 열기", PANEL_2, MUTED,
                v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), matchWrap(dp(7)));

        TextView deviceTitle = text("페어링된 기기", 12, MUTED, true);
        deviceTitle.setLetterSpacing(0.1f);
        deviceTitle.setPadding(0, dp(18), 0, dp(7));
        root.addView(deviceTitle);
        ScrollView listScroll = new ScrollView(this);
        deviceList = column();
        listScroll.addView(deviceList);
        root.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void startHost() {
        if (!ensureBluetoothPermission()) return;
        stopTransportOnly();
        hostRole = true;
        intentionalExit = false;
        String sessionId = UUID.randomUUID().toString();
        long seed = System.nanoTime() ^ sessionId.hashCode();
        session = RelaySiegeBluetoothSession.host(
                selectedDeckId, sessionId, seed, this::sendPayload, new SessionListener());
        setLobby("RFCOMM 방 여는 중… 상대 폰에서 이 기기를 선택하세요.", CYAN);
        bluetooth.startServer(nextRequestId(), SERVICE_NAME, SERVICE_UUID);
    }

    private void startClientBrowser() {
        if (!ensureBluetoothPermission()) return;
        stopTransportOnly();
        hostRole = false;
        intentionalExit = false;
        session = RelaySiegeBluetoothSession.client(selectedDeckId, this::sendPayload, new SessionListener());
        deviceList.removeAllViews();
        try {
            String[] devices = bluetooth.getBondedDevices();
            if (devices.length == 0) {
                addDeviceMessage("페어링된 기기가 없습니다. Bluetooth 설정에서 두 폰을 먼저 페어링하세요.");
                return;
            }
            setLobby("상대 HOST 폰을 선택하세요.", CYAN);
            for (String encoded : devices) addBondedDevice(encoded);
        } catch (Throwable t) {
            setLobby("기기 목록 오류: " + safeMessage(t), RED);
        }
    }

    private void addBondedDevice(String encoded) {
        int split = encoded.indexOf('\n');
        String address = split < 0 ? encoded : encoded.substring(0, split);
        String name = split < 0 ? encoded : encoded.substring(split + 1);
        Button button = compactButton(
                (name == null || name.isEmpty() ? "Android 기기" : name) + "\n" + address,
                PANEL_2, TEXT, v -> connectClient(address));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(15), dp(8), dp(15), dp(8));
        deviceList.addView(button, matchWrap(dp(5)));
    }

    private void connectClient(String address) {
        if (session == null || hostRole) return;
        lastDeviceAddress = address;
        setLobby("RFCOMM 연결 중: " + address, CYAN);
        bluetooth.connect(nextRequestId(), address, SERVICE_UUID);
    }

    private void reconnectClient() {
        if (hostRole || session == null || lastDeviceAddress == null || connectionId != null) return;
        if (matchStatus != null) matchStatus.setText("재연결 중… " + lastDeviceAddress);
        bluetooth.connect(nextRequestId(), lastDeviceAddress, SERVICE_UUID);
    }

    private void ensureMatchSurface() {
        if (matchSurfaceActive) return;
        matchSurfaceActive = true;
        handler.removeCallbacks(hostTick);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(15), dp(16), dp(15), dp(14));
        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button leave = compactButton("← 나가기", PANEL_2, TEXT, v -> {
            intentionalExit = true;
            showLobby(selectedDeckId);
        });
        nav.addView(leave, new LinearLayout.LayoutParams(dp(96), dp(42)));
        matchStatus = text("Bluetooth 동기화 중…", 11.5f, CYAN, true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = dp(10);
        nav.addView(matchStatus, statusParams);
        root.addView(nav);

        objectiveStatus = text("", 13, TEXT, true);
        objectiveStatus.setPadding(dp(3), dp(12), dp(3), dp(7));
        root.addView(objectiveStatus);

        arena = new RelaySiegeNetworkArenaView(this);
        arena.setListener(new RelaySiegeNetworkArenaView.Listener() {
            @Override public void onDeploy(RelaySiegeGame.Lane lane, int position) { deploySelected(lane, position); }
            @Override public void onMessage(String message) { setMatchMessage(message, MUTED); }
        });
        LinearLayout.LayoutParams arenaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(arena, arenaParams);

        selectionStatus = text("카드를 선택하세요.", 12, MUTED, false);
        selectionStatus.setGravity(Gravity.CENTER);
        selectionStatus.setPadding(0, dp(5), 0, dp(7));
        root.addView(selectionStatus);

        LinearLayout resource = row();
        fluxStatus = text("FLUX -", 13, SUN, true);
        nextStatus = text("NEXT -", 12, MUTED, true);
        resource.addView(fluxStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nextStatus.setGravity(Gravity.END);
        resource.addView(nextStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(resource);

        LinearLayout hand = row();
        hand.setPadding(0, dp(7), 0, 0);
        for (int i = 0; i < handButtons.length; i++) {
            final int index = i;
            handButtons[i] = compactButton("-", PANEL, TEXT, v -> selectHand(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(62), 1f);
            if (i > 0) params.leftMargin = dp(5);
            hand.addView(handButtons[i], params);
        }
        root.addView(hand);

        reconnectButton = primaryButton("Bluetooth 다시 연결", CYAN, BG, v -> reconnectClient());
        reconnectButton.setVisibility(View.GONE);
        root.addView(reconnectButton, matchWrap(dp(7)));
        setContentView(root);

        RelaySiegeNetworkState initial = currentRenderState();
        if (initial != null) arena.setState(initial);
        refreshMatchUi();
        handler.postDelayed(hostTick, 100L);
    }

    private void selectHand(int index) {
        RelaySiegeNetworkState state = currentRenderState();
        if (state == null || index < 0 || index >= state.yourHand.size()) return;
        if (session == null || !session.isStarted() || session.isPaused()) {
            setMatchMessage("상대 연결/동기화가 끝난 뒤 배치할 수 있습니다.", MUTED);
            return;
        }
        String cardId = state.yourHand.get(index);
        arena.selectCard(cardId);
        RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
        selectionStatus.setText(arena.getSelectedCardId() == null
                ? "카드를 선택하세요."
                : card.name + " · " + card.fluxCost + " Flux · 전장에 배치");
        refreshHandButtons(state);
    }

    private void deploySelected(RelaySiegeGame.Lane lane, int position) {
        if (session == null || arena == null || arena.getSelectedCardId() == null) return;
        String cardId = arena.getSelectedCardId();
        RelaySiegeBluetoothSession.LocalResult result = hostRole
                ? session.requestHostPlay(cardId, lane, position)
                : session.requestClientPlay(cardId, lane, position);
        if (result.accepted) {
            setMatchMessage(RelaySiegeCards.card(cardId).name + " 전송 · authoritative 판정 대기", CYAN);
            arena.clearSelection();
        } else {
            setMatchMessage(koreanReason(result.reason), RED);
        }
        refreshMatchUi();
    }

    private RelaySiegeNetworkState currentRenderState() {
        if (session == null) return null;
        if (hostRole && session.getHostGame() != null)
            return RelaySiegeNetworkState.capture(session.getHostGame(), RelaySiegeGame.Player.SUN);
        return session.getClientState();
    }

    private void refreshMatchUi() {
        if (!matchSurfaceActive || arena == null) return;
        RelaySiegeNetworkState state = currentRenderState();
        if (state != null) arena.setState(state);
        String role = hostRole ? "HOST · SUN" : "CLIENT · MOON";
        String link = connectionId == null ? "DISCONNECTED" : "RFCOMM";
        int tick = state == null ? 0 : state.tick;
        matchStatus.setText(role + "  ·  " + link + "  ·  T" + tick);
        if (state == null) {
            objectiveStatus.setText("authoritative state 동기화 중…");
            return;
        }
        objectiveStatus.setText(
                "SUN " + state.sunCoreHp + "  [" + state.sunLeftRelayHp + " | " + state.sunRightRelayHp + "]"
                        + "    vs    MOON " + state.moonCoreHp + "  [" + state.moonLeftRelayHp + " | " + state.moonRightRelayHp + "]");
        fluxStatus.setText(String.format(Locale.US, "FLUX %.1f / 10", state.yourFluxMilli / 1000f));
        RelaySiegeCards.Card next = RelaySiegeCards.card(state.yourNextCard);
        nextStatus.setText("NEXT  " + next.name);
        refreshHandButtons(state);
        if (state.isFinished()) {
            selectionStatus.setText(state.winner == RelaySiegeGame.Player.NONE
                    ? "DRAW · " + state.endReason
                    : state.winner + " 승리 · " + state.endReason);
        } else if (lastMessage != null && !lastMessage.isEmpty()) {
            selectionStatus.setText(lastMessage);
        }
        boolean disconnected = connectionId == null;
        reconnectButton.setVisibility(disconnected && !hostRole && lastDeviceAddress != null ? View.VISIBLE : View.GONE);
    }

    private void refreshHandButtons(RelaySiegeNetworkState state) {
        if (state == null) return;
        String selected = arena == null ? null : arena.getSelectedCardId();
        boolean pending = !hostRole && session != null && session.clientHasPendingPlay();
        for (int i = 0; i < handButtons.length; i++) {
            String cardId = state.yourHand.get(i);
            RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
            handButtons[i].setText(card.name + "\n" + card.fluxCost + " Flux");
            handButtons[i].setTextColor(cardId.equals(selected) ? GREEN : TEXT);
            boolean enough = state.yourFluxMilli >= card.fluxCost * 1000;
            handButtons[i].setEnabled(session != null && session.isStarted() && !session.isPaused() && !pending && enough);
            handButtons[i].setAlpha(handButtons[i].isEnabled() ? 1f : 0.45f);
        }
    }

    private void setMatchMessage(String message, int color) {
        lastMessage = message == null ? "" : message;
        if (selectionStatus != null) {
            selectionStatus.setText(lastMessage);
            selectionStatus.setTextColor(color);
        }
    }

    private void sendPayload(byte[] payload) {
        if (connectionId == null) return;
        bluetooth.sendBytes(nextRequestId(), connectionId, payload);
    }

    private void onConnected(String id, String address) {
        connectionId = id;
        if (address != null && !address.isEmpty()) lastDeviceAddress = address;
        if (session != null) session.onConnected();
        if (lobbyStatus != null) setLobby("RFCOMM 연결됨 · handshake 중…", GREEN);
    }

    private void onDisconnected(String id) {
        if (connectionId != null && !connectionId.equals(id)) return;
        connectionId = null;
        if (session != null) session.onDisconnected();
        if (matchSurfaceActive) {
            setMatchMessage(hostRole
                    ? "연결 끊김 · HOST tick 정지 · 상대 재접속 대기"
                    : "연결 끊김 · 게임 정지 · 다시 연결 가능", RED);
            refreshMatchUi();
        } else if (lobbyStatus != null) {
            setLobby("연결이 끊겼습니다.", RED);
        }
    }

    private boolean ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            return true;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        setLobby("Bluetooth 연결 권한을 승인한 뒤 다시 눌러주세요.", RED);
        return false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setLobby(granted ? "권한 승인됨. 방 만들기/참가를 다시 선택하세요." : "Bluetooth 권한이 필요합니다.",
                    granted ? GREEN : RED);
        }
    }

    private void leaveCurrentMatch(boolean finishing) {
        handler.removeCallbacks(hostTick);
        matchSurfaceActive = false;
        reconnectOnResume = false;
        lastMessage = "";
        arena = null;
        if (session != null) session.onDisconnected();
        session = null;
        connectionId = null;
        if (!finishing) stopTransportOnly();
    }

    private void stopTransportOnly() {
        try { bluetooth.stopAll(nextRequestId()); } catch (Throwable ignored) { }
        connectionId = null;
    }

    private void setLobby(String message, int color) {
        if (lobbyStatus == null) return;
        lobbyStatus.setText(message);
        lobbyStatus.setTextColor(color);
    }

    private void addDeviceMessage(String message) {
        if (deviceList == null) return;
        TextView view = text(message, 13, MUTED, false);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        deviceList.addView(view);
    }

    private final class BluetoothCallback implements SolarBluetoothClassicBridge.Callback {
        @Override public void onOperationResult(long requestId, boolean success, String error) {
            if (!success) runOnUiThread(() -> {
                if (matchSurfaceActive) setMatchMessage("Bluetooth 오류: " + error, RED);
                else setLobby("Bluetooth 오류: " + error, RED);
            });
        }

        @Override public void onConnected(String id, String address, String name) {
            runOnUiThread(() -> RelaySiegeBluetoothActivity.this.onConnected(id, address));
        }

        @Override public void onDisconnected(String id) {
            runOnUiThread(() -> RelaySiegeBluetoothActivity.this.onDisconnected(id));
        }

        @Override public void onBytesReceived(String id, byte[] payload) {
            runOnUiThread(() -> {
                if (connectionId == null || !connectionId.equals(id) || session == null) return;
                session.receive(payload);
            });
        }

        @Override public void onError(String operation, String message) {
            runOnUiThread(() -> {
                String text = "Bluetooth " + operation + ": " + message;
                if (matchSurfaceActive) setMatchMessage(text, RED);
                else setLobby(text, RED);
            });
        }
    }

    private final class SessionListener implements RelaySiegeBluetoothSession.Listener {
        @Override public void onStatus(String status) {
            if ("RUNNING".equals(status)) ensureMatchSurface();
            if (matchSurfaceActive) {
                if ("DISCONNECTED".equals(status)) setMatchMessage("Bluetooth 연결 끊김", RED);
                refreshMatchUi();
            } else if (lobbyStatus != null) {
                setLobby(sessionStatusKorean(status), CYAN);
            }
        }

        @Override public void onClientState(RelaySiegeNetworkState state) {
            ensureMatchSurface();
            refreshMatchUi();
        }

        @Override public void onHostGameChanged(RelaySiegeGame game) {
            if (session != null && session.isStarted()) ensureMatchSurface();
            if (matchSurfaceActive) refreshMatchUi();
        }

        @Override public void onPlayQueued(long sequence, int applyTick) {
            setMatchMessage("배치 #" + sequence + " 승인 · T" + applyTick + " 적용", CYAN);
            refreshMatchUi();
        }

        @Override public void onPlayRejected(long sequence, String reason) {
            setMatchMessage("배치 #" + sequence + " 거절 · " + koreanReason(reason), RED);
            refreshMatchUi();
        }

        @Override public void onProtocolError(String error) {
            setMatchMessage("프로토콜 오류: " + error, RED);
        }
    }

    private static String sessionStatusKorean(String status) {
        if (status == null) return "연결 상태 변경";
        switch (status) {
            case "WAITING_FOR_JOIN": return "RFCOMM 방 열림 · 상대 참가 대기";
            case "WAITING_FOR_RESUME": return "상대 재접속 대기";
            case "JOINING": return "HOST에 참가 요청 중";
            case "RESUMING": return "기존 session 재접속 요청 중";
            case "START_SENT": return "덱 교환 완료 · 초기 state 전송";
            case "RESUME_STATE_SENT": return "복구 state 전송";
            case "START_RECEIVED": return "HOST match 정보 수신";
            default: return status;
        }
    }

    private static String koreanReason(String reason) {
        if (reason == null) return "알 수 없는 오류";
        switch (reason) {
            case "MATCH_NOT_RUNNING": return "게임이 아직 실행 중이 아닙니다.";
            case "ACTION_PENDING": return "이전 배치가 authoritative tick에 적용되는 중입니다.";
            case "CARD_NOT_IN_HAND": return "현재 손패에 없는 카드입니다.";
            case "NOT_ENOUGH_FLUX": return "Flux가 부족합니다.";
            case "INVALID_DEPLOYMENT_POSITION": return "이 위치에는 배치할 수 없습니다.";
            case "MATCH_FINISHED": return "이미 경기가 끝났습니다.";
            default: return reason;
        }
    }

    private Button primaryButton(String value, int background, int foreground, View.OnClickListener click) {
        Button button = compactButton(value, background, foreground, click);
        button.setTextSize(14);
        return button;
    }

    private Button compactButton(String value, int background, int foreground, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(foreground);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(background, dp(12)));
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private long nextRequestId() { return requestIds.incrementAndGet(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
