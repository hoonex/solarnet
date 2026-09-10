package com.hoonex.nightshift;

import com.hoonex.nightshift.core.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Standalone 20 Hz game loop. No network connection or room is involved. */
public final class SoloGameActivity extends Activity {
    private final ScheduledExecutorService gameLoop = Executors.newSingleThreadScheduledExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private SoloGameRuntime runtime;
    private NightshiftGameView gameView;
    private NightshiftAudio audio;
    private AtmosphereOverlay atmosphere;
    private TextView hud, banner, prompt;
    private FrameLayout endPanel;
    private volatile boolean sprint, interact, flashlight = true, paused;
    private volatile double movementAmount;
    private int bannerGeneration;
    private long roundStartNs;
    private GameSnapshot.Phase terminalShown;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        runtime = new SoloGameRuntime();
        audio = new NightshiftAudio();
        roundStartNs = System.nanoTime();
        setContentView(buildUi());
        GameSnapshot initial = runtime.snapshot();
        gameView.setLocalPlayerId(SoloGameRuntime.PLAYER_ID);
        gameView.setSnapshot(initial);
        updatePresentation(initial);
        gameLoop.scheduleAtFixedRate(this::advanceGame, 0, 50, TimeUnit.MILLISECONDS);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        gameView = new NightshiftGameView(this);
        root.addView(gameView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        atmosphere = new AtmosphereOverlay(this);
        root.addView(atmosphere, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE); hud.setTextSize(12); hud.setShadowLayer(5, 0, 1, Color.BLACK);
        hud.setPadding(dp(14), dp(10), dp(14), dp(10));
        hud.setBackground(panel(0xB0101517, 0x403E4B4F, 5));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        hp.setMargins(dp(14), dp(12), 0, 0); root.addView(hud, hp);

        banner = new TextView(this);
        banner.setTextColor(Color.WHITE); banner.setTextSize(18); banner.setGravity(Gravity.CENTER); banner.setShadowLayer(8, 0, 1, Color.BLACK);
        banner.setBackground(panel(0xA80A0E10, 0x557B1E1E, 5));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(620), dp(58), Gravity.TOP | Gravity.CENTER_HORIZONTAL); bp.topMargin = dp(18);
        root.addView(banner, bp);

