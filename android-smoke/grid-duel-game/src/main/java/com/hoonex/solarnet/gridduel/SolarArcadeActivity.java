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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher surface for individually developed SolarNet games. */
public final class SolarArcadeActivity extends Activity {
    private static final int BG = Color.rgb(6, 9, 15);
    private static final int PANEL = Color.rgb(18, 23, 33);
    private static final int TEXT = Color.rgb(244, 247, 252);
    private static final int MUTED = Color.rgb(151, 160, 178);
    private static final int CYAN = Color.rgb(96, 224, 255);
    private static final int ORANGE = Color.rgb(255, 174, 61);
    private static final int MOON = Color.rgb(111, 130, 255);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showLauncher();
    }

    private void showLauncher() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(34), dp(22), dp(32));
        scroll.addView(root);

        TextView eyebrow = text("SOLARNET GAMES", 12, CYAN, true);
        eyebrow.setLetterSpacing(0.16f);
        root.addView(eyebrow);

        TextView title = text("SOLAR\nARCADE", 46, TEXT, true);
        title.setLineSpacing(-dp(5), 0.92f);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);

        TextView subtitle = text(
                "한 번에 여러 게임을 대충 넣지 않고, 각 게임을 독립적으로 완성해 나가는 실험실.",
                15,
                MUTED,
                false);
        subtitle.setPadding(0, dp(10), 0, dp(25));
        root.addView(subtitle);

        root.addView(gameCard(
                "01",
                "PULSE HOCKEY 3D",
                "3D 턴제 에어하키 × 전술 전투",
                "STRIKE · POWER · GUARD · COUNTER  /  AI · LOCAL 2P",
                ORANGE,
                v -> startActivity(new Intent(this, PulseHockeyActivity.class))));

        TextView classicLabel = text("CLASSIC", 11, MUTED, true);
        classicLabel.setLetterSpacing(0.13f);
        classicLabel.setPadding(dp(3), dp(25), 0, dp(7));
        root.addView(classicLabel);

        root.addView(gameCard(
                "00",
                "GRID DUEL",
                "기존 5×5 턴제 프로토타입",
                "AI · LOCAL 2P · BLUETOOTH · NEARBY",
                MOON,
                v -> startActivity(new Intent(this, MainActivity.class))));

        TextView footer = text(
                "Pulse Hockey의 규칙과 AI를 먼저 안정화한 뒤 다음 게임을 추가합니다.",
                12,
                MUTED,
                false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(12), dp(28), dp(12), 0);
        root.addView(footer);

        setContentView(scroll);
    }

    private View gameCard(
            String number,
            String title,
            String subtitle,
            String meta,
            int accent,
            View.OnClickListener click) {
        LinearLayout card = column();
        GradientDrawable background = rounded(PANEL, dp(20));
        background.setStroke(dp(1), withAlpha(accent, 82));
        card.setBackground(background);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView index = text(number, 12, accent, true);
        index.setGravity(Gravity.CENTER);
        index.setBackground(rounded(withAlpha(accent, 35), dp(10)));
        index.setPadding(dp(9), dp(6), dp(9), dp(6));
        header.addView(index);
        TextView heading = text(title, 20, TEXT, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headingParams.leftMargin = dp(11);
        header.addView(heading, headingParams);
        TextView arrow = text("›", 30, accent, false);
        header.addView(arrow);
        card.addView(header);

        TextView body = text(subtitle, 14, MUTED, false);
        body.setPadding(0, dp(10), 0, dp(5));
        card.addView(body);
        TextView metaView = text(meta, 11, withAlpha(TEXT, 175), true);
        metaView.setLetterSpacing(0.035f);
        card.addView(metaView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(9);
        card.setLayoutParams(params);
        return card;
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
