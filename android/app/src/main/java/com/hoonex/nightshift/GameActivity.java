package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class GameActivity extends Activity implements TcpGameClient.Listener {
    private final ScheduledExecutorService inputLoop = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger inputSequence = new AtomicInteger(1);
    private NightshiftGameView gameView;
    private TcpGameClient client;
    private TextView hud;
    private volatile boolean sprint, interact, flashlight = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = NightshiftSession.get();
        if (client == null) { finish(); return; }
        client.setListener(this);

        FrameLayout root = new FrameLayout(this);
        gameView = new NightshiftGameView(this);
        gameView.setLocalPlayerId(client.playerId());
        GameSnapshot existing = client.latestSnapshot(); if (existing != null) gameView.setSnapshot(existing);
        root.addView(gameView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE); hud.setTextSize(13); hud.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        root.addView(hud, hp);

        Button run = button("RUN");
        Button use = button("USE");
        Button light = button("LIGHT");
        addBottom(root, run, Gravity.RIGHT, 226);
        addBottom(root, use, Gravity.RIGHT, 118);
        addBottom(root, light, Gravity.RIGHT, 10);
        run.setOnTouchListener((v,e) -> { sprint = e.getActionMasked() != MotionEvent.ACTION_UP && e.getActionMasked() != MotionEvent.ACTION_CANCEL; return true; });
        use.setOnTouchListener((v,e) -> { interact = e.getActionMasked() != MotionEvent.ACTION_UP && e.getActionMasked() != MotionEvent.ACTION_CANCEL; return true; });
        light.setOnClickListener(v -> { flashlight = !flashlight; light.setAlpha(flashlight ? 1f : 0.45f); });

        setContentView(root);
        inputLoop.scheduleAtFixedRate(this::sendInput, 0, 50, TimeUnit.MILLISECONDS);
    }

    private void sendInput() {
        if (client == null || gameView == null) return;
        try {
            client.sendInput(new GameInput(inputSequence.getAndIncrement(), gameView.forward(), gameView.strafe(), gameView.yaw(), sprint, interact, flashlight));
        } catch (IOException ignored) { }
    }

    @Override public void onSnapshot(GameSnapshot snapshot) {
        gameView.setSnapshot(snapshot);
        GameSnapshot.PlayerView me = null;
        for (GameSnapshot.PlayerView p : snapshot.players) if (p.id == client.playerId()) me = p;
        GameSnapshot.PlayerView finalMe = me;
        runOnUiThread(() -> {
            if (finalMe == null) { hud.setText("Disconnected from room"); return; }
            int active = 0; for (boolean b : snapshot.breakers) if (b) active++;
            hud.setText("ROOM " + client.roomCode() + "  ·  BREAKERS " + active + "/" + snapshot.breakers.length +
                "  ·  STAMINA " + Math.round(finalMe.stamina * 100) + "%  ·  LIGHT " + Math.round(finalMe.flashlightBattery * 100) + "%" +
                (finalMe.downed ? "  ·  DOWNED" : "") + (snapshot.exitUnlocked ? "  ·  EXIT OPEN" : ""));
            if (snapshot.phase == GameSnapshot.Phase.WON || snapshot.phase == GameSnapshot.Phase.LOST) {
                hud.setText(hud.getText() + "  ·  " + snapshot.phase.name());
            }
        });
    }

    @Override public void onEvent(String text) { }
    @Override public void onError(String text) { runOnUiThread(() -> hud.setText("Network error: " + text)); }
    @Override public void onDisconnected() { runOnUiThread(() -> hud.setText("Disconnected")); }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAlpha(0.82f); return b; }
    private void addBottom(FrameLayout root, View v, int gravity, int rightMargin) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(96), dp(56), Gravity.BOTTOM | gravity);
        p.setMargins(dp(10), dp(10), dp(rightMargin), dp(12)); root.addView(v, p);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        inputLoop.shutdownNow();
        if (client != null) { client.close(); NightshiftSession.clear(client); }
        super.onDestroy();
    }
}
