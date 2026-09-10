package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameSnapshot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Non-interactive screen-space horror pass: vignette, danger pulse, scanlines and reticle. */
final class AtmosphereOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient vignette;
    private volatile double tension;
    private volatile boolean blackout;
    private volatile double movement;
    private volatile boolean sprinting;

    AtmosphereOverlay(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    void setState(GameSnapshot.PlayerView me, boolean blackout, double movement, boolean sprinting) {
        this.tension = me == null ? 0 : me.tension;
        this.blackout = blackout;
        this.movement = movement;
        this.sprinting = sprinting;
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float radius = Math.max(w, h) * .76f;
        vignette = new RadialGradient(
            w * .5f, h * .5f, radius,
            new int[] { Color.TRANSPARENT, 0x26000000, 0xD0000000 },
            new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = System.nanoTime();
        double seconds = now / 1_000_000_000.0;

        if (vignette != null) {
            paint.setShader(vignette);
            paint.setAlpha(255);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        if (blackout) {
            int flicker = 22 + (int)(12 * (0.5 + 0.5 * Math.sin(seconds * 19.0)));
            paint.setColor(Color.BLACK);
            paint.setAlpha(flicker);
            canvas.drawRect(0, 0, w, h, paint);
        }

        linePaint.setStrokeWidth(1f);
        linePaint.setColor(Color.BLACK);
        linePaint.setAlpha(16);
        int gap = Math.max(4, dp(4));
        for (int y = 1; y < h; y += gap) canvas.drawLine(0, y, w, y, linePaint);

        if (tension > .42) {
            double normalized = Math.min(1.0, (tension - .42) / .58);
            double pulse = .55 + .45 * Math.sin(seconds * (4.0 + tension * 4.0));
            int alpha = (int)(115 * normalized * pulse);
            paint.setColor(0xFF7C0909);
            paint.setAlpha(alpha);
            int edge = dp(16);
            canvas.drawRect(0, 0, w, edge, paint);
            canvas.drawRect(0, h - edge, w, h, paint);
            canvas.drawRect(0, 0, edge, h, paint);
            canvas.drawRect(w - edge, 0, w, h, paint);
        }

        float cx = w * .5f, cy = h * .5f;
        float spread = dp(5) + (float)Math.min(dp(4), movement * (sprinting ? dp(4) : dp(2)));
        float length = dp(6);
        linePaint.setStrokeWidth(Math.max(1.5f, dp(1)));
        linePaint.setColor(Color.rgb(224, 232, 226));
        linePaint.setAlpha(190);
        canvas.drawLine(cx - spread - length, cy, cx - spread, cy, linePaint);
        canvas.drawLine(cx + spread, cy, cx + spread + length, cy, linePaint);
        canvas.drawLine(cx, cy - spread - length, cx, cy - spread, linePaint);
        canvas.drawLine(cx, cy + spread, cx, cy + spread + length, linePaint);
        canvas.drawCircle(cx, cy, Math.max(1.2f, dp(1)), linePaint);

        if (tension > .42 || blackout || movement > .05) postInvalidateOnAnimation();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
