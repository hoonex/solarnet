package com.hoonex.nightshift;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Game launcher. Single-player remains the primary path; multiplayer is secondary. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.BLACK);
        hideSystemUi();
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(48), dp(30), dp(48), dp(30));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] { 0xFF020405, 0xFF081013, 0xFF020304 });
        root.setBackground(bg);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView status = text("NIGHTSHIFT // 0.5.0", 11, Color.rgb(133, 156, 157));
        status.setLetterSpacing(.16f);
        title.addView(status);
        TextView logo = text("NIGHTSHIFT", 44, Color.WHITE);
        logo.setLetterSpacing(.19f);
        title.addView(logo);
        TextView subtitle = text("FACILITY 17 · 02:13 AM", 13, Color.rgb(178, 72, 67));
        subtitle.setLetterSpacing(.08f);
        title.addView(subtitle);
        TextView pitch = text("The night crew vanished after the power failure.\nRecover three fuses and the security keycard.\nIf the lights fail again, do not run unless you have to.", 16, Color.rgb(198, 207, 205));
        pitch.setLineSpacing(dp(2), 1.18f);
        LinearLayout.LayoutParams pitchParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pitchParams.topMargin = dp(18);
        title.addView(pitch, pitchParams);
        root.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.20f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(34), 0, 0, 0);

        Button solo = primaryButton("BEGIN SHIFT");
        Button multi = secondaryButton("MULTIPLAYER  ·  EXPERIMENTAL");
        Button quit = secondaryButton("QUIT");
        TextView soloHint = text("PLAY SOLO  ·  OFFLINE  ·  NO SERVER REQUIRED", 11, Color.rgb(116, 139, 140));
        soloHint.setGravity(Gravity.CENTER);
        soloHint.setLetterSpacing(.07f);

        actions.addView(solo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));
        actions.addView(soloHint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        mp.topMargin = dp(10);
        actions.addView(multi, mp);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        qp.topMargin = dp(10);
        actions.addView(quit, qp);

        solo.setOnClickListener(v -> startActivity(new Intent(this, SoloGameActivity.class)));
        multi.setOnClickListener(v -> startActivity(new Intent(this, MultiplayerActivity.class)));
        quit.setOnClickListener(v -> finishAndRemoveTask());

        root.addView(actions, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .80f));
        return root;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(16); b.setLetterSpacing(.10f); b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { 0xFF5E1717, 0xFF381012 });
        d.setCornerRadius(dp(6)); d.setStroke(dp(1), 0xFF9D4A45); b.setBackground(d);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(12); b.setLetterSpacing(.04f); b.setTextColor(Color.rgb(220, 228, 225));
        GradientDrawable d = new GradientDrawable(); d.setColor(0xB0151C1F); d.setCornerRadius(dp(6)); d.setStroke(dp(1), 0xFF394448); b.setBackground(d);
        return b;
    }

    private TextView text(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sp); v.setTextColor(color); v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
