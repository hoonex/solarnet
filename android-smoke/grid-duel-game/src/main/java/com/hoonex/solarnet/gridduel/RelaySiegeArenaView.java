package com.hoonex.solarnet.gridduel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Interactive portrait arena for a SUN player versus the Relay Siege AI. */
public final class RelaySiegeArenaView extends View {
    public interface Listener {
        void onStateChanged();
        void onMessage(String message);
        void onFinished(RelaySiegeGame.Player winner, RelaySiegeGame.EndReason reason);
    }

    private static final int BG = Color.rgb(7, 11, 18);
    private static final int LANE = Color.rgb(18, 26, 38);
    private static final int LANE_ALT = Color.rgb(22, 31, 45);
    private static final int GRID = Color.argb(48, 184, 201, 225);
    private static final int TEXT = Color.rgb(239, 244, 250);
    private static final int MUTED = Color.rgb(137, 151, 174);
    private static final int SUN = Color.rgb(255, 176, 73);
    private static final int MOON = Color.rgb(118, 135, 255);
    private static final int GREEN = Color.rgb(94, 225, 157);
    private static final int CYAN = Color.rgb(96, 224, 255);
    private static final int RED = Color.rgb(255, 92, 116);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;

    private Listener listener;
    private RelaySiegeGame game;
    private String selectedCardId;
    private boolean running;
    private boolean finishNotified;

    public RelaySiegeArenaView(Context context) {
        super(context);
        setBackgroundColor(BG);
        setFocusable(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void newMatch(RelaySiegeCards.Deck playerDeck, RelaySiegeCards.Deck aiDeck, long seed) {
        pause();
        game = new RelaySiegeGame(playerDeck, aiDeck, seed);
        selectedCardId = null;
        finishNotified = false;
        invalidate();
        if (listener != null) listener.onStateChanged();
        resume();
    }

    public RelaySiegeGame getGame() {
        return game;
    }

    public String getSelectedCardId() {
        return selectedCardId;
    }

    public void selectCard(String cardId) {
        if (game == null || game.isFinished()) return;
        if (cardId == null || !game.getHand(RelaySiegeGame.Player.SUN).contains(cardId)) return;
        selectedCardId = selectedCardId != null && selectedCardId.equals(cardId) ? null : cardId;
        invalidate();
        if (listener != null) listener.onStateChanged();
    }

    public void pause() {
        running = false;
        handler.removeCallbacks(tickRunnable);
    }

    public void resume() {
        if (game == null || game.isFinished() || running) return;
        running = true;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 100L);
    }

    @Override
    protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    private void tick() {
        if (!running || game == null) return;
        if (!game.isFinished()) {
            game.advanceTicks(1);
            if (!game.isFinished() && game.getTick() % 5 == 0) {
                RelaySiegeAi.Decision decision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.MOON);
                if (!decision.wait) {
                    RelaySiegeGame.PlayResult result = RelaySiegeAi.apply(game, RelaySiegeGame.Player.MOON, decision);
                    if (!result.accepted && listener != null)
                        listener.onMessage("AI action rejected: " + result.reason);
                }
            }
        }

        invalidate();
        if (listener != null) listener.onStateChanged();
        if (game.isFinished()) {
            running = false;
            notifyFinished();
            return;
        }
        handler.postDelayed(tickRunnable, 100L);
    }

    private void notifyFinished() {
        if (finishNotified || game == null || !game.isFinished()) return;
        finishNotified = true;
        if (listener != null) listener.onFinished(game.getWinner(), game.getEndReason());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || game == null || game.isFinished()) return true;
        if (selectedCardId == null) {
            if (listener != null) listener.onMessage("먼저 아래 손패에서 카드를 선택하세요.");
            return true;
        }

        float top = arenaTop();
        float bottom = arenaBottom();
        if (event.getY() < top || event.getY() > bottom) return true;
        RelaySiegeGame.Lane lane = event.getX() < getWidth() / 2f
                ? RelaySiegeGame.Lane.LEFT : RelaySiegeGame.Lane.RIGHT;
        float normalized = (bottom - event.getY()) / Math.max(1f, bottom - top);
        int position = clamp(Math.round(normalized * 100_000f), 0, 100_000);

        RelaySiegeGame.PlayResult result = game.tryPlay(
                RelaySiegeGame.Player.SUN, selectedCardId, lane, position);
        if (result.accepted) {
            RelaySiegeCards.Card card = RelaySiegeCards.card(selectedCardId);
            selectedCardId = null;
            if (listener != null) listener.onMessage(card.name + " 배치");
        } else if (listener != null) {
            listener.onMessage(koreanReason(result.reason));
        }
        invalidate();
        if (listener != null) listener.onStateChanged();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        if (game == null) return;