        prompt = new TextView(this);
        prompt.setTextColor(Color.rgb(230, 236, 231)); prompt.setTextSize(14); prompt.setGravity(Gravity.CENTER); prompt.setShadowLayer(7, 0, 1, Color.BLACK);
        prompt.setBackground(panel(0xA8070B0D, 0x403A474B, 5));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(700), dp(50), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); pp.bottomMargin = dp(16);
        root.addView(prompt, pp);

        TextView moveZone = new TextView(this);
        moveZone.setText("MOVE\nDRAG"); moveZone.setGravity(Gravity.CENTER); moveZone.setTextSize(11); moveZone.setTextColor(Color.argb(115, 220, 230, 225));
        GradientDrawable moveBg = new GradientDrawable(); moveBg.setShape(GradientDrawable.OVAL); moveBg.setColor(0x2210181B); moveBg.setStroke(dp(1), 0x4A9AABAA);
        moveZone.setBackground(moveBg); moveZone.setClickable(false); moveZone.setFocusable(false);
        FrameLayout.LayoutParams mz = new FrameLayout.LayoutParams(dp(106), dp(106), Gravity.BOTTOM | Gravity.LEFT); mz.setMargins(dp(26), 0, 0, dp(24));
        root.addView(moveZone, mz);

        Button run = button("RUN"), use = actionButton("USE"), light = button("LIGHT"), menu = button("MENU");
        addBottom(root, run, 334, 92); addBottom(root, use, 224, 100); addBottom(root, light, 116, 92); addBottom(root, menu, 12, 92);
        run.setOnTouchListener((v, e) -> { sprint = held(e); run.setAlpha(sprint ? 1f : .76f); return true; });
        use.setOnTouchListener((v, e) -> { interact = held(e); use.setAlpha(interact ? 1f : .86f); return true; });
        light.setOnClickListener(v -> { flashlight = !flashlight; light.setAlpha(flashlight ? .86f : .36f); });
        menu.setOnClickListener(v -> finish());

        endPanel = buildEndPanel();
        endPanel.setVisibility(View.GONE);
        root.addView(endPanel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private FrameLayout buildEndPanel() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(232, 1, 3, 4));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(44), dp(32), dp(44), dp(32));
        card.setBackground(panel(0xEE090D0F, 0x667B8788, 8));
        TextView result = new TextView(this); result.setTag("result"); result.setTextColor(Color.WHITE); result.setTextSize(34); result.setLetterSpacing(.08f); result.setGravity(Gravity.CENTER);
        TextView detail = new TextView(this); detail.setTag("detail"); detail.setTextColor(Color.rgb(170, 185, 188)); detail.setTextSize(15); detail.setGravity(Gravity.CENTER); detail.setPadding(0, dp(10), 0, dp(22));
        Button retry = actionButton("RETRY SHIFT"), menu = button("MAIN MENU");
        card.addView(result, new LinearLayout.LayoutParams(dp(600), dp(70)));
        card.addView(detail, new LinearLayout.LayoutParams(dp(600), dp(70)));
        card.addView(retry, new LinearLayout.LayoutParams(dp(330), dp(58)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(330), dp(54)); mp.topMargin = dp(10); card.addView(menu, mp);
        retry.setOnClickListener(v -> restartSolo());
        menu.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(700), dp(340), Gravity.CENTER);
        overlay.addView(card, cp);
        return overlay;
    }

    private void advanceGame() {
        if (paused || terminalShown != null || runtime == null || gameView == null) return;
        double forward = gameView.forward(), strafe = gameView.strafe();
        double movement = Math.min(1.0, Math.sqrt(forward * forward + strafe * strafe));
        movementAmount = movement;
        gameView.setMotionState(movement, sprint);
        SoloGameRuntime.Frame frame = runtime.step(forward, strafe, gameView.yaw(), sprint, interact, flashlight);
        gameView.setSnapshot(frame.snapshot);
        for (GameEvent event : frame.events) {
            String message = formatEvent(event.type);
            uiHandler.post(() -> {
                if (message != null) showBanner(message);
                audio.onEvent(event.type);
                eventHaptic(event.type);
            });
        }
        uiHandler.post(() -> updatePresentation(frame.snapshot));
    }

    private void updatePresentation(GameSnapshot snapshot) {
        GameSnapshot.PlayerView me = player(snapshot, SoloGameRuntime.PLAYER_ID);
        if (me == null) return;
        int powered = 0; for (boolean b : snapshot.breakers) if (b) powered++;
        int seconds = (int)((System.nanoTime() - roundStartNs) / 1_000_000_000L);
        String danger = me.tension > .72 ? "  ·  HUNTER CLOSE" : me.tension > .40 ? "  ·  STAY QUIET" : "";
        String carry = me.carryingFuse ? "  ·  FUSE CARRIED" : "";
        String powerOut = snapshot.blackout ? "  ·  BLACKOUT" : "";
        hud.setText("NIGHTSHIFT // SOLO    " + mmss(seconds) + "\nPOWER " + powered + "/3" +
            (snapshot.keycardRecovered ? "  ·  KEYCARD ✓" : "  ·  KEYCARD ?") + carry + powerOut + danger +
            "\nSTAMINA " + Math.round(me.stamina * 100) + "%  ·  LIGHT " + Math.round(me.flashlightBattery * 100) + "%  ·  THREAT " + snapshot.threatLevel + "/5");
        prompt.setText(contextPrompt(snapshot, me));
        atmosphere.setState(me, snapshot.blackout, movementAmount, sprint);
        audio.onFrame(me, movementAmount > .08, sprint, snapshot.blackout);

        if ((snapshot.phase == GameSnapshot.Phase.WON || snapshot.phase == GameSnapshot.Phase.LOST) && terminalShown == null) {
            terminalShown = snapshot.phase;
            showEnd(snapshot.phase, seconds);
        }
    }

    private String contextPrompt(GameSnapshot s, GameSnapshot.PlayerView me) {
        Vec2 pos = new Vec2(me.x, me.z);
        if (!s.keycardRecovered && pos.distance(FacilityMap.KEYCARD) <= 2.0) return "HOLD USE  ·  TAKE SECURITY KEYCARD";
        if (!me.carryingFuse) {
            for (int i = 0; i < FacilityMap.FUSES.length; i++) {
                if (!s.fusesTaken[i] && pos.distance(FacilityMap.FUSES[i]) <= 2.0) return "HOLD USE  ·  PICK UP FUSE";
            }
        } else {
            for (int i = 0; i < FacilityMap.BREAKERS.length; i++) {
                if (!s.breakers[i] && pos.distance(FacilityMap.BREAKERS[i]) <= 2.0) return "HOLD USE  ·  INSTALL FUSE / RESTORE BREAKER";
            }
        }
        if (pos.distance(FacilityMap.EXIT) <= 2.4) {
            if (s.exitUnlocked) return "HOLD USE  ·  EXTRACT";
            int powered = 0; for (boolean b : s.breakers) if (b) powered++;
            return "EXTRACTION LOCKED  ·  POWER " + powered + "/3  ·  KEYCARD " + (s.keycardRecovered ? "✓" : "MISSING");
        }
        if (me.tension > .72) return "HUNTER VERY CLOSE  ·  STOP SPRINTING / BREAK LINE OF SIGHT";
        if (me.carryingFuse) return "OBJECTIVE  ·  FIND AN UNPOWERED BREAKER";
        if (!s.keycardRecovered) return "OBJECTIVE  ·  SEARCH FOR FUSES + SECURITY KEYCARD";
        int powered = 0; for (boolean b : s.breakers) if (b) powered++;
        if (powered < 3) return "OBJECTIVE  ·  FIND A FUSE, THEN RESTORE A BREAKER";
        return "OBJECTIVE  ·  REACH THE NORTH EXTRACTION DOOR";
    }

    private void showEnd(GameSnapshot.Phase phase, int seconds) {
        TextView result = endPanel.findViewWithTag("result");
        TextView detail = endPanel.findViewWithTag("detail");
        if (phase == GameSnapshot.Phase.WON) {
            result.setText("SHIFT SURVIVED");
            detail.setText("Power restored. Security recovered. Extraction in " + mmss(seconds) + ".");
        } else {
            result.setText("YOU WERE TAKEN");
            detail.setText("The hunter found you. Break line of sight, walk quietly, and save sprint for escape.");
        }
        endPanel.setVisibility(View.VISIBLE);
    }

    private void restartSolo() {
        Intent again = new Intent(this, SoloGameActivity.class);
        finish();
        startActivity(again);
    }

    private String formatEvent(GameEvent.Type type) {
        return switch (type) {
            case FUSE_PICKED -> "FUSE RECOVERED";
            case KEYCARD_RECOVERED -> "SECURITY KEYCARD RECOVERED";
            case FUSE_INSERTED -> "FUSE INSTALLED";
            case BREAKER_ACTIVATED -> "POWER CIRCUIT ONLINE";
            case HUNT_SURGE -> "THE HUNTER HEARD THAT";
            case BLACKOUT_STARTED -> "POWER FAILURE";
            case BLACKOUT_ENDED -> "EMERGENCY LIGHTS RESTORED";
            case EXIT_UNLOCKED -> "EXTRACTION DOOR UNLOCKED";
            case PLAYER_DOWNED -> "THE HUNTER GOT YOU";
            case PLAYER_ESCAPED -> "EXTRACTION COMPLETE";
            case MATCH_WON -> "SHIFT SURVIVED";
            case MATCH_LOST -> "NO WAY OUT";
            default -> null;
        };
    }

    private void eventHaptic(GameEvent.Type type) {
        if (gameView == null) return;
        switch (type) {
            case HUNT_SURGE, BLACKOUT_STARTED, PLAYER_DOWNED -> gameView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            case FUSE_PICKED, KEYCARD_RECOVERED, BREAKER_ACTIVATED -> gameView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            default -> { }
        }
    }

    private void showBanner(String text) {
        int generation = ++bannerGeneration;
        banner.setText(text);
        banner.setAlpha(1f);
        uiHandler.postDelayed(() -> { if (generation == bannerGeneration) banner.animate().alpha(0f).setDuration(260).withEndAction(() -> banner.setText("")); }, 1900);
    }

    private static GameSnapshot.PlayerView player(GameSnapshot s, int id) {
        for (GameSnapshot.PlayerView p : s.players) if (p.id == id) return p;
        return null;
    }

    private static boolean held(MotionEvent e) {
        int action = e.getActionMasked();
        return action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setTextSize(12); b.setTextColor(Color.rgb(225, 232, 228));
        b.setBackground(panel(0xC7172023, 0x707D8A8C, 7)); b.setAlpha(.86f); return b;
    }

    private Button actionButton(String label) {
        Button b = new Button(this); b.setText(label); b.setTextSize(13); b.setTextColor(Color.WHITE);
        b.setBackground(panel(0xE53E1717, 0xA8A94A43, 7)); b.setAlpha(.92f); return b;
    }

    private GradientDrawable panel(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(1), stroke); return d;
    }

    private void addBottom(FrameLayout root, View v, int rightMargin, int width) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(width), dp(56), Gravity.BOTTOM | Gravity.RIGHT);
        p.setMargins(dp(8), dp(8), dp(rightMargin), dp(12)); root.addView(v, p);
    }

    private static String mmss(int seconds) {
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume(); paused = false; if (gameView != null) gameView.onResume(); if (audio != null) audio.start();
    }

    @Override protected void onPause() {
        paused = true; if (audio != null) audio.pause(); if (gameView != null) gameView.onPause(); super.onPause();
    }

    @Override protected void onDestroy() {
        gameLoop.shutdownNow(); uiHandler.removeCallbacksAndMessages(null); if (audio != null) audio.release(); super.onDestroy();
    }
}
