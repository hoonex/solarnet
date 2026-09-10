package com.hoonex.solarnet.gridduel;

import android.app.Activity;
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

import java.util.List;
import java.util.Locale;

/** Interactive, engine-backed tactical teaching surface for Relay Siege. */
public final class RelaySiegeTacticalTrainerActivity extends Activity {
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

    private RelaySiegeReplayView replayView;
    private String activeLessonId;
    private String selectedChoiceId;
    private TextView replayModeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showLessonList();
    }

    @Override
    protected void onPause() {
        if (replayView != null) replayView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (replayView != null) replayView.resume();
    }

    private void clearReplay() {
        if (replayView != null) replayView.pause();
        replayView = null;
        replayModeView = null;
    }

    private void showLessonList() {
        clearReplay();
        activeLessonId = null;
        selectedChoiceId = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(25), dp(20), dp(34));
        scroll.addView(root);

        Button back = compactButton("← RELAY SIEGE", PANEL_2, TEXT, v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(135), dp(44)));

        TextView eyebrow = text("ENGINE-BACKED LEARNING", 11.5f, CYAN, true);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setPadding(0, dp(25), 0, dp(7));
        root.addView(eyebrow);

        TextView title = text("TACTICAL\nTRAINER", 41, TEXT, true);
        title.setLineSpacing(-dp(4), 0.93f);
        root.addView(title);

        TextView subtitle = text(
                "같은 전장에서 선택지만 바꿔 실제 전투 엔진을 다시 돌립니다. 외워서 맞히는 문제가 아니라, Relay 피해·생존 전력·남은 위협·Flux 교환으로 왜 좋은 수인지 확인하세요.",
                14.5f, MUTED, false);
        subtitle.setPadding(0, dp(12), 0, dp(18));
        root.addView(subtitle);

        TextView rule = text("SAME STATE  ·  SAME SEED  ·  DIFFERENT DECISION", 10.5f, GREEN, true);
        rule.setLetterSpacing(0.055f);
        rule.setBackground(rounded(PANEL, dp(12)));
        rule.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(rule, matchWrap(dp(2)));

        List<RelaySiegeTacticalTrainer.Lesson> lessons = RelaySiegeTacticalTrainer.lessons();
        for (int i = 0; i < lessons.size(); i++) {
            RelaySiegeTacticalTrainer.Lesson lesson = lessons.get(i);
            root.addView(lessonCard(i + 1, lesson));
        }
        setContentView(scroll);
    }

    private View lessonCard(int number, RelaySiegeTacticalTrainer.Lesson lesson) {
        LinearLayout panel = column();
        GradientDrawable background = rounded(PANEL, dp(18));
        background.setStroke(dp(1), Color.argb(70, 96, 224, 255));
        panel.setBackground(background);
        panel.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout head = row();
        TextView index = pill(String.format(Locale.US, "%02d", number), CYAN);
        head.addView(index);
        TextView principle = text(lesson.principle, 11.5f, GREEN, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(9);
        head.addView(principle, p);
        panel.addView(head);

        TextView title = text(lesson.title, 18, TEXT, true);
        title.setPadding(0, dp(11), 0, dp(6));
        panel.addView(title);
        TextView situation = text(lesson.situation, 13, MUTED, false);
        panel.addView(situation);

        Button solve = compactButton("상황 풀기 →", PANEL_2, TEXT, v -> showLesson(lesson.id));
        panel.addView(solve, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)) {{ topMargin = dp(13); }});
        panel.setLayoutParams(matchWrap(dp(10)));
        return panel;
    }

    private void showLesson(String lessonId) {
        clearReplay();
        activeLessonId = lessonId;
        selectedChoiceId = null;
        RelaySiegeTacticalTrainer.Lesson lesson = RelaySiegeTacticalTrainer.lesson(lessonId);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(19), dp(22), dp(19), dp(32));
        scroll.addView(root);

        Button back = compactButton("← 문제 목록", PANEL_2, TEXT, v -> showLessonList());
        root.addView(back, new LinearLayout.LayoutParams(dp(112), dp(43)));

        TextView principle = pill(lesson.principle, GREEN);
        principle.setPadding(dp(11), dp(7), dp(11), dp(7));
        root.addView(principle, matchWrap(dp(19)));

        TextView title = text(lesson.title, 28, TEXT, true);
        title.setPadding(0, dp(10), 0, dp(8));
        root.addView(title);

        TextView situation = text(lesson.situation, 14.5f, MUTED, false);
        situation.setBackground(rounded(PANEL, dp(15)));
        situation.setPadding(dp(14), dp(13), dp(14), dp(13));
        root.addView(situation, matchWrap(0));

        TextView previewLabel = text("현재 전장 · 결과는 아직 숨김", 11, CYAN, true);
        previewLabel.setPadding(dp(2), dp(15), 0, dp(7));
        root.addView(previewLabel);

        replayView = new RelaySiegeReplayView(this);
        replayView.setDemo(RelaySiegeTacticalReplay.preview(lessonId));
        root.addView(replayView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(255)));

        TextView prompt = text("어떤 수를 두겠습니까?", 17, TEXT, true);
        prompt.setPadding(dp(2), dp(17), 0, dp(6));
        root.addView(prompt);

        for (RelaySiegeTacticalTrainer.Choice choice : lesson.choices) {
            root.addView(choiceButton(lesson, choice));
        }

        TextView foot = text(
                "선택 후 모든 선택지를 같은 seed와 같은 초기 상태에서 재실행해 비교합니다.",
                11.5f, MUTED, false);
        foot.setPadding(dp(2), dp(14), dp(2), 0);
        root.addView(foot);
        setContentView(scroll);
    }

    private View choiceButton(
            RelaySiegeTacticalTrainer.Lesson lesson,
            RelaySiegeTacticalTrainer.Choice choice) {
        LinearLayout box = column();
        GradientDrawable background = rounded(PANEL, dp(15));
        background.setStroke(dp(1), Color.argb(55, 180, 195, 220));
        box.setBackground(background);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> showOutcome(lesson.id, choice.id));

        LinearLayout header = row();
        TextView name = text(choice.label, 15, TEXT, true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String cost;
        if (choice.isWait()) cost = "WAIT";
        else cost = RelaySiegeCards.card(choice.cardId).fluxCost + " FLUX";
        header.addView(pill(cost, choice.isWait() ? MUTED : SUN));
        box.addView(header);

        TextView hypothesis = text(choice.hypothesis, 12.5f, MUTED, false);
        hypothesis.setPadding(0, dp(6), 0, 0);
        box.addView(hypothesis);
        box.setLayoutParams(matchWrap(dp(7)));
        return box;
    }

    private void showOutcome(String lessonId, String choiceId) {
        clearReplay();
        activeLessonId = lessonId;
        selectedChoiceId = choiceId;

        RelaySiegeTacticalTrainer.Lesson lesson = RelaySiegeTacticalTrainer.lesson(lessonId);
        RelaySiegeTacticalTrainer.Result result = RelaySiegeTacticalTrainer.evaluate(lessonId);
        RelaySiegeTacticalTrainer.Outcome selected = result.forChoice(choiceId);
        RelaySiegeTacticalTrainer.Choice selectedChoice = RelaySiegeTacticalReplay.choice(lesson, choiceId);
        RelaySiegeTacticalTrainer.Choice bestChoice = RelaySiegeTacticalReplay.choice(lesson, result.best.choiceId);
        boolean best = selected.choiceId.equals(result.best.choiceId);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(19), dp(22), dp(19), dp(32));
        scroll.addView(root);

        Button back = compactButton("← 다시 선택", PANEL_2, TEXT, v -> showLesson(lessonId));
        root.addView(back, new LinearLayout.LayoutParams(dp(112), dp(43)));

        TextView verdict = text(best ? "BEST LINE" : "BETTER LINE EXISTS", 12, best ? GREEN : SUN, true);
        verdict.setLetterSpacing(0.12f);
        verdict.setPadding(0, dp(21), 0, dp(7));
        root.addView(verdict);

        TextView title = text(
                best ? "좋은 판단입니다." : "이 수보다 더 좋은 교환이 있습니다.",
                27, TEXT, true);
        root.addView(title);

        TextView compare = text(
                best
                        ? selectedChoice.label + "가 현재 규칙에서 실제 최고 점수를 냈습니다."
                        : "내 선택: " + selectedChoice.label + "\n최적 수: " + bestChoice.label,
                13.5f, MUTED, false);
        compare.setPadding(0, dp(8), 0, dp(13));
        root.addView(compare);

        LinearLayout metrics = row();
        metrics.addView(metric("RELAY DMG", Integer.toString(selected.ownRelayDamage), selected.ownRelayDamage == 0 ? GREEN : RED), weighted());
        metrics.addView(metric("SURVIVE", Integer.toString(selected.friendlySurvivorHp), CYAN), weightedMargin(dp(6)));
        metrics.addView(metric("THREAT", Integer.toString(selected.enemyThreatHp), selected.enemyThreatHp == 0 ? GREEN : SUN), weightedMargin(dp(6)));
        metrics.addView(metric("FLUX", String.format(Locale.US, "%.1f", selected.fluxSpentMilli / 1000f), SUN), weightedMargin(dp(6)));
        root.addView(metrics);

        LinearLayout scorePanel = column();
        scorePanel.setBackground(rounded(PANEL, dp(15)));
        scorePanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView score = text("내 선택  " + signed(selected.score), 16, best ? GREEN : TEXT, true);
        scorePanel.addView(score);
        if (!best) {
            TextView bestScore = text(
                    "최적 수  " + signed(result.best.score) + "  ·  차이 " + signed(result.best.score - selected.score),
                    13, GREEN, true);
            bestScore.setPadding(0, dp(5), 0, 0);
            scorePanel.addView(bestScore);
        }
        TextView hypothesis = text("WHY  " + selectedChoice.hypothesis, 12.5f, MUTED, false);
        hypothesis.setPadding(0, dp(7), 0, 0);
        scorePanel.addView(hypothesis);
        root.addView(scorePanel, matchWrap(dp(11)));

        replayModeView = text("REPLAY · MY CHOICE", 11, CYAN, true);
        replayModeView.setLetterSpacing(0.10f);
        replayModeView.setPadding(dp(2), dp(16), 0, dp(7));
        root.addView(replayModeView);

        replayView = new RelaySiegeReplayView(this);
        replayView.setDemo(RelaySiegeTacticalReplay.run(lessonId, choiceId));
        root.addView(replayView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(265)));

        LinearLayout replayButtons = row();
        Button mine = compactButton("내 선택 재생", PANEL_2, TEXT, v -> replayChoice(lessonId, choiceId, "MY CHOICE"));
        Button optimal = compactButton("최적 수 재생", best ? GREEN : CYAN, BG,
                v -> replayChoice(lessonId, result.best.choiceId, "BEST LINE"));
        replayButtons.addView(mine, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams optimalParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        optimalParams.leftMargin = dp(7);
        replayButtons.addView(optimal, optimalParams);
        root.addView(replayButtons, matchWrap(dp(8)));

        TextView rankingLabel = text("모든 선택 비교", 12, MUTED, true);
        rankingLabel.setPadding(dp(2), dp(19), 0, dp(6));
        root.addView(rankingLabel);
        int rank = 1;
        for (RelaySiegeTacticalTrainer.Outcome outcome : RelaySiegeTacticalReplay.rankedOutcomes(lessonId)) {
            root.addView(rankingCard(lesson, outcome, rank++, outcome.choiceId.equals(choiceId)));
        }

        Button next = compactButton("다음 문제 →", GREEN, BG, v -> showNextLesson(lessonId));
        root.addView(next, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)) {{ topMargin = dp(18); }});
        setContentView(scroll);
    }

    private void replayChoice(String lessonId, String choiceId, String label) {
        if (replayView == null) return;
        replayView.setDemo(RelaySiegeTacticalReplay.run(lessonId, choiceId));
        if (replayModeView != null) replayModeView.setText("REPLAY · " + label);
    }

    private View rankingCard(
            RelaySiegeTacticalTrainer.Lesson lesson,
            RelaySiegeTacticalTrainer.Outcome outcome,
            int rank,
            boolean selected) {
        RelaySiegeTacticalTrainer.Choice choice = RelaySiegeTacticalReplay.choice(lesson, outcome.choiceId);
        LinearLayout panel = column();
        GradientDrawable background = rounded(selected ? PANEL_2 : PANEL, dp(13));
        if (rank == 1) background.setStroke(dp(1), Color.argb(110, 94, 225, 157));
        panel.setBackground(background);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout header = row();
        TextView name = text(rank + ".  " + choice.label + (selected ? "  ·  MY" : ""), 13.5f, rank == 1 ? GREEN : TEXT, true);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(signed(outcome.score), rank == 1 ? GREEN : MUTED));
        panel.addView(header);
        TextView detail = text(outcome.compact(), 10.5f, MUTED, false);
        detail.setPadding(0, dp(4), 0, 0);
        panel.addView(detail);
        panel.setOnClickListener(v -> replayChoice(lesson.id, outcome.choiceId, rank == 1 ? "BEST LINE" : choice.label.toUpperCase(Locale.US)));
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setLayoutParams(matchWrap(dp(6)));
        return panel;
    }

    private void showNextLesson(String currentId) {
        List<RelaySiegeTacticalTrainer.Lesson> lessons = RelaySiegeTacticalTrainer.lessons();
        for (int i = 0; i < lessons.size(); i++) {
            if (lessons.get(i).id.equals(currentId)) {
                showLesson(lessons.get((i + 1) % lessons.size()).id);
                return;
            }
        }
        showLessonList();
    }

    private View metric(String label, String value, int accent) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setBackground(rounded(PANEL, dp(12)));
        box.setPadding(dp(5), dp(8), dp(5), dp(8));
        TextView l = text(label, 8.5f, MUTED, true);
        l.setGravity(Gravity.CENTER);
        box.addView(l);
        TextView v = text(value, 15, accent, true);
        v.setGravity(Gravity.CENTER);
        box.addView(v);
        return box;
    }

    private TextView pill(String value, int accent) {
        TextView view = text(value, 10.5f, accent, true);
        GradientDrawable bg = rounded(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(20));
        bg.setStroke(dp(1), Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent)));
        view.setBackground(bg);
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button compactButton(String label, int background, int foreground, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(13.5f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
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

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedMargin(int left) {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = left;
        return params;
    }

    private String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