        float top = arenaTop();
        float bottom = arenaBottom();
        float midX = getWidth() / 2f;
        float gap = dp(5);
        RectF leftLane = new RectF(dp(8), top, midX - gap, bottom);
        RectF rightLane = new RectF(midX + gap, top, getWidth() - dp(8), bottom);
        paint.setColor(LANE);
        canvas.drawRoundRect(leftLane, dp(18), dp(18), paint);
        paint.setColor(LANE_ALT);
        canvas.drawRoundRect(rightLane, dp(18), dp(18), paint);

        drawDeploymentZone(canvas, leftLane, RelaySiegeGame.Lane.LEFT);
        drawDeploymentZone(canvas, rightLane, RelaySiegeGame.Lane.RIGHT);
        drawGrid(canvas, leftLane, rightLane);
        drawObjectives(canvas, leftLane, rightLane);
        drawEntities(canvas, leftLane, rightLane);
        drawCenterMark(canvas, leftLane, rightLane);
    }

    private void drawDeploymentZone(Canvas canvas, RectF laneRect, RelaySiegeGame.Lane lane) {
        if (selectedCardId == null) return;
        RelaySiegeCards.Card card = RelaySiegeCards.card(selectedCardId);
        paint.setColor(Color.argb(30, Color.red(GREEN), Color.green(GREEN), Color.blue(GREEN)));
        if (card.kind == RelaySiegeCards.Kind.SPELL) {
            canvas.drawRoundRect(laneRect, dp(18), dp(18), paint);
            return;
        }
        int max = game.getRelayHp(RelaySiegeGame.Player.MOON, lane) > 0
                ? RelaySiegeGame.BASE_SUN_DEPLOY_MAX
                : RelaySiegeGame.ADVANCED_SUN_DEPLOY_MAX;
        float y = yForPosition(max);
        RectF deploy = new RectF(laneRect.left, y, laneRect.right, laneRect.bottom);
        canvas.drawRoundRect(deploy, dp(18), dp(18), paint);
    }

    private void drawGrid(Canvas canvas, RectF left, RectF right) {
        stroke.setColor(GRID);
        stroke.setStrokeWidth(dp(1));
        for (int p = 10_000; p < 100_000; p += 10_000) {
            float y = yForPosition(p);
            canvas.drawLine(left.left + dp(6), y, left.right - dp(6), y, stroke);
            canvas.drawLine(right.left + dp(6), y, right.right - dp(6), y, stroke);
        }
        float centerY = yForPosition(50_000);
        stroke.setColor(Color.argb(105, 96, 224, 255));
        stroke.setStrokeWidth(dp(2));
        canvas.drawLine(left.left + dp(4), centerY, right.right - dp(4), centerY, stroke);
    }

    private void drawObjectives(Canvas canvas, RectF left, RectF right) {
        drawRelay(canvas, centerX(left), yForPosition(RelaySiegeGame.SUN_RELAY_POSITION),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT), SUN, "S-L");
        drawRelay(canvas, centerX(right), yForPosition(RelaySiegeGame.SUN_RELAY_POSITION),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT), SUN, "S-R");
        drawRelay(canvas, centerX(left), yForPosition(RelaySiegeGame.MOON_RELAY_POSITION),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT), MOON, "M-L");
        drawRelay(canvas, centerX(right), yForPosition(RelaySiegeGame.MOON_RELAY_POSITION),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT), MOON, "M-R");

        drawCore(canvas, getWidth() / 2f, yForPosition(RelaySiegeGame.SUN_CORE_POSITION),
                game.getCoreHp(RelaySiegeGame.Player.SUN), SUN, "SUN CORE");
        drawCore(canvas, getWidth() / 2f, yForPosition(RelaySiegeGame.MOON_CORE_POSITION),
                game.getCoreHp(RelaySiegeGame.Player.MOON), MOON, "MOON CORE");
    }

    private void drawRelay(Canvas canvas, float x, float y, int hp, int color, String label) {
        float w = dp(44);
        float h = dp(24);
        paint.setColor(Color.argb(hp > 0 ? 220 : 75, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRoundRect(new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), dp(7), dp(7), paint);
        drawHpBar(canvas, x - w / 2f, y + h / 2f + dp(3), w, hp, RelaySiegeGame.RELAY_MAX_HP, color);
        drawText(canvas, label, x, y + dp(4), 9, BG, Paint.Align.CENTER, true);
    }

    private void drawCore(Canvas canvas, float x, float y, int hp, int color, String label) {
        float w = dp(92);
        float h = dp(26);
        paint.setColor(Color.argb(235, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRoundRect(new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), dp(9), dp(9), paint);
        drawHpBar(canvas, x - w / 2f, y + h / 2f + dp(3), w, hp, RelaySiegeGame.CORE_MAX_HP, color);
        drawText(canvas, label, x, y + dp(4), 9, BG, Paint.Align.CENTER, true);
    }

    private void drawEntities(Canvas canvas, RectF left, RectF right) {
        for (RelaySiegeGame.EntityView entity : game.getEntities()) {
            RectF lane = entity.lane == RelaySiegeGame.Lane.LEFT ? left : right;
            float laneCenter = centerX(lane);
            float offset = ((entity.id % 3) - 1) * dp(9);
            float x = laneCenter + offset;
            float y = yForPosition(entity.position);
            int color = entity.owner == RelaySiegeGame.Player.SUN ? SUN : MOON;
            float radius = entity.building ? dp(12) : dp(entity.airborne ? 11 : 10);

            if (entity.building) {
                paint.setColor(withAlpha(color, 230));
                canvas.drawRoundRect(new RectF(x - radius, y - radius, x + radius, y + radius), dp(4), dp(4), paint);
            } else if (entity.airborne) {
                Path diamond = new Path();
                diamond.moveTo(x, y - radius);
                diamond.lineTo(x + radius, y);
                diamond.lineTo(x, y + radius);
                diamond.lineTo(x - radius, y);
                diamond.close();
                paint.setColor(withAlpha(color, 230));
                canvas.drawPath(diamond, paint);
            } else {
                paint.setColor(withAlpha(color, 230));
                canvas.drawCircle(x, y, radius, paint);
            }

            RelaySiegeCards.Card card = RelaySiegeCards.card(entity.cardId);
            drawText(canvas, shortName(card.name), x, y + dp(3), 7, BG, Paint.Align.CENTER, true);
            drawHpBar(canvas, x - radius, y + radius + dp(2), radius * 2f, entity.hp, entity.maxHp, color);
            if (entity.overclockTicks > 0) drawStatusDot(canvas, x - radius, y - radius, CYAN);
            if (entity.slowTicks > 0) drawStatusDot(canvas, x + radius, y - radius, RED);
        }
    }

    private void drawStatusDot(Canvas canvas, float x, float y, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, dp(3), paint);
    }

    private void drawCenterMark(Canvas canvas, RectF left, RectF right) {
        float y = yForPosition(50_000);
        drawText(canvas, "RELAY LINE", getWidth() / 2f, y - dp(7), 8, MUTED, Paint.Align.CENTER, true);
        drawText(canvas, "LEFT", centerX(left), arenaTop() + dp(15), 8, MUTED, Paint.Align.CENTER, true);
        drawText(canvas, "RIGHT", centerX(right), arenaTop() + dp(15), 8, MUTED, Paint.Align.CENTER, true);
    }

    private void drawHpBar(Canvas canvas, float x, float y, float width, int hp, int maxHp, int color) {
        float ratio = maxHp <= 0 ? 0f : Math.max(0f, Math.min(1f, hp / (float) maxHp));
        paint.setColor(Color.argb(110, 0, 0, 0));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + dp(3)), dp(2), dp(2), paint);
        paint.setColor(color);
        canvas.drawRoundRect(new RectF(x, y, x + width * ratio, y + dp(3)), dp(2), dp(2), paint);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float sp, int color,
                          Paint.Align align, boolean bold) {
        paint.setColor(color);
        paint.setTextSize(spToPx(sp));
        paint.setTextAlign(align);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        canvas.drawText(text, x, y, paint);
    }

    private float yForPosition(int position) {
        float top = arenaTop();
        float bottom = arenaBottom();
        return bottom - (clamp(position, 0, 100_000) / 100_000f) * (bottom - top);
    }

    private float arenaTop() {
        return dp(10);
    }

    private float arenaBottom() {
        return Math.max(arenaTop() + dp(60), getHeight() - dp(10));
    }

    private float centerX(RectF rect) {
        return (rect.left + rect.right) / 2f;
    }

    private String koreanReason(String reason) {
        if ("NOT_ENOUGH_FLUX".equals(reason)) return "Flux가 부족합니다.";
        if ("INVALID_DEPLOYMENT_POSITION".equals(reason)) return "이 카드는 그 위치에 배치할 수 없습니다.";
        if ("CARD_NOT_IN_HAND".equals(reason)) return "현재 손패에 없는 카드입니다.";
        if ("MATCH_FINISHED".equals(reason)) return "이미 경기가 끝났습니다.";
        return String.format(Locale.KOREA, "배치 실패: %s", reason);
    }

    private String shortName(String name) {
        String compact = name.replace(" ", "").toUpperCase(Locale.US);
        return compact.substring(0, Math.min(2, compact.length()));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
