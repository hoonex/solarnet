package com.hoonex.solarnet.gridduel;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class PulseHockeyActivity extends Activity {
    private enum Mode {
        AI,
        LOCAL_2P
    }

    private static final int BG = Color.rgb(5, 8, 14);
    private static final int PANEL = Color.rgb(17, 22, 32);
    private static final int PANEL_2 = Color.rgb(25, 31, 44);
    private static final int TEXT = Color.rgb(244, 247, 252);
    private static final int MUTED = Color.rgb(152, 162, 181);
    private static final int CYAN = Color.rgb(96, 224, 255);
    private static final int SUN = Color.rgb(255, 174, 61);
    private static final int MOON = Color.rgb(111, 130, 255);
    private static final int RED = Color.rgb(255, 77, 105);
    private static final int GREEN = Color.rgb(96, 226, 156);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong rematchCounter = new AtomicLong();

    private Mode mode;
    private PulseHockeyGame game;
    private PulseHockey3DView arena;
    private PulseHockeyGame.Tactic selectedTactic = PulseHockeyGame.Tactic.STRIKE;
    private boolean busy;
    private boolean aiScheduled;

    private TextView turnView;
    private TextView scoreView;
    private TextView sunEnergyView;
    private TextView moonEnergyView;
    private TextView overdriveView;
    private TextView hintView;
    private Button strikeButton;
    private Button powerButton;
    private Button guardButton;
    private Button counterButton;
    private Button rematchButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showModeSelect();
    }

    private void showModeSelect() {
        handler.removeCallbacksAndMessages(null);
        aiScheduled = false;
        busy = false;
        mode = null;

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(30), dp(22), dp(28));

        Button back = compactButton("← SOLAR ARCADE", PANEL_2, TEXT, v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(142), dp(44));
        root.addView(back, backParams);

        TextView eyebrow = text("3D TACTICAL AIR HOCKEY", 12, CYAN, true);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setPadding(0, dp(28), 0, dp(7));
        root.addView(eyebrow);

        TextView title = text("PULSE\nHOCKEY", 44, TEXT, true);
        title.setLineSpacing(-dp(5), 0.92f);
        root.addView(title);

        TextView description = text(
                "퍽 물리 + 턴제 전술. 드래그로 방향과 세기를 정하고, 공격·파워샷·가드·카운터를 선택합니다.",
                16,
                MUTED,
                false);
        description.setPadding(0, dp(12), 0, dp(24));
        root.addView(description);

        root.addView(modeCard(
                "AI BATTLE",
                "상대의 다음 응수까지 일부 계산하는 전술 AI",
                SUN,
                v -> startMatch(Mode.AI)));
        root.addView(modeCard(
                "LOCAL 2P",
                "한 폰에서 SUN / MOON 번갈아 플레이",
                MOON,
                v -> startMatch(Mode.LOCAL_2P)));

        TextView rules = text(
                "승리 조건  5골\n" +
                "STRIKE  기본 공격 · POWER  에너지 3 · GUARD  방어+회복 · COUNTER  반사 강화\n" +
                "무득점이 길어지면 OVERDRIVE가 골문을 넓히고 샷을 가속합니다. 36턴이 지나면 점수로 판정되어 무한 경기가 없습니다.",
                13,
                MUTED,
                false);
        rules.setPadding(dp(3), dp(24), dp(3), 0);
        root.addView(rules);

        setContentView(root);
    }

    private void startMatch(Mode selectedMode) {
        mode = selectedMode;
        long seed = System.nanoTime() ^ (rematchCounter.incrementAndGet() * 0x9E3779B97F4A7C15L);
        game = new PulseHockeyGame(seed);
        selectedTactic = PulseHockeyGame.Tactic.STRIKE;
        busy = false;
        aiScheduled = false;
        showArena();
        refreshUi(null);
        maybeScheduleAi();
    }

    private void showArena() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        arena = new PulseHockey3DView(this);
        arena.setAimListener(new PulseHockey3DView.AimListener() {
            @Override
            public void onAimChanged(float dirX, float dirZ, float power) {
                if (!canHumanAct()) return;
                arena.showAim(game.getActivePlayer(), dirX, dirZ, power, selectedTactic);
                hintView.setText(aimHint(dirX, dirZ, power));
            }

            @Override
            public void onAimReleased(float dirX, float dirZ, float power) {
                if (!canHumanAct()) return;
                performHumanAction(dirX, dirZ, power);
            }
        });
        root.addView(arena, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = column();
        top.setPadding(dp(14), dp(12), dp(14), 0);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton("← 나가기", PANEL_2, TEXT, v -> showModeSelect());
        nav.addView(back, new LinearLayout.LayoutParams(dp(94), dp(42)));
        View spacer = new View(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        overdriveView = pill("NORMAL", CYAN);
        nav.addView(overdriveView);
        top.addView(nav);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(2), dp(10), dp(2), 0);

        turnView = text("", 18, TEXT, true);
        status.addView(turnView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scoreView = text("0  :  0", 24, TEXT, true);
        scoreView.setGravity(Gravity.END);
        status.addView(scoreView);
        top.addView(status);

        LinearLayout energies = new LinearLayout(this);
        energies.setOrientation(LinearLayout.HORIZONTAL);
        energies.setPadding(0, dp(7), 0, 0);
        sunEnergyView = energyCard("SUN", SUN);
        moonEnergyView = energyCard("MOON", MOON);
        energies.addView(sunEnergyView, new LinearLayout.LayoutParams(0, dp(55), 1f));
        LinearLayout.LayoutParams moonParams = new LinearLayout.LayoutParams(0, dp(55), 1f);
        moonParams.leftMargin = dp(8);
        energies.addView(moonEnergyView, moonParams);
        top.addView(energies);
        root.addView(top, topParams);

        LinearLayout controls = column();
        controls.setPadding(dp(12), 0, dp(12), dp(12));
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);

        hintView = text("드래그해서 조준", 13, TEXT, true);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackground(rounded(Color.argb(205, 17, 22, 32), dp(13)));
        hintView.setPadding(dp(12), dp(9), dp(12), dp(9));
        controls.addView(hintView, matchWrap(dp(6)));

        LinearLayout tactics = new LinearLayout(this);
        tactics.setOrientation(LinearLayout.HORIZONTAL);
        strikeButton = tacticButton("STRIKE", PulseHockeyGame.Tactic.STRIKE);
        powerButton = tacticButton("POWER", PulseHockeyGame.Tactic.POWER);
        guardButton = tacticButton("GUARD", PulseHockeyGame.Tactic.GUARD);
        counterButton = tacticButton("COUNTER", PulseHockeyGame.Tactic.COUNTER);
        tactics.addView(strikeButton, tacticParams(0));
        tactics.addView(powerButton, tacticParams(dp(5)));
        tactics.addView(guardButton, tacticParams(dp(5)));
        tactics.addView(counterButton, tacticParams(dp(5)));
        controls.addView(tactics);

        rematchButton = compactButton("새 매치", PANEL_2, MUTED, v -> startMatch(mode));
        rematchButton.setVisibility(View.GONE);
        controls.addView(rematchButton, matchWrap(dp(7)));
        root.addView(controls, controlsParams);

        setContentView(root);
        arena.showGame(game);
    }

    private void performHumanAction(float dirX, float dirZ, float power) {
        PulseHockeyGame.Action action;
        if (selectedTactic == PulseHockeyGame.Tactic.GUARD || selectedTactic == PulseHockeyGame.Tactic.COUNTER) {
            float currentX = game.getMalletX(game.getActivePlayer());
            float targetX = clamp(currentX + dirX * (1.6f + power * 3.4f), -3.9f, 3.9f);
            action = selectedTactic == PulseHockeyGame.Tactic.GUARD
                    ? PulseHockeyGame.Action.guard(targetX)
                    : PulseHockeyGame.Action.counter(targetX);
        } else {
            action = selectedTactic == PulseHockeyGame.Tactic.POWER
                    ? PulseHockeyGame.Action.power(dirX, dirZ, power)
                    : PulseHockeyGame.Action.strike(dirX, dirZ, power);
        }
        executeAction(action, false);
    }

    private void executeAction(PulseHockeyGame.Action action, boolean fromAi) {
        if (busy || game == null || game.isFinished()) return;
        PulseHockeyGame.Player actor = game.getActivePlayer();
        PulseHockeyGame.TurnResult result = game.apply(action, true);
        if (!result.accepted) {
            refreshUi(result.message);
            return;
        }

        busy = true;
        aiScheduled = false;
        arena.setAimEnabled(false);
        arena.clearAim();
        refreshUi(fromAi ? "AI: " + tacticName(action.tactic) : tacticName(action.tactic));
        animateFrames(result.frames, 0, () -> {
            busy = false;
            arena.showGame(game);
            selectedTactic = defaultTacticForCurrentPlayer();
            refreshUi(result.goal ? goalMessage(result.scorer) : result.counterTriggered ? "COUNTER! 반격 성공" : null);
            maybeScheduleAi();
        });
    }

    private void animateFrames(List<PulseHockeyGame.MotionFrame> frames, int index, Runnable done) {
        if (frames == null || index >= frames.size()) {
            done.run();
            return;
        }
        arena.showFrame(frames.get(index), game);
        handler.postDelayed(() -> animateFrames(frames, index + 1, done), 18L);
    }

    private void maybeScheduleAi() {
        if (mode != Mode.AI || game == null || game.isFinished() || busy) return;
        if (game.getActivePlayer() != PulseHockeyGame.Player.MOON || aiScheduled) return;
        aiScheduled = true;
        arena.setAimEnabled(false);
        hintView.setText("MOON AI가 수를 계산 중…");
        handler.postDelayed(() -> {
            aiScheduled = false;
            if (mode != Mode.AI || game == null || game.isFinished() || busy
                    || game.getActivePlayer() != PulseHockeyGame.Player.MOON) return;
            PulseHockeyGame.Action action = PulseHockeyAi.chooseAction(game);
            if (action != null) executeAction(action, true);
        }, 420L);
    }

    private boolean canHumanAct() {
        if (game == null || game.isFinished() || busy || aiScheduled) return false;
        return mode == Mode.LOCAL_2P || game.getActivePlayer() == PulseHockeyGame.Player.SUN;
    }

    private void selectTactic(PulseHockeyGame.Tactic tactic) {
        if (game == null || busy || game.isFinished() || !canHumanAct()) return;
        PulseHockeyGame.Player player = game.getActivePlayer();
        if (!game.canUse(player, tactic)) {
            hintView.setText("에너지 부족 · POWER는 3 필요");
            return;
        }
        selectedTactic = tactic;
        arena.clearAim();
        refreshTacticButtons();
        if (tactic == PulseHockeyGame.Tactic.GUARD) hintView.setText("좌우로 드래그해 수비 위치 선택");
        else if (tactic == PulseHockeyGame.Tactic.COUNTER) hintView.setText("반격 위치 선택 · 막으면 퍽 속도 1.38×");
        else hintView.setText("드래그 방향 = 조준 · 길이 = 세기");
    }

    private void refreshUi(String transientMessage) {
        if (game == null || turnView == null) return;
        scoreView.setText(game.getSunScore() + "  :  " + game.getMoonScore());
        sunEnergyView.setText("☀  SUN\n" + energyBar(game.getEnergy(PulseHockeyGame.Player.SUN)));
        moonEnergyView.setText("◐  MOON\n" + energyBar(game.getEnergy(PulseHockeyGame.Player.MOON)));

        if (game.isFinished()) {
            if (game.isDraw()) {
                turnView.setText("DRAW · 36턴 판정");
                turnView.setTextColor(TEXT);
            } else {
                turnView.setText(game.getWinner() + " 승리");
                turnView.setTextColor(game.getWinner() == PulseHockeyGame.Player.SUN ? SUN : MOON);
            }
            hintView.setText("매치 종료");
            rematchButton.setVisibility(View.VISIBLE);
            arena.setAimEnabled(false);
        } else {
            PulseHockeyGame.Player active = game.getActivePlayer();
            turnView.setText((active == PulseHockeyGame.Player.SUN ? "SUN" : "MOON") + " TURN  ·  " + (game.getTurnNumber() + 1) + "/" + PulseHockeyGame.MAX_TURNS);
            turnView.setTextColor(active == PulseHockeyGame.Player.SUN ? SUN : MOON);
            if (transientMessage != null) hintView.setText(transientMessage);
            else if (mode == Mode.AI && active == PulseHockeyGame.Player.MOON) hintView.setText("MOON AI 차례");
            else hintView.setText(selectedTactic == PulseHockeyGame.Tactic.GUARD || selectedTactic == PulseHockeyGame.Tactic.COUNTER
                    ? "좌우 드래그로 수비 위치 선택"
                    : "드래그 방향 = 조준 · 길이 = 세기");
            rematchButton.setVisibility(View.GONE);
            arena.setAimEnabled(canHumanAct());
        }

        int overdrive = game.getOverdriveLevel();
        if (overdrive <= 0) {
            overdriveView.setText("NORMAL");
            overdriveView.setTextColor(CYAN);
        } else {
            overdriveView.setText("OVERDRIVE " + overdrive);
            overdriveView.setTextColor(RED);
        }
        refreshTacticButtons();
    }

    private void refreshTacticButtons() {
        if (strikeButton == null || game == null) return;
        PulseHockeyGame.Player player = game.getActivePlayer();
        styleTactic(strikeButton, PulseHockeyGame.Tactic.STRIKE, CYAN, game.canUse(player, PulseHockeyGame.Tactic.STRIKE));
        styleTactic(powerButton, PulseHockeyGame.Tactic.POWER, RED, game.canUse(player, PulseHockeyGame.Tactic.POWER));
        styleTactic(guardButton, PulseHockeyGame.Tactic.GUARD, GREEN, game.canUse(player, PulseHockeyGame.Tactic.GUARD));
        styleTactic(counterButton, PulseHockeyGame.Tactic.COUNTER, MOON, game.canUse(player, PulseHockeyGame.Tactic.COUNTER));
    }

    private void styleTactic(Button button, PulseHockeyGame.Tactic tactic, int accent, boolean available) {
        boolean selected = tactic == selectedTactic;
        GradientDrawable bg = rounded(selected ? withAlpha(accent, 62) : Color.argb(225, 20, 25, 35), dp(13));
        bg.setStroke(dp(selected ? 2 : 1), withAlpha(accent, selected ? 220 : 75));
        button.setBackground(bg);
        button.setTextColor(available ? TEXT : Color.rgb(89, 95, 108));
        button.setAlpha(available ? 1f : 0.55f);
    }

    private PulseHockeyGame.Tactic defaultTacticForCurrentPlayer() {
        PulseHockeyGame.Player player = game.getActivePlayer();
        if (game.canUse(player, PulseHockeyGame.Tactic.STRIKE)) return PulseHockeyGame.Tactic.STRIKE;
        return PulseHockeyGame.Tactic.GUARD;
    }

    private String aimHint(float dirX, float dirZ, float power) {
        int percent = Math.round(power * 100f);
        String side = Math.abs(dirX) < 0.18f ? "직선" : dirX < 0f ? "좌측" : "우측";
        return side + " · POWER " + percent + "%";
    }

    private String goalMessage(PulseHockeyGame.Player scorer) {
        return scorer == PulseHockeyGame.Player.SUN ? "GOAL · SUN" : "GOAL · MOON";
    }

    private String tacticName(PulseHockeyGame.Tactic tactic) {
        switch (tactic) {
            case POWER: return "POWER STRIKE";
            case GUARD: return "GUARD";
            case COUNTER: return "COUNTER";
            case STRIKE:
            default: return "STRIKE";
        }
    }

    private Button tacticButton(String label, PulseHockeyGame.Tactic tactic) {
        Button button = compactButton(label, PANEL, TEXT, v -> selectTactic(tactic));
        button.setTextSize(11.5f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return button;
    }

    private LinearLayout.LayoutParams tacticParams(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = leftMargin;
        return params;
    }

    private View modeCard(String title, String subtitle, int accent, View.OnClickListener click) {
        LinearLayout card = column();
        GradientDrawable bg = rounded(PANEL, dp(19));
        bg.setStroke(dp(1), withAlpha(accent, 85));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);
        TextView heading = text(title, 19, TEXT, true);
        TextView body = text(subtitle, 13, MUTED, false);
        body.setPadding(0, dp(4), 0, 0);
        card.addView(heading);
        card.addView(body);
        card.setLayoutParams(matchWrap(dp(9)));
        return card;
    }

    private TextView energyCard(String label, int accent) {
        TextView view = text(label, 14, TEXT, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(13), dp(7), dp(13), dp(7));
        GradientDrawable bg = rounded(Color.argb(205, 17, 22, 32), dp(14));
        bg.setStroke(dp(1), withAlpha(accent, 75));
        view.setBackground(bg);
        return view;
    }

    private TextView pill(String label, int accent) {
        TextView view = text(label, 11, accent, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        view.setBackground(rounded(Color.argb(215, 18, 23, 33), dp(20)));
        return view;
    }

    private Button compactButton(String label, int background, int foreground, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(rounded(background, dp(13)));
        button.setOnClickListener(listener);
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

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private String energyBar(int energy) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < PulseHockeyGame.MAX_ENERGY; i++) builder.append(i < energy ? "●" : "·");
        return builder.toString();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arena != null) arena.onResume();
    }

    @Override
    protected void onPause() {
        if (arena != null) arena.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
