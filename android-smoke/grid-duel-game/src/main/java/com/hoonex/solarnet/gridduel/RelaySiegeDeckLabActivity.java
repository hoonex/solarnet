package com.hoonex.solarnet.gridduel;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Player-facing deck construction, analysis and custom-deck practice surface. */
public final class RelaySiegeDeckLabActivity extends Activity {
    private static final String PREFS = "relay_siege_deck_lab";
    private static final String KEY_DECK = "ordered_deck_v1";

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

    private final ArrayList<String> deckIds = new ArrayList<>();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private int editingSlot;
    private String opponentDeckId = "counterforge";
    private long matchCounter;

    private TextView statusView;
    private TextView probeView;
    private RelaySiegeArenaView arenaView;
    private TextView matchTopView;
    private TextView matchObjectiveView;
    private TextView matchMessageView;
    private TextView nextCardView;
    private TextView selectedCardView;
    private Button rematchButton;
    private final Button[] handButtons = new Button[4];
    private String lastHandSignature = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        loadDeck();
        showLab();
    }

    @Override
    protected void onPause() {
        if (arenaView != null) arenaView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arenaView != null) arenaView.resume();
    }

    @Override
    protected void onDestroy() {
        if (arenaView != null) arenaView.pause();
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadDeck() {
        deckIds.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_DECK, null);
        if (saved != null) {
            try {
                deckIds.addAll(RelaySiegeDeckLab.decode(saved));
            } catch (RuntimeException ignored) { }
        }
        if (deckIds.size() != RelaySiegeDeckLab.DECK_SIZE)
            deckIds.addAll(RelaySiegeCards.deck("counterforge").cardIds);
    }

    private void saveDeck() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_DECK, RelaySiegeDeckLab.encode(deckIds))
                .apply();
    }

    private void clearArena() {
        if (arenaView != null) arenaView.pause();
        arenaView = null;
        lastHandSignature = "";
    }

    private void showLab() {
        clearArena();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← RELAY SIEGE", PANEL_2, TEXT, v -> finish());
        nav.addView(back, new LinearLayout.LayoutParams(dp(132), dp(43)));
        TextView tag = pill("DECK LAB", GREEN);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dp(9);
        nav.addView(tag, tagParams);
        root.addView(nav);

        TextView title = text("BUILD WITH\nA REASON", 36, TEXT, true);
        title.setLineSpacing(-dp(3), 0.95f);
        title.setPadding(0, dp(20), 0, dp(7));
        root.addView(title);
        TextView intro = text(
                "8장뿐 아니라 순서도 덱의 일부입니다. 1~4번은 시작 손패, 5번은 첫 NEXT 카드입니다. 슬롯을 고른 뒤 카드 도감에서 교체하세요.",
                13.5f, MUTED, false);
        root.addView(intro);

        statusView = text("편집할 슬롯을 선택하세요.", 12, CYAN, true);
        statusView.setBackground(rounded(PANEL_2, dp(11)));
        statusView.setPadding(dp(11), dp(8), dp(11), dp(8));
        root.addView(statusView, matchWrap(dp(12)));

        addSectionLabel(root, "ORDERED DECK");
        for (int i = 0; i < deckIds.size(); i++) root.addView(slotRow(i));

        addSectionLabel(root, "QUICK PRESETS");
        LinearLayout presets = row();
        presets.addView(presetButton("Counterforge", "counterforge"), weighted());
        presets.addView(presetButton("Spark", "spark_cycle"), weightedMargin(dp(6)));
        presets.addView(presetButton("Split", "split_voltage"), weightedMargin(dp(6)));
        root.addView(presets);

        addSectionLabel(root, "CARD CATALOG");
        for (RelaySiegeCards.Card card : RelaySiegeCards.allCards()) root.addView(catalogCard(card));

        addSectionLabel(root, "STRUCTURE ANALYSIS");
        RelaySiegeDeckLab.Analysis analysis = RelaySiegeDeckLab.analyze(deckIds);
        root.addView(metricStrip(analysis));
        root.addView(analysisPanel("강점", analysis.strengths, GREEN));
        root.addView(analysisPanel("구조적 빈틈", analysis.warnings, analysis.warnings.isEmpty() ? GREEN : RED));

        addSectionLabel(root, "MATCHUP LAB · REAL ENGINE");
        TextView probeInfo = text(
                "현재 덱과 3개 스타터 덱을 실제 RelaySiegeGame + RelaySiegeAi로 90초씩 돌립니다. 결과는 정적 점수가 아니라 전투 시뮬레이션입니다.",
                12.5f, MUTED, false);
        root.addView(probeInfo);
        Button runProbes = button("3개 덱 매치업 probe 실행", PANEL_2, TEXT, v -> runProbes());
        root.addView(runProbes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        probeView = text("아직 실행하지 않음", 11.5f, MUTED, false);
        probeView.setBackground(rounded(PANEL, dp(12)));
        probeView.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(probeView, matchWrap(dp(7)));

        addSectionLabel(root, "PLAYTEST");
        TextView opponentLabel = text("상대 AI 덱", 11.5f, MUTED, true);
        root.addView(opponentLabel);
        LinearLayout opponents = row();
        opponents.addView(opponentButton("Counter", "counterforge"), weighted());
        opponents.addView(opponentButton("Spark", "spark_cycle"), weightedMargin(dp(6)));
        opponents.addView(opponentButton("Split", "split_voltage"), weightedMargin(dp(6)));
        root.addView(opponents, matchWrap(dp(5)));
        Button play = button("이 덱 그대로 AI전 시작", GREEN, BG, v -> showCustomMatch());
        play.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        setContentView(scroll);
    }

    private View slotRow(int index) {
        RelaySiegeCards.Card card = RelaySiegeCards.card(deckIds.get(index));
        LinearLayout panel = row();
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = rounded(index == editingSlot ? withAlpha(GREEN, 28) : PANEL, dp(13));
        bg.setStroke(dp(1), index == editingSlot ? withAlpha(GREEN, 150) : withAlpha(TEXT, 24));
        panel.setBackground(bg);
        panel.setPadding(dp(10), dp(8), dp(8), dp(8));
        panel.setOnClickListener(v -> {
            editingSlot = index;
            showLab();
        });

        String prefix = index < 4 ? "HAND " + (index + 1) : index == 4 ? "NEXT 5" : "CYCLE " + (index + 1);
        TextView title = text(prefix + "\n" + card.name + " · " + card.fluxCost + "F", 12, TEXT, true);
        panel.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button up = button("↑", PANEL_2, TEXT, v -> moveSlot(index, -1));
        Button down = button("↓", PANEL_2, TEXT, v -> moveSlot(index, 1));
        panel.addView(up, new LinearLayout.LayoutParams(dp(39), dp(38)));
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(39), dp(38));
        downParams.leftMargin = dp(5);
        panel.addView(down, downParams);
        return withTopMargin(panel, dp(6));
    }

    private View catalogCard(RelaySiegeCards.Card card) {
        boolean selected = deckIds.contains(card.id);
        LinearLayout panel = column();
        GradientDrawable bg = rounded(selected ? withAlpha(CYAN, 20) : PANEL, dp(13));
        bg.setStroke(dp(1), selected ? withAlpha(CYAN, 100) : withAlpha(TEXT, 20));
        panel.setBackground(bg);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setOnClickListener(v -> replaceSlot(card.id));

        LinearLayout header = row();
        TextView name = text(card.name, 15, TEXT, true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(card.fluxCost + " FLUX", SUN));
        panel.addView(header);
        TextView meta = text(card.primaryRole + " · " + card.kind + (card.airborne ? " · AIR" : ""), 10.5f, CYAN, true);
        meta.setPadding(0, dp(4), 0, dp(3));
        panel.addView(meta);
        panel.addView(text(card.shortDescription, 12, MUTED, false));
        if (selected) {
            TextView used = text("현재 덱에 포함 · 해당 슬롯을 먼저 선택하면 순서를 바꿀 수 있음", 10.5f, GREEN, true);
            used.setPadding(0, dp(5), 0, 0);
            panel.addView(used);
        }
        return withTopMargin(panel, dp(6));
    }

    private View metricStrip(RelaySiegeDeckLab.Analysis analysis) {
        LinearLayout strip = row();
        strip.addView(metric("AVG", String.format(Locale.US, "%.1f", analysis.averageFlux), SUN), weighted());
        strip.addView(metric("4-CARD", Integer.toString(analysis.fourCardCycleCost), CYAN), weightedMargin(dp(6)));
        strip.addView(metric("PRESS", analysis.pressureScore + "/5", GREEN), weightedMargin(dp(6)));
        strip.addView(metric("DEF", analysis.defenseScore + "/5", MOON), weightedMargin(dp(6)));
        return strip;
    }

    private View analysisPanel(String label, List<String> values, int accent) {
        LinearLayout panel = column();
        panel.setBackground(rounded(PANEL, dp(13)));
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.addView(text(label, 11.5f, accent, true));
        if (values.isEmpty()) {
            TextView empty = text("뚜렷한 구조적 경고 없음", 12, MUTED, false);
            empty.setPadding(0, dp(4), 0, 0);
            panel.addView(empty);
        } else {
            for (String value : values) {
                TextView line = text("• " + value, 12, TEXT, false);
                line.setPadding(0, dp(4), 0, 0);
                panel.addView(line);
            }
        }
        return withTopMargin(panel, dp(6));
    }

    private Button presetButton(String label, String deckId) {
        return button(label, PANEL_2, TEXT, v -> {
            deckIds.clear();
            deckIds.addAll(RelaySiegeCards.deck(deckId).cardIds);
            editingSlot = 0;
            saveDeck();
            showLab();
        });
    }

    private Button opponentButton(String label, String deckId) {
        int bg = deckId.equals(opponentDeckId) ? MOON : PANEL_2;
        return button(label, bg, deckId.equals(opponentDeckId) ? BG : TEXT, v -> {
            opponentDeckId = deckId;
            showLab();
        });
    }

    private void replaceSlot(String cardId) {
        int existing = deckIds.indexOf(cardId);
        if (existing == editingSlot) {
            statusView.setText("이미 이 슬롯에 있는 카드입니다.");
            return;
        }
        if (existing >= 0) {
            statusView.setText("중복 카드는 허용하지 않습니다. 현재 " + (existing + 1) + "번 슬롯에 있습니다.");
            statusView.setTextColor(RED);
            return;
        }
        deckIds.set(editingSlot, cardId);
        saveDeck();
        editingSlot = Math.min(RelaySiegeDeckLab.DECK_SIZE - 1, editingSlot + 1);
        showLab();
    }

    private void moveSlot(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= deckIds.size()) return;
        String tmp = deckIds.get(index);
        deckIds.set(index, deckIds.get(target));
        deckIds.set(target, tmp);
        editingSlot = target;
        saveDeck();
        showLab();
    }

    private void runProbes() {
        final ArrayList<String> snapshot = new ArrayList<>(deckIds);
        probeView.setText("실제 엔진으로 3개 matchup 계산 중…");
        probeView.setTextColor(CYAN);
        probeExecutor.execute(() -> {
            String result;
            try {
                List<RelaySiegeDeckLab.MatchupProbe> probes = RelaySiegeDeckLab.runStarterProbes(snapshot, 20260910L);
                StringBuilder out = new StringBuilder();
                for (RelaySiegeDeckLab.MatchupProbe probe : probes) {
                    if (out.length() > 0) out.append('\n');
                    out.append(probe.opponentDeckName).append("  →  ").append(probe.detail());
                }
                result = out.toString();
            } catch (Throwable t) {
                result = "Probe 실패: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || probeView == null) return;
                probeView.setText(finalResult);
                probeView.setTextColor(TEXT);
            });
        });
    }

    private void showCustomMatch() {
        clearArena();
        RelaySiegeCards.Deck custom = RelaySiegeDeckLab.buildDeck("My Deck", deckIds);
        RelaySiegeCards.Deck opponent = RelaySiegeCards.deck(opponentDeckId);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(9), dp(10), dp(10));

        LinearLayout nav = row();
        Button back = button("← DECK LAB", PANEL_2, TEXT, v -> showLab());
        nav.addView(back, new LinearLayout.LayoutParams(dp(106), dp(41)));
        matchTopView = text("", 13, TEXT, true);
        matchTopView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(0, dp(41), 1f);
        topParams.leftMargin = dp(8);
        nav.addView(matchTopView, topParams);
        root.addView(nav);

        matchObjectiveView = text("", 11, MUTED, false);
        matchObjectiveView.setGravity(Gravity.CENTER);
        matchObjectiveView.setPadding(dp(4), dp(6), dp(4), dp(5));
        root.addView(matchObjectiveView);

        FrameLayout arenaFrame = new FrameLayout(this);
        arenaFrame.setBackground(rounded(PANEL, dp(16)));
        arenaView = new RelaySiegeArenaView(this);
        arenaFrame.addView(arenaView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(arenaFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        matchMessageView = text("카드를 고르고 아레나를 탭해 배치하세요.", 11.5f, TEXT, true);
        matchMessageView.setGravity(Gravity.CENTER);
        matchMessageView.setBackground(rounded(PANEL_2, dp(11)));
        matchMessageView.setPadding(dp(8), dp(7), dp(8), dp(7));
        root.addView(matchMessageView, matchWrap(dp(6)));

        LinearLayout hand = row();
        for (int i = 0; i < handButtons.length; i++) {
            final int index = i;
            Button button = button("", PANEL, TEXT, v -> selectHandIndex(index));
            button.setTextSize(10.5f);
            button.setPadding(dp(4), 0, dp(4), 0);
            handButtons[i] = button;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(65), 1f);
            if (i > 0) p.leftMargin = dp(5);
            hand.addView(button, p);
        }
        root.addView(hand);

        LinearLayout lower = row();
        selectedCardView = text("선택 없음", 11, MUTED, true);
        lower.addView(selectedCardView, new LinearLayout.LayoutParams(0, dp(36), 1f));
        nextCardView = text("NEXT —", 11, CYAN, true);
        nextCardView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        lower.addView(nextCardView, new LinearLayout.LayoutParams(0, dp(36), 1f));
        root.addView(lower);

        rematchButton = button("같은 덱으로 다시", GREEN, BG, v -> startCustomMatch(custom, opponent));
        rematchButton.setVisibility(View.GONE);
        root.addView(rematchButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));

        setContentView(root);
        startCustomMatch(custom, opponent);
    }

    private void startCustomMatch(RelaySiegeCards.Deck custom, RelaySiegeCards.Deck opponent) {
        lastHandSignature = "";
        rematchButton.setVisibility(View.GONE);
        matchMessageView.setText("카드를 고르고 아레나를 탭해 배치하세요.");
        matchMessageView.setTextColor(TEXT);
        arenaView.setListener(new RelaySiegeArenaView.Listener() {
            @Override public void onStateChanged() { refreshMatchUi(custom, opponent); }
            @Override public void onMessage(String message) { matchMessageView.setText(message); }
            @Override public void onFinished(RelaySiegeGame.Player winner, RelaySiegeGame.EndReason reason) {
                matchMessageView.setText(winner == RelaySiegeGame.Player.SUN ? "승리 · " + reason
                        : winner == RelaySiegeGame.Player.MOON ? "패배 · " + reason : "무승부 · " + reason);
                matchMessageView.setTextColor(winner == RelaySiegeGame.Player.SUN ? GREEN
                        : winner == RelaySiegeGame.Player.MOON ? RED : MUTED);
                rematchButton.setVisibility(View.VISIBLE);
                refreshMatchUi(custom, opponent);
            }
        });
        long seed = System.nanoTime() ^ (++matchCounter * 0x9E3779B97F4A7C15L);
        arenaView.newMatch(custom, opponent, seed);
        refreshMatchUi(custom, opponent);
    }

    private void selectHandIndex(int index) {
        if (arenaView == null || arenaView.getGame() == null) return;
        List<String> hand = arenaView.getGame().getHand(RelaySiegeGame.Player.SUN);
        if (index < 0 || index >= hand.size()) return;
        arenaView.selectCard(hand.get(index));
        refreshMatchUi(RelaySiegeDeckLab.buildDeck("My Deck", deckIds), RelaySiegeCards.deck(opponentDeckId));
    }

    private void refreshMatchUi(RelaySiegeCards.Deck custom, RelaySiegeCards.Deck opponent) {
        if (arenaView == null || arenaView.getGame() == null || matchTopView == null) return;
        RelaySiegeGame game = arenaView.getGame();
        int seconds = game.getTick() / RelaySiegeGame.TICKS_PER_SECOND;
        matchTopView.setText(String.format(Locale.US, "MY DECK vs %s · %d:%02d · %.1fF",
                opponent.name, seconds / 60, seconds % 60,
                game.getFluxMilli(RelaySiegeGame.Player.SUN) / 1000f));
        matchObjectiveView.setText(String.format(Locale.US,
                "SUN L%d R%d C%d  ·  MOON L%d R%d C%d",
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT),
                game.getCoreHp(RelaySiegeGame.Player.SUN),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT),
                game.getCoreHp(RelaySiegeGame.Player.MOON)));

        List<String> hand = game.getHand(RelaySiegeGame.Player.SUN);
        String signature = hand + ":" + arenaView.getSelectedCardId();
        if (!signature.equals(lastHandSignature)) {
            lastHandSignature = signature;
            for (int i = 0; i < handButtons.length; i++) {
                RelaySiegeCards.Card card = RelaySiegeCards.card(hand.get(i));
                boolean selected = card.id.equals(arenaView.getSelectedCardId());
                handButtons[i].setText(card.fluxCost + "  " + card.name.replace(" ", "\n"));
                handButtons[i].setBackground(rounded(selected ? GREEN : PANEL, dp(11)));
                handButtons[i].setTextColor(selected ? BG : TEXT);
            }
        }
        int flux = game.getFluxMilli(RelaySiegeGame.Player.SUN);
        for (int i = 0; i < handButtons.length; i++) {
            RelaySiegeCards.Card card = RelaySiegeCards.card(hand.get(i));
            handButtons[i].setAlpha(flux >= card.fluxCost * 1_000 ? 1f : 0.43f);
            handButtons[i].setEnabled(!game.isFinished());
        }
        String selected = arenaView.getSelectedCardId();
        selectedCardView.setText(selected == null ? "선택 없음" : "SELECTED  " + RelaySiegeCards.card(selected).name);
        nextCardView.setText("NEXT  " + RelaySiegeCards.card(game.getNextCard(RelaySiegeGame.Player.SUN)).name);
    }

    private void addSectionLabel(LinearLayout root, String label) {
        TextView view = text(label, 11.5f, MUTED, true);
        view.setLetterSpacing(0.11f);
        view.setPadding(dp(2), dp(20), 0, dp(7));
        root.addView(view);
    }

    private TextView metric(String label, String value, int accent) {
        TextView view = text(label + "\n" + value, 10.5f, TEXT, true);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(PANEL_2, dp(10));
        bg.setStroke(dp(1), withAlpha(accent, 55));
        view.setBackground(bg);
        view.setPadding(dp(4), dp(7), dp(4), dp(7));
        return view;
    }

    private TextView pill(String value, int accent) {
        TextView view = text(value, 9.5f, accent, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(withAlpha(accent, 26), dp(9)));
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        return view;
    }

    private Button button(String value, int background, int foreground, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(foreground);
        button.setBackground(rounded(background, dp(11)));
        button.setPadding(dp(8), 0, dp(8), 0);
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

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedMargin(int margin) {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private View withTopMargin(View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        view.setLayoutParams(params);
        return view;
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
