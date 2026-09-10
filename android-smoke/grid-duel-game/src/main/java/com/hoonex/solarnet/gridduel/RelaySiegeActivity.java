package com.hoonex.solarnet.gridduel;

import android.app.Activity;
import android.content.Intent;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay Siege front-end.
 *
 * Deck guides, embedded replays and the playable AI arena all consume the same RelaySiegeGame rules.
 * No guide-only combat implementation exists here.
 */
public final class RelaySiegeActivity extends Activity {
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

    private final AtomicLong matchCounter = new AtomicLong();

    private RelaySiegeArenaView arenaView;
    private RelaySiegeReplayView replayView;
    private RelaySiegeDeckGuide.Guide currentGuide;
    private int demoIndex;

    private TextView demoTitleView;
    private TextView demoLessonView;
    private TextView demoWatchView;
    private TextView demoActionsView;

    private String matchDeckId;
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
        showDeckSelect();
    }

    @Override
    protected void onPause() {
        if (arenaView != null) arenaView.pause();
        if (replayView != null) replayView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arenaView != null) arenaView.resume();
        if (replayView != null) replayView.resume();
    }

    private void clearActiveSurface() {
        if (arenaView != null) arenaView.pause();
        if (replayView != null) replayView.pause();
        arenaView = null;
        replayView = null;
        currentGuide = null;
        demoIndex = 0;
        lastHandSignature = "";
    }

    private void showDeckSelect() {
        clearActiveSurface();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(21), dp(28), dp(21), dp(32));
        scroll.addView(root);

        Button back = compactButton("← SOLAR ARCADE", PANEL_2, TEXT, v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(142), dp(44)));

        TextView eyebrow = text("REAL-TIME DECK STRATEGY", 12, GREEN, true);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setPadding(0, dp(27), 0, dp(7));
        root.addView(eyebrow);

        TextView title = text("RELAY\nSIEGE", 45, TEXT, true);
        title.setLineSpacing(-dp(5), 0.92f);
        root.addView(title);

        TextView description = text(
                "2개 라인, 8장 덱, 4장 손패. Flux를 모으고 배치 위치·어그로·상성·덱 순환으로 상대 Relay와 Core를 무너뜨리세요.",
                15, MUTED, false);
        description.setPadding(0, dp(12), 0, dp(20));
        root.addView(description);

        TextView system = text(
                "GROUND / AIR  ·  BUILDING PULL  ·  4-CARD ROTATION  ·  DEFENSE → COUNTERPUSH",
                10.5f, CYAN, true);
        system.setLetterSpacing(0.035f);
        system.setBackground(rounded(PANEL, dp(12)));
        system.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(system, matchWrap(dp(10)));

        LinearLayout learning = row();
        Button trainerButton = compactButton("전술 트레이너", GREEN, BG,
                v -> startActivity(new Intent(this, RelaySiegeTacticalTrainerActivity.class)));
        Button labButton = compactButton("덱 연구소", PANEL_2, TEXT,
                v -> startActivity(new Intent(this, RelaySiegeDeckLabActivity.class)));
        learning.addView(trainerButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams labParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        labParams.leftMargin = dp(8);
        learning.addView(labButton, labParams);
        root.addView(learning, matchWrap(dp(10)));

        TextView learningNote = text(
                "전술 트레이너는 같은 전장을 선택지만 바꿔 실제 엔진으로 비교하고, 덱 연구소는 8장 순서·순환·상성 커버리지를 분석합니다.",
                11.5f, MUTED, false);
        learningNote.setPadding(dp(3), dp(7), dp(3), dp(4));
        root.addView(learningNote);

        TextView choose = text("STARTER DECKS", 12, MUTED, true);
        choose.setLetterSpacing(0.12f);
        choose.setPadding(dp(2), dp(16), 0, dp(6));
        root.addView(choose);

        for (RelaySiegeDeckGuide.Guide guide : RelaySiegeDeckGuide.guides())
            root.addView(deckCard(guide));

        TextView note = text(
                "덱 가이드의 전투 예시는 별도 애니메이션이 아니라 실제 RelaySiegeGame 시뮬레이션 프레임입니다. 카드/어그로 규칙이 바뀌면 예시도 같은 규칙으로 바뀝니다.",
                12, MUTED, false);
        note.setPadding(dp(3), dp(20), dp(3), 0);
        root.addView(note);

        setContentView(scroll);
    }

    private View deckCard(RelaySiegeDeckGuide.Guide guide) {
        RelaySiegeCards.Deck deck = guide.deck();
        LinearLayout card = column();
        GradientDrawable bg = rounded(PANEL, dp(18));
        bg.setStroke(dp(1), withAlpha(deckAccent(deck.id), 70));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout header = row();
        TextView name = text(deck.name, 20, TEXT, true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView archetype = pill(deck.archetype.toUpperCase(Locale.US), deckAccent(deck.id));
        header.addView(archetype);
        card.addView(header);

        TextView plan = text(deck.gamePlan, 13.5f, MUTED, false);
        plan.setPadding(0, dp(9), 0, dp(10));
        card.addView(plan);

        LinearLayout metrics = row();
        metrics.addView(metric("AVG", String.format(Locale.US, "%.1f", deck.averageFlux()), SUN), weighted());
        metrics.addView(metric("4-CARD", Integer.toString(deck.fourCardCycleCost()), CYAN), weightedMargin(dp(7)));
        metrics.addView(metric("DEF", guide.defense + "/5", GREEN), weightedMargin(dp(7)));
        metrics.addView(metric("CYCLE", guide.cycle + "/5", MOON), weightedMargin(dp(7)));
        card.addView(metrics);

        LinearLayout actions = row();
        Button guideButton = compactButton("덱 가이드", PANEL_2, TEXT, v -> showGuide(guide));
        Button playButton = compactButton("AI 연습전", deckAccent(deck.id), BG, v -> showMatch(deck.id));
        actions.addView(guideButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        playParams.leftMargin = dp(8);
        actions.addView(playButton, playParams);
        LinearLayout.LayoutParams actionsParams = matchWrap(dp(13));
        card.addView(actions, actionsParams);

        LinearLayout.LayoutParams cardParams = matchWrap(dp(9));
        card.setLayoutParams(cardParams);
        return card;
    }

    private void showGuide(RelaySiegeDeckGuide.Guide guide) {
        clearActiveSurface();
        currentGuide = guide;
        demoIndex = 0;
        RelaySiegeCards.Deck deck = guide.deck();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(34));
        scroll.addView(root);

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton("← 덱 선택", PANEL_2, TEXT, v -> showDeckSelect());
        nav.addView(back, new LinearLayout.LayoutParams(dp(104), dp(43)));
        TextView tag = pill("DECK GUIDE", deckAccent(deck.id));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dp(10);
        nav.addView(tag, tagParams);
        root.addView(nav);

        TextView title = text(deck.name, 34, TEXT, true);
        title.setPadding(0, dp(22), 0, dp(3));
        root.addView(title);
        TextView archetype = text(deck.archetype, 14, deckAccent(deck.id), true);
        root.addView(archetype);

        LinearLayout metrics = row();
        metrics.setPadding(0, dp(13), 0, dp(8));
        metrics.addView(metric("AVG FLUX", String.format(Locale.US, "%.1f", guide.averageFlux()), SUN), weighted());
        metrics.addView(metric("4-CARD", Integer.toString(guide.fourCardCycleCost()), CYAN), weightedMargin(dp(7)));
        metrics.addView(metric("PRESS", guide.pressure + "/5", RED), weightedMargin(dp(7)));
        metrics.addView(metric("COMPLEX", guide.complexity + "/5", MOON), weightedMargin(dp(7)));
        root.addView(metrics);

        addGuideSection(root, "승리 플랜", guide.winCondition, GREEN);
        addGuideSection(root, "첫 수", guide.opening, CYAN);
        addGuideSection(root, "방어", guide.defensePlan, SUN);
        addGuideSection(root, "방어 → 역공", guide.conversionPlan, GREEN);
        addGuideSection(root, "흔한 실수", guide.commonMistake, RED);

        TextView replayLabel = text("LIVE COMBAT EXAMPLE", 12, MUTED, true);
        replayLabel.setLetterSpacing(0.11f);
        replayLabel.setPadding(dp(2), dp(22), 0, dp(8));
        root.addView(replayLabel);

        LinearLayout demoPanel = column();
        demoPanel.setBackground(rounded(PANEL, dp(17)));
        demoPanel.setPadding(dp(13), dp(13), dp(13), dp(13));
        demoTitleView = text("", 18, TEXT, true);
        demoPanel.addView(demoTitleView);
        demoLessonView = text("", 13, MUTED, false);
        demoLessonView.setPadding(0, dp(5), 0, dp(4));
        demoPanel.addView(demoLessonView);
        demoWatchView = text("", 12, CYAN, true);
        demoPanel.addView(demoWatchView);

        replayView = new RelaySiegeReplayView(this);
        LinearLayout.LayoutParams replayParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(245));
        replayParams.topMargin = dp(11);
        demoPanel.addView(replayView, replayParams);

        demoActionsView = text("", 11.5f, MUTED, false);
        demoActionsView.setBackground(rounded(PANEL_2, dp(11)));
        demoActionsView.setPadding(dp(10), dp(9), dp(10), dp(9));
        demoPanel.addView(demoActionsView, matchWrap(dp(8)));

        LinearLayout demoNav = row();
        Button previous = compactButton("‹ 이전 예시", PANEL_2, TEXT, v -> changeDemo(-1));
        Button next = compactButton("다음 예시 ›", PANEL_2, TEXT, v -> changeDemo(1));
        demoNav.addView(previous, new LinearLayout.LayoutParams(0, dp(43), 1f));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, dp(43), 1f);
        nextParams.leftMargin = dp(7);
        demoNav.addView(next, nextParams);
        demoPanel.addView(demoNav, matchWrap(dp(9)));
        root.addView(demoPanel);

        TextView cardLabel = text("CARD-BY-CARD", 12, MUTED, true);
        cardLabel.setLetterSpacing(0.11f);
        cardLabel.setPadding(dp(2), dp(24), 0, dp(7));
        root.addView(cardLabel);
        for (RelaySiegeDeckGuide.CardNote note : guide.cardNotes)
            root.addView(cardGuideCard(note));

        Button practice = compactButton("이 덱으로 AI 연습전 시작", deckAccent(deck.id), BG,
                v -> showMatch(deck.id));
        root.addView(practice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        setContentView(scroll);
        updateDemo();
    }

    private View cardGuideCard(RelaySiegeDeckGuide.CardNote note) {
        RelaySiegeCards.Card card = RelaySiegeCards.card(note.cardId);
        LinearLayout panel = column();
        panel.setBackground(rounded(PANEL, dp(14)));
        panel.setPadding(dp(13), dp(12), dp(13), dp(12));

        LinearLayout header = row();
        TextView title = text(card.name, 16, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(card.fluxCost + " FLUX", SUN));
        panel.addView(header);

        TextView job = text(note.job, 12.5f, MUTED, false);
        job.setPadding(0, dp(6), 0, dp(6));
        panel.addView(job);
        panel.addView(text("GOOD  " + note.goodInto, 11.5f, GREEN, true));
        TextView bad = text("BAD    " + note.badInto, 11.5f, RED, true);
        bad.setPadding(0, dp(3), 0, 0);
        panel.addView(bad);
        TextView timing = text("TIMING  " + note.timing, 11.5f, CYAN, false);
        timing.setPadding(0, dp(3), 0, 0);
        panel.addView(timing);

        LinearLayout.LayoutParams params = matchWrap(dp(7));
        panel.setLayoutParams(params);
        return panel;
    }

    private void changeDemo(int delta) {
        if (currentGuide == null || currentGuide.demos.isEmpty()) return;
        demoIndex = (demoIndex + delta + currentGuide.demos.size()) % currentGuide.demos.size();
        updateDemo();
    }

    private void updateDemo() {
        if (currentGuide == null || currentGuide.demos.isEmpty() || replayView == null) return;
        RelaySiegeDeckGuide.DemoScenario demo = currentGuide.demos.get(demoIndex);
        RelaySiegeDeckGuide.DemoRun run = demo.run();
        demoTitleView.setText((demoIndex + 1) + "/" + currentGuide.demos.size() + "  " + demo.title);
        demoLessonView.setText(demo.lesson);
        demoWatchView.setText("WATCH  " + demo.watchFor);
        demoActionsView.setText(annotationSummary(run));
        replayView.setDemo(run);
    }

    private String annotationSummary(RelaySiegeDeckGuide.DemoRun run) {
        if (run.annotations.isEmpty()) return "실제 엔진이 전투를 자동 재생합니다.";
        StringBuilder out = new StringBuilder();
        int count = Math.min(4, run.annotations.size());
        for (int i = 0; i < count; i++) {
            RelaySiegeDeckGuide.DemoAnnotation annotation = run.annotations.get(i);
            if (i > 0) out.append('\n');
            out.append(String.format(Locale.US, "%4.1fs  ", annotation.tick / 10f)).append(annotation.text);
        }
        return out.toString();
    }

    private void showMatch(String deckId) {
        clearActiveSurface();
        matchDeckId = deckId;
        RelaySiegeCards.Deck playerDeck = RelaySiegeCards.deck(deckId);
        RelaySiegeCards.Deck aiDeck = RelaySiegeCards.deck(aiDeckFor(deckId));

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(9), dp(10), dp(10));

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton("← 덱 선택", PANEL_2, TEXT, v -> showDeckSelect());
        nav.addView(back, new LinearLayout.LayoutParams(dp(100), dp(41)));
        matchTopView = text("", 14, TEXT, true);
        matchTopView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(0, dp(41), 1f);
        topParams.leftMargin = dp(8);
        nav.addView(matchTopView, topParams);
        root.addView(nav);

        matchObjectiveView = text("", 11.5f, MUTED, false);
        matchObjectiveView.setGravity(Gravity.CENTER);
        matchObjectiveView.setPadding(dp(4), dp(7), dp(4), dp(6));
        root.addView(matchObjectiveView);

        FrameLayout arenaFrame = new FrameLayout(this);
        arenaFrame.setBackground(rounded(PANEL, dp(16)));
        arenaView = new RelaySiegeArenaView(this);
        arenaFrame.addView(arenaView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams arenaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(arenaFrame, arenaParams);

        matchMessageView = text("카드를 고르고 아레나를 탭해 배치하세요.", 11.5f, TEXT, true);
        matchMessageView.setGravity(Gravity.CENTER);
        matchMessageView.setBackground(rounded(PANEL_2, dp(11)));
        matchMessageView.setPadding(dp(9), dp(7), dp(9), dp(7));
        root.addView(matchMessageView, matchWrap(dp(6)));

        LinearLayout hand = row();
        for (int i = 0; i < handButtons.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setTextSize(10.5f);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(TEXT);
            button.setPadding(dp(4), 0, dp(4), 0);
            button.setOnClickListener(v -> selectHandIndex(index));
            handButtons[i] = button;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(65), 1f);
            if (i > 0) p.leftMargin = dp(5);
            hand.addView(button, p);
        }
        root.addView(hand);

        LinearLayout lower = row();
        lower.setGravity(Gravity.CENTER_VERTICAL);
        selectedCardView = text("선택 없음", 11, MUTED, true);
        lower.addView(selectedCardView, new LinearLayout.LayoutParams(0, dp(36), 1f));
        nextCardView = text("NEXT —", 11, CYAN, true);
        nextCardView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        lower.addView(nextCardView, new LinearLayout.LayoutParams(0, dp(36), 1f));
        root.addView(lower);

        rematchButton = compactButton("새 매치", deckAccent(deckId), BG, v -> startArenaMatch(playerDeck, aiDeck));
        rematchButton.setVisibility(View.GONE);
        root.addView(rematchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));

        setContentView(root);
        startArenaMatch(playerDeck, aiDeck);
    }

    private void startArenaMatch(RelaySiegeCards.Deck playerDeck, RelaySiegeCards.Deck aiDeck) {
        if (arenaView == null) return;
        rematchButton.setVisibility(View.GONE);
        matchMessageView.setText("카드를 고르고 아레나를 탭해 배치하세요.");
        lastHandSignature = "";
        arenaView.setListener(new RelaySiegeArenaView.Listener() {
            @Override public void onStateChanged() { refreshMatchUi(); }
            @Override public void onMessage(String message) { matchMessageView.setText(message); }
            @Override public void onFinished(RelaySiegeGame.Player winner, RelaySiegeGame.EndReason reason) {
                matchMessageView.setText(endMessage(winner, reason));
                matchMessageView.setTextColor(winner == RelaySiegeGame.Player.SUN ? GREEN
                        : winner == RelaySiegeGame.Player.MOON ? RED : MUTED);
                rematchButton.setVisibility(View.VISIBLE);
                refreshMatchUi();
            }
        });
        long seed = System.nanoTime() ^ (matchCounter.incrementAndGet() * 0x9E3779B97F4A7C15L);
        arenaView.newMatch(playerDeck, aiDeck, seed);
        refreshMatchUi();
    }

    private void selectHandIndex(int index) {
        if (arenaView == null || arenaView.getGame() == null) return;
        List<String> hand = arenaView.getGame().getHand(RelaySiegeGame.Player.SUN);
        if (index < 0 || index >= hand.size()) return;
        arenaView.selectCard(hand.get(index));
        refreshMatchUi();
    }

    private void refreshMatchUi() {
        if (arenaView == null || arenaView.getGame() == null || matchTopView == null) return;
        RelaySiegeGame game = arenaView.getGame();
        int seconds = game.getTick() / RelaySiegeGame.TICKS_PER_SECOND;
        matchTopView.setText(String.format(Locale.US, "%s  ·  %d:%02d  ·  %.1f Flux",
                RelaySiegeCards.deck(matchDeckId).name,
                seconds / 60,
                seconds % 60,
                game.getFluxMilli(RelaySiegeGame.Player.SUN) / 1000f));
        matchObjectiveView.setText(String.format(Locale.US,
                "SUN  L %d  R %d  CORE %d     ·     MOON  L %d  R %d  CORE %d",
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT),
                game.getCoreHp(RelaySiegeGame.Player.SUN),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT),
                game.getCoreHp(RelaySiegeGame.Player.MOON)));

        List<String> hand = game.getHand(RelaySiegeGame.Player.SUN);
        String signature = hand.toString() + ":" + arenaView.getSelectedCardId();
        if (!signature.equals(lastHandSignature)) {
            lastHandSignature = signature;
            for (int i = 0; i < handButtons.length; i++) {
                String cardId = hand.get(i);
                RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
                Button button = handButtons[i];
                button.setText(card.fluxCost + "  " + card.name.replace(" ", "\n"));
                boolean selected = cardId.equals(arenaView.getSelectedCardId());
                button.setBackground(rounded(selected ? deckAccent(matchDeckId) : PANEL, dp(11)));
                button.setTextColor(selected ? BG : TEXT);
            }
        }
        int flux = game.getFluxMilli(RelaySiegeGame.Player.SUN);
        for (int i = 0; i < handButtons.length; i++) {
            RelaySiegeCards.Card card = RelaySiegeCards.card(hand.get(i));
            handButtons[i].setAlpha(flux >= card.fluxCost * 1_000 ? 1f : 0.43f);
            handButtons[i].setEnabled(!game.isFinished());
        }

        String selected = arenaView.getSelectedCardId();
        selectedCardView.setText(selected == null ? "선택 없음"
                : "SELECTED  " + RelaySiegeCards.card(selected).name);
        nextCardView.setText("NEXT  " + RelaySiegeCards.card(game.getNextCard(RelaySiegeGame.Player.SUN)).name);
    }

    private String aiDeckFor(String playerDeck) {
        if ("counterforge".equals(playerDeck)) return "spark_cycle";
        if ("spark_cycle".equals(playerDeck)) return "split_voltage";
        return "counterforge";
    }

    private String endMessage(RelaySiegeGame.Player winner, RelaySiegeGame.EndReason reason) {
        if (winner == RelaySiegeGame.Player.SUN) return "승리 · " + reason;
        if (winner == RelaySiegeGame.Player.MOON) return "패배 · " + reason;
        return "무승부 · " + reason;
    }

    private void addGuideSection(LinearLayout root, String label, String body, int accent) {
        LinearLayout panel = column();
        panel.setBackground(rounded(PANEL, dp(14)));
        panel.setPadding(dp(13), dp(11), dp(13), dp(12));
        TextView heading = text(label, 12, accent, true);
        heading.setLetterSpacing(0.04f);
        panel.addView(heading);
        TextView content = text(body, 13, TEXT, false);
        content.setPadding(0, dp(5), 0, 0);
        panel.addView(content);
        root.addView(panel, matchWrap(dp(7)));
    }

    private TextView metric(String label, String value, int accent) {
        TextView view = text(label + "\n" + value, 10.5f, TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(PANEL_2, dp(11)));
        view.setPadding(dp(5), dp(7), dp(5), dp(7));
        view.setCompoundDrawablePadding(dp(3));
        return view;
    }

    private TextView pill(String value, int accent) {
        TextView view = text(value, 9.5f, accent, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(withAlpha(accent, 25), dp(9)));
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        return view;
    }

    private Button compactButton(String value, int background, int foreground, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(foreground);
        button.setBackground(rounded(background, dp(11)));
        button.setPadding(dp(10), 0, dp(10), 0);
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

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int deckAccent(String deckId) {
        if ("counterforge".equals(deckId)) return SUN;
        if ("spark_cycle".equals(deckId)) return GREEN;
        return MOON;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
