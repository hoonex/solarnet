package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameSnapshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional network lobby. Kept separate so the APK is a game even with no server. */
public final class MultiplayerActivity extends Activity implements TcpGameClient.Listener {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText hostField, portField, nameField, roomField;
    private TextView status, roomDisplay;
    private Button createButton, joinButton, readyButton, startButton;
    private TcpGameClient client;
    private boolean launched;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setBackgroundColor(Color.rgb(4, 6, 8));

        LinearLayout left = column();
        TextView title = text("NIGHTSHIFT · MULTIPLAYER", 30, Color.WHITE);
        TextView sub = text("EXPERIMENTAL 1–4 PLAYER SERVER-AUTHORITATIVE MODE", 13, Color.rgb(130, 145, 150));
        left.addView(title); left.addView(sub);
        left.addView(text("Solo play does not need this screen. Multiplayer currently requires a reachable Nightshift authority server.", 15, Color.rgb(205,210,210)));
        Button back = button("BACK TO MENU");
        back.setOnClickListener(v -> finish());
        left.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));

        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(12), dp(20), dp(12));
        hostField = field("Server host / IP", "10.0.2.2");
        portField = field("Port", "46000"); portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        nameField = field("Name", "Player");
        roomField = field("Room code", ""); roomField.setAllCaps(true);
        panel.addView(hostField); panel.addView(portField); panel.addView(nameField); panel.addView(roomField);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        createButton = button("CREATE ROOM"); joinButton = button("JOIN ROOM");
        row.addView(createButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        row.addView(joinButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        panel.addView(row);

        roomDisplay = text("", 18, Color.WHITE); panel.addView(roomDisplay);
        status = text("Enter a server endpoint.", 13, Color.rgb(145,160,165)); panel.addView(status);

        LinearLayout lobby = new LinearLayout(this); lobby.setOrientation(LinearLayout.HORIZONTAL);
        readyButton = button("READY"); startButton = button("START");
        readyButton.setVisibility(View.GONE); startButton.setVisibility(View.GONE);
        lobby.addView(readyButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        lobby.addView(startButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        panel.addView(lobby);

        createButton.setOnClickListener(v -> connect(true));
        joinButton.setOnClickListener(v -> connect(false));
        readyButton.setOnClickListener(v -> io.execute(() -> {
            try { client.setReady(true); ui(() -> { readyButton.setEnabled(false); status.setText("READY · waiting for leader"); }); }
            catch (IOException e) { showError(e.getMessage()); }
        }));
        startButton.setOnClickListener(v -> io.execute(() -> {
            try { client.startMatch(); } catch (IOException e) { showError(e.getMessage()); }
        }));

        root.addView(panel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f));
        return root;
    }

    private void connect(boolean create) {
        createButton.setEnabled(false); joinButton.setEnabled(false);
        status.setText("Connecting…");
        String host = hostField.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (Exception e) { resetButtons("Bad port"); return; }
        String name = nameField.getText().toString().trim();
        String room = roomField.getText().toString().trim();
        io.execute(() -> {
            try {
                TcpGameClient c = new TcpGameClient(this);
                c.connect(host, port);
                client = c; NightshiftSession.set(c);
                if (create) c.createRoom(name); else c.joinRoom(room, name);
            } catch (Exception e) { showError(e.getMessage()); }
        });
    }

    @Override public void onWelcome(String roomCode, int playerId, boolean owner) {
        ui(() -> {
            roomDisplay.setText("ROOM  " + roomCode + "   ·   PLAYER " + playerId + (owner ? "   ·   LEADER" : ""));
            status.setText("Connected. Mark ready; leader starts when the team is in.");
            readyButton.setVisibility(View.VISIBLE);
            startButton.setVisibility(owner ? View.VISIBLE : View.GONE);
        });
    }

    @Override public void onSnapshot(GameSnapshot snapshot) {
        if (snapshot.phase == GameSnapshot.Phase.PLAYING && !launched) {
            launched = true;
            ui(() -> startActivity(new Intent(this, GameActivity.class)));
        } else if (snapshot.phase == GameSnapshot.Phase.LOBBY) {
            ui(() -> status.setText("Lobby · " + snapshot.players.size() + "/4 connected"));
        }
    }

    @Override public void onEvent(String text) { ui(() -> status.setText(text)); }
    @Override public void onError(String text) { showError(text); }
    @Override public void onDisconnected() { ui(() -> resetButtons("Disconnected")); }

    private void showError(String text) { ui(() -> resetButtons(text == null ? "Network error" : text)); }
    private void resetButtons(String text) {
        status.setText(text); createButton.setEnabled(true); joinButton.setEnabled(true);
    }
    private void ui(Runnable r) { runOnUiThread(r); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String s, int sp, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setPadding(0, dp(8), 0, dp(8)); return v; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.rgb(100,110,115)); return e; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); return b; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (isFinishing() && !launched && client != null) { client.close(); NightshiftSession.clear(client); }
    }
}
