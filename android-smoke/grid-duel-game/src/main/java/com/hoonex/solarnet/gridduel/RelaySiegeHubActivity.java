package com.hoonex.solarnet.gridduel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Entry point separating deck lab/AI practice from actual two-phone Bluetooth play. */
public final class RelaySiegeHubActivity extends Activity {
    private static final int BG = Color.rgb(6, 10, 17);
    private static final int PANEL = Color.rgb(17, 24, 35);
    private static final int TEXT = Color.rgb(242, 246, 251);
    private static final int MUTED = Color.rgb(148, 160, 181);
    private static final int GREEN = Color.rgb(94, 225, 157);
    private static final int CYAN = Color.rgb(96, 224, 255);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(34), dp(22), dp(26));

        Button back = button("← SOLAR ARCADE", PANEL, TEXT);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(150), dp(46)));

        TextView eyebrow = text("REAL-TIME DECK STRATEGY", 12, GREEN, true);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setPadding(0, dp(30), 0, dp(7));
        root.addView(eyebrow);
        root.addView(text("RELAY\nSIEGE", 46, TEXT, true));

        TextView desc = text("같은 전투 엔진을 AI 연습과 실제 근거리 2인전에서 사용합니다.", 15, MUTED, false);
        desc.setPadding(0, dp(12), 0, dp(28));
        root.addView(desc);

        root.addView(modeCard(
                "DECK LAB + AI",
                "덱 가이드 · 실제 엔진 미니 리플레이 · AI 연습전",
                GREEN,
                v -> startActivity(new Intent(this, RelaySiegeActivity.class))));

        root.addView(modeCard(
                "BLUETOOTH 2P",
                "인터넷 없이 페어링된 두 Android 폰 · HOST authoritative simulation · 재연결 복구",
                CYAN,
                v -> startActivity(new Intent(this, RelaySiegeBluetoothActivity.class))));

        TextView note = text(
                "Bluetooth Classic RFCOMM을 사용합니다. 두 폰은 Android Bluetooth 설정에서 먼저 페어링합니다.",
                12, MUTED, false);
        note.setPadding(dp(4), dp(23), dp(4), 0);
        root.addView(note);
        setContentView(root);
    }

    private LinearLayout modeCard(String title, String body, int accent, android.view.View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(PANEL, dp(18));
        bg.setStroke(dp(1), Color.argb(85, Color.red(accent), Color.green(accent), Color.blue(accent)));
        card.setBackground(bg);
        card.setPadding(dp(17), dp(16), dp(17), dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);
        card.addView(text(title, 20, accent, true));
        TextView bodyView = text(body, 13.5f, MUTED, false);
        bodyView.setPadding(0, dp(8), 0, 0);
        card.addView(bodyView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private Button button(String value, int bgColor, int textColor) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(textColor);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(bgColor, dp(12)));
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

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
