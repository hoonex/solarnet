package com.hoonex.solarnet.gridduel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Locale;

/** Loops deterministic frames captured from RelaySiegeDeckGuide's real combat simulation. */
public final class RelaySiegeReplayView extends View {
    private static final int BG = Color.rgb(8, 12, 19);
    private static final int PANEL = Color.rgb(18, 27, 39);
    private static final int PANEL_2 = Color.rgb(22, 32, 47);
    private static final int SUN = Color.rgb(255, 176, 73);
    private static final int MOON = Color.rgb(118, 135, 255);
    private static final int TEXT = Color.rgb(235, 241, 249);
    private static final int MUTED = Color.rgb(138, 151, 174);
    private static final int CYAN = Color.rgb(96, 224, 255);
    private static final int RED = Color.rgb(255, 92, 116);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advanceRunnable = this::advanceFrame;

    private RelaySiegeDeckGuide.DemoRun run;
    private int frameIndex;
    private boolean running;

    public RelaySiegeReplayView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        setBackgroundColor(BG);
    }

    public void setDemo(RelaySiegeDeckGuide.DemoRun run) {
        pause();
        this.run = run;
        frameIndex = 0;
        invalidate();
        resume();
    }

    public RelaySiegeDeckGuide.DemoRun getRun() {
        return run;
    }

    public int getFrameIndex() {
        return frameIndex;
    }

    public void pause() {
        running = false;
        handler.removeCallbacks(advanceRunnable);
    }

    public void resume() {
        if (run == null || run.frames.size() < 2 || running) return;
        running = true;
        handler.removeCallbacks(advanceRunnable);
        handler.postDelayed(advanceRunnable, 360L);
    }

    @Override
    protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    private void advanceFrame() {
        if (!running || run == null || run.frames.isEmpty()) return;
        frameIndex = (frameIndex + 1) % run.frames.size();
        invalidate();
        handler.postDelayed(advanceRunnable, frameIndex == 0 ? 760L : 360L);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        if (run == null || run.frames.isEmpty()) {
            drawText(canvas, "NO REPLAY", getWidth() / 2f, getHeight() / 2f, 12, MUTED, Paint.Align.CENTER, true);
            return;
        }

        RelaySiegeDeckGuide.DemoFrame frame = run.frames.get(Math.min(frameIndex, run.frames.size() - 1));
        float top = dp(22);
        float bottom = getHeight() - dp(23);
        float mid = getWidth() / 2f;
        float gap = dp(4);
        RectF left = new RectF(dp(8), top, mid - gap, bottom);
        RectF right = new RectF(mid + gap, top, getWidth() - dp(8), bottom);
        paint.setColor(PANEL);
        canvas.drawRoundRect(left, dp(14), dp(14), paint);
        paint.setColor(PANEL_2);
        canvas.drawRoundRect(right, dp(14), dp(14), paint);

        stroke.setColor(Color.argb(48, 200, 214, 235));
        for (int p = 20_000; p < 100_000; p += 20_000) {
            float y = yFor(p, top, bottom);
            canvas.drawLine(left.left + dp(5), y, left.right - dp(5), y, stroke);
            canvas.drawLine(right.left + dp(5), y, right.right - dp(5), y, stroke);
        }
        float centerY = yFor(50_000, top, bottom);
        stroke.setColor(Color.argb(100, 96, 224, 255));
        canvas.drawLine(left.left, centerY, right.right, centerY, stroke);

        drawRelay(canvas, centerX(left), yFor(RelaySiegeGame.SUN_RELAY_POSITION, top, bottom),
                frame.sunLeftRelayHp, SUN);
        drawRelay(canvas, centerX(right), yFor(RelaySiegeGame.SUN_RELAY_POSITION, top, bottom),
                frame.sunRightRelayHp, SUN);
        drawRelay(canvas, centerX(left), yFor(RelaySiegeGame.MOON_RELAY_POSITION, top, bottom),
                frame.moonLeftRelayHp, MOON);
        drawRelay(canvas, centerX(right), yFor(RelaySiegeGame.MOON_RELAY_POSITION, top, bottom),
                frame.moonRightRelayHp, MOON);

        for (RelaySiegeGame.EntityView entity : frame.entities) {
            RectF lane = entity.lane == RelaySiegeGame.Lane.LEFT ? left : right;
            float x = centerX(lane) + ((entity.id % 3) - 1) * dp(7);
            float y = yFor(entity.position, top, bottom);
            int color = entity.owner == RelaySiegeGame.Player.SUN ? SUN : MOON;
            float r = dp(entity.building ? 9 : 8);
            if (entity.building) {
                paint.setColor(withAlpha(color, 230));
                canvas.drawRoundRect(new RectF(x - r, y - r, x + r, y + r), dp(3), dp(3), paint);
            } else if (entity.airborne) {
                Path path = new Path();
                path.moveTo(x, y - r);
                path.lineTo(x + r, y);
                path.lineTo(x, y + r);
                path.lineTo(x - r, y);
                path.close();
                paint.setColor(withAlpha(color, 230));
                canvas.drawPath(path, paint);
            } else {
                paint.setColor(withAlpha(color, 230));
                canvas.drawCircle(x, y, r, paint);
            }
            drawText(canvas, shortName(RelaySiegeCards.card(entity.cardId).name), x, y + dp(2.5f), 6.5f, BG,
                    Paint.Align.CENTER, true);
            if (entity.overclockTicks > 0) dot(canvas, x - r, y - r, CYAN);
            if (entity.slowTicks > 0) dot(canvas, x + r, y - r, RED);
        }

        String time = String.format(Locale.US, "%.1fs", frame.tick / 10f);
        drawText(canvas, time, dp(10), dp(15), 9, TEXT, Paint.Align.LEFT, true);
        drawText(canvas,
                String.format(Locale.US, "SUN %.1f  ·  MOON %.1f Flux", frame.sunFluxMilli / 1000f, frame.moonFluxMilli / 1000f),
                getWidth() - dp(10), dp(15), 8.5f, MUTED, Paint.Align.RIGHT, true);
        drawText(canvas, (frameIndex + 1) + " / " + run.frames.size(), getWidth() - dp(10), getHeight() - dp(7),
                8, MUTED, Paint.Align.RIGHT, false);
    }

    private void drawRelay(Canvas canvas, float x, float y, int hp, int color) {
        float w = dp(34);
        float h = dp(17);
        paint.setColor(withAlpha(color, hp > 0 ? 220 : 65));
        canvas.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), dp(5), dp(5), paint);
        float ratio = Math.max(0f, Math.min(1f, hp / (float) RelaySiegeGame.RELAY_MAX_HP));
        paint.setColor(Color.argb(100, 0, 0, 0));
        canvas.drawRect(x - w / 2, y + h / 2 + dp(2), x + w / 2, y + h / 2 + dp(4), paint);
        paint.setColor(color);
        canvas.drawRect(x - w / 2, y + h / 2 + dp(2), x - w / 2 + w * ratio, y + h / 2 + dp(4), paint);
    }

    private void dot(Canvas canvas, float x, float y, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, dp(2.5f), paint);
    }

    private float yFor(int position, float top, float bottom) {
        int p = Math.max(0, Math.min(100_000, position));
        return bottom - p / 100_000f * (bottom - top);
    }

    private float centerX(RectF rect) {
        return (rect.left + rect.right) / 2f;
    }

    private String shortName(String name) {
        String compact = name.replace(" ", "").toUpperCase(Locale.US);
        return compact.substring(0, Math.min(2, compact.length()));
    }

    private void drawText(Canvas canvas, String value, float x, float y, float sp, int color,
                          Paint.Align align, boolean bold) {
        paint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(color);
        paint.setTextAlign(align);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        canvas.drawText(value, x, y, paint);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
