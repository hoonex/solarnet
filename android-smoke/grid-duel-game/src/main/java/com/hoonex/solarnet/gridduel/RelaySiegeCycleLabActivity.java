package com.hoonex.solarnet.gridduel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player-facing exact hand / next-card cycle laboratory for Relay Siege. */
public final class RelaySiegeCycleLabActivity extends Activity {
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

    private final ArrayList<String> deckIds = new ArrayList<>();
    private String sourceId = "custom";
    private String sourceName = "MY DECK";
    private boolean created;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        loadCustomDeck();
        created = true;
        showLab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (created && "custom".equals(sourceId)) {
            List<String> before = new ArrayList<>(deckIds);
            loadCustomDeck();
            if (!before.equals(deckIds)) showLab();
        }
    }

    private void loadCustomDeck() {
        deckIds.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String encoded = prefs.getString(KEY_DECK, null);
        if (encoded != null) {
            try {
                deckIds.addAll(RelaySiegeDeckLab.decode(encoded));
            } catch (RuntimeException ignored) { }
        }
        if (deckIds.size() != RelaySiegeDeckLab.DECK_SIZE)
            deckIds.addAll(RelaySiegeCards.deck("counterforge").cardIds);
    }

    private void selectStarter(String deckId) {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck(deckId);
        sourceId = deckId;
        sourceName = deck.name.toUpperCase(Locale.US);
        deckIds.clear();
        deckIds.addAll(deck.cardIds);
        showLab();
    }

    private void selectCustom() {
        sourceId = "custom";
        sourceName = "MY DECK";
        loadCustomDeck();
        showLab();
    }

    private void showLab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(38));
        scroll.addView(root);

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← SOLAR ARCADE", PANEL_2, TEXT, v -> finish());
        nav.addView(back, new LinearLayout.LayoutParams(dp(139), dp(43)));
        TextView tag = pill("CYCLE LAB", CYAN);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dp(9);
        nav.addView(tag, tagParams);
        root.addView(nav);

        TextView title = text("KNOW WHEN\nIT COMES BACK", 34, TEXT, true);
        title.setLineSpacing(-dp(3), 0.94f);
        title.setPadding(0, dp(20), 0, dp(7));
        root.addView(title);
        root.addView(text(
                "핵심 카드를 지금 쓰면 몇 장을 더 돌려야 다시 잡히는지, 아직 손에 없다면 몇 번 플레이 뒤 들어오는지 실제 RelaySiegeGame 손패로 재생합니다.",
                13.5f, MUTED, false));

        TextView evidence = text(
                "PLAY COUNT = 실제 HAND/NEXT 순환 · FLUX/TIME = cheapest-first 경로를 실제 Flux 재생까지 돌린 값 · 전역 최적해라고 주장하지 않음",
                10.8f, CYAN, true);
        evidence.setBackground(rounded(withAlpha(CYAN, 20), dp(11)));
        evidence.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(evidence, matchWrap(dp(12)));

        addSectionLabel(root, "DECK SOURCE · " + sourceName);
        LinearLayout sources1 = row();
        sources1.addView(sourceButton("MY DECK", "custom"), weighted());
        sources1.addView(sourceButton("Counter", "counterforge"), weightedMargin(dp(6)));
        root.addView(sources1);
        LinearLayout sources2 = row();
        sources2.addView(sourceButton("Spark", "spark_cycle"), weighted());
        sources2.addView(sourceButton("Split", "split_voltage"), weightedMargin(dp(6)));
        root.addView(sources2, matchWrap(dp(6)));

        Button edit = button("덱 연구소에서 순서 바꾸기", PANEL_2, TEXT,
                v -> startActivity(new Intent(this, RelaySiegeDeckLabActivity.class)));
        root.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));

        addSectionLabel(root, "EXACT ORDER");
        root.addView(orderPanel());

        List<RelaySiegeCycleLab.Route> routes = RelaySiegeCycleLab.analyze(deckIds);
        addSectionLabel(root, "OPENING HAND · SPEND → RETURN");
        root.addView(text(
                "1~4번 카드는 시작 손패입니다. 지금 그 카드를 사용한 직후부터 다시 손에 돌아올 때까지를 측정합니다.",
                11.5f, MUTED, false));
        for (int i = 0; i < 4; i++) root.addView(routeCard(i, routes.get(i)));

        addSectionLabel(root, "FUTURE SLOTS · DRAW-IN");
        root.addView(text(
                "5~8번 카드는 시작 손패 밖입니다. 현재 NEXT부터 cheapest-first로 순환했을 때 처음 손에 들어오는 시점을 측정합니다.",
                11.5f, MUTED, false));
        for (int i = 4; i < routes.size(); i++) root.addView(routeCard(i, routes.get(i)));

        TextView lesson = text(
                "핵심 포인트: 같은 8장을 써도 슬롯이 다르면 재순환 창이 달라집니다. 시작 손패 1번 카드는 사용 후 4번의 추가 플레이로 돌아오지만 4번 카드는 7번이 필요합니다. 따라서 '무슨 카드가 있느냐'뿐 아니라 '어디에 놓였느냐'도 실제 전략 자원입니다.",
                12.5f, TEXT, false);
        lesson.setBackground(rounded(withAlpha(GREEN, 18), dp(13)));
        lesson.setPadding(dp(13), dp(12), dp(13), dp(12));
        root.addView(lesson, matchWrap(dp(18)));

        setContentView(scroll);
    }

    private View orderPanel() {
        LinearLayout panel = column();
        panel.setBackground(rounded(PANEL, dp(14)));
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        for (int i = 0; i < deckIds.size(); i++) {
            RelaySiegeCards.Card card = RelaySiegeCards.card(deckIds.get(i));
            String slot = i < 4 ? "HAND " + (i + 1) : i == 4 ? "NEXT 5" : "CYCLE " + (i + 1);
            TextView line = text(
                    slot + "   " + card.name + " · " + card.fluxCost + "F",
                    12.2f,
                    i < 4 ? SUN : CYAN,
                    i == 4);
            if (i > 0) line.setPadding(0, dp(5), 0, 0);
            panel.addView(line);
        }
        return panel;
    }

    private View routeCard(int slotIndex, RelaySiegeCycleLab.Route route) {
        RelaySiegeCards.Card target = RelaySiegeCards.card(route.targetCardId);
        boolean opening = route.mode == RelaySiegeCycleLab.Mode.RECOVER_AFTER_SPEND;
        int accent = opening ? SUN : CYAN;

        LinearLayout panel = column();
        GradientDrawable bg = rounded(PANEL, dp(14));
        bg.setStroke(dp(1), withAlpha(accent, 72));
        panel.setBackground(bg);
        panel.setPadding(dp(13), dp(11), dp(13), dp(11));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        String slot = opening ? "HAND " + (slotIndex + 1) : slotIndex == 4 ? "NEXT 5" : "CYCLE " + (slotIndex + 1);
        header.addView(pill(slot, accent));
        TextView name = text(target.name + " · " + target.fluxCost + "F", 15, TEXT, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(9);
        header.addView(name, nameParams);
        panel.addView(header);

        String headline;
        if (opening) {
            headline = "사용 후 복귀  +" + route.cycleCardsAfterSpend + " plays  ·  +"
                    + flux(route.extraCycleFluxMilli) + "F  ·  "
                    + seconds(route.ticksAfterSpend) + "s";
        } else {
            headline = "첩 손패 진입  " + route.cardsToReady + " plays  ·  "
                    + flux(route.fluxToReadyMilli) + "F  ·  "
                    + seconds(route.ticksToReady) + "s";
        }
        TextView result = text(headline, 13, GREEN, true);
        result.setPadding(0, dp(9), 0, dp(5));
        panel.addView(result);

        String sequence = route.sequenceLabel();
        if (opening) {
            int split = sequence.indexOf(" → ");
            if (split >= 0) sequence = sequence.substring(split + 3);
            else sequence = "(추가 플레이 없음)";
        }
        panel.addView(text("경로  " + sequence, 11.7f, MUTED, false));
        panel.addView(text(
                "READY HAND  " + handNames(route.finalHand) + "  ·  NEXT "
                        + RelaySiegeCards.card(route.finalNextCard).name,
                10.8f, withAlpha(TEXT, 180), false));

        return withTopMargin(panel, dp(7));
    }

    private Button sourceButton(String label, String id) {
        boolean selected = sourceId.equals(id);
        return button(label, selected ? GREEN : PANEL_2, selected ? BG : TEXT, v -> {
            if ("custom".equals(id)) selectCustom();
            else selectStarter(id);
        });
    }

    private String handNames(List<String> hand) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) out.append(" / ");
            out.append(RelaySiegeCards.card(hand.get(i)).name);
        }
        return out.toString();
    }

    private String flux(int milli) {
        return String.format(Locale.US, "%.1f", milli / 1000f);
    }

    private String seconds(int ticks) {
        return String.format(Locale.US, "%.1f", ticks / 10f);
    }

    private void addSectionLabel(LinearLayout root, String value) {
        TextView label = text(value, 11, MUTED, true);
        label.setLetterSpacing(0.11f);
        label.setPadding(dp(2), dp(21), 0, dp(7));
        root.addView(label);
    }

    private Button button(String label, int background, int foreground, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11.5f);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, dp(11)));
        button.setOnClickListener(click);
        return button;
    }

    private TextView pill(String value, int accent) {
        TextView view = text(value, 10, accent, true);
        view.setLetterSpacing(0.07f);
        view.setBackground(rounded(withAlpha(accent, 24), dp(9)));
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        return view;
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

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(43), 1f);
    }

    private LinearLayout.LayoutParams weightedMargin(int left) {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = left;
        return params;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private View withTopMargin(View view, int margin) {
        view.setLayoutParams(matchWrap(margin));
        return view;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
