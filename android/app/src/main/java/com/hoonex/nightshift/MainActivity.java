package com.hoonex.nightshift;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launcher menu. Single-player is the primary product path; multiplayer is optional. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.BLACK);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(42), dp(26), dp(42), dp(26));
        root.setBackgroundColor(Color.rgb(2, 4, 6));

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("NIGHTSHIFT", 42, Color.WHITE);
        logo.setLetterSpacing(.18f);
        title.addView(logo);
        title.addView(text("SURVIVE THE FACILITY", 14, Color.rgb(155, 168, 172)));
        TextView pitch = text("Find the fuses. Restore power. Recover the keycard.\nStay quiet when the hunter is close. Then get out.", 17, Color.rgb(210, 215, 216));
        pitch.setLineSpacing(0, 1.18f);
        title.addView(pitch);
        root.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(28), 0, 0, 0);

        Button solo = primaryButton("PLAY SOLO");
        Button multi = secondaryButton("MULTIPLAYER  ·  EXPERIMENTAL");
        Button quit = secondaryButton("QUIT");
        TextView soloHint = text("Offline · no server or room code required", 13, Color.rgb(120, 145, 148));
        soloHint.setGravity(Gravity.CENTER);

        actions.addView(solo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        actions.addView(soloHint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        mp.topMargin = dp(8);
        actions.addView(multi, mp);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        qp.topMargin = dp(8);
        actions.addView(quit, qp);

        solo.setOnClickListener(v -> startActivity(new Intent(this, SoloGameActivity.class)));
        multi.setOnClickListener(v -> startActivity(new Intent(this, MultiplayerActivity.class)));
        quit.setOnClickListener(v -> finishAndRemoveTask());

        root.addView(actions, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .85f));
        return root;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.BLACK);
        b.setBackgroundColor(Color.rgb(210, 232, 225));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(22, 29, 32));
        return b;
    }

    private TextView text(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0, dp(7), 0, dp(7));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
