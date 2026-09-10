package com.hoonex.solarnet.gridduel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Snapshot-driven arena used by both Bluetooth host and client. Local player is always at bottom. */
public final class RelaySiegeNetworkArenaView extends View {
    public interface Listener {
        void onDeploy(RelaySiegeGame.Lane lane, int position);
        void onMessage(String message);
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

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RelaySiegeNetworkState state;
    private String selectedCardId;
    private Listener listener;

    public RelaySiegeNetworkArenaView(Context context) {
        super(context);
        setBackgroundColor(BG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setState(RelaySiegeNetworkState state) {
        this.state = state;
        if (selectedCardId != null && (state == null || !state.yourHand.contains(selectedCardId)))
            selectedCardId = null;
        invalidate();
    }

    public RelaySiegeNetworkState getState() { return state; }
    public String getSelectedCardId() { return selectedCardId; }

    public void selectCard(String cardId) {
        if (state == null || state.isFinished()) return;
        if (cardId == null || !state.yourHand.contains(cardId)) return;
        selectedCardId = cardId.equals(selectedCardId) ? null : cardId;
        invalidate();
    }

    public void clearSelection() {
        selectedCardId = null;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || state == null || state.isFinished()) return true;
        if (selectedCardId == null) {
            if (listener != null) listener.onMessage("먼저 손패에서 카드를 선택하세요.");
            return true;
        }
        float top = arenaTop();
        float bottom = arenaBottom();
        if (event.getY() < top || event.getY() > bottom) return true;
        RelaySiegeGame.Lane lane = event.getX() < getWidth() / 2f
                ? RelaySiegeGame.Lane.LEFT : RelaySiegeGame.Lane.RIGHT;
        int position = worldPositionForY(event.getY());
        if (listener != null) listener.onDeploy(lane, position);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        if (state == null) {
            drawCentered(canvas, "Bluetooth 상태 동기화 중…", getHeight() / 2f, MUTED, 14, false);
            return;
        }

        float top = arenaTop();
        float bottom = arenaBottom();
        float midX = getWidth() / 2f;
        float gap = dp(5);
        RectF left = new RectF(dp(8), top, midX - gap, bottom);
        RectF right = new RectF(midX + gap, top, getWidth() - dp(8), bottom);
        fill.setColor(LANE);
        canvas.drawRoundRect(left, dp(18), dp(18), fill);
        fill.setColor(LANE_ALT);
        canvas.drawRoundRect(right, dp(18), dp(18), fill);

        drawDeploymentZone(canvas, left, RelaySiegeGame.Lane.LEFT);
        drawDeploymentZone(canvas, right, RelaySiegeGame.Lane.RIGHT);
        drawGrid(canvas, left, right);
        drawObjectives(canvas, left, right);
        drawEntities(canvas, left, right);
        drawCenter(canvas, left, right);
    }

    private void drawDeploymentZone(Canvas canvas, RectF laneRect, RelaySiegeGame.Lane lane) {
        if (selectedCardId == null || state == null) return;
        RelaySiegeCards.Card card = RelaySiegeCards.card(selectedCardId);
        fill.setColor(Color.argb(32, Color.red(GREEN), Color.green(GREEN), Color.blue(GREEN)));
        if (card.kind == RelaySiegeCards.Kind.SPELL) {
            canvas.drawRoundRect(laneRect, dp(18), dp(18), fill);
            return;
        }

        int low;
        int high;
        if (state.perspective == RelaySiegeGame.Player.SUN) {
            low = 4_000;
            high = state.relayHp(RelaySiegeGame.Player.MOON, lane) > 0
                    ? RelaySiegeGame.BASE_SUN_DEPLOY_MAX : RelaySiegeGame.ADVANCED_SUN_DEPLOY_MAX;
        } else {
            low = state.relayHp(RelaySiegeGame.Player.SUN, lane) > 0
                    ? RelaySiegeGame.BASE_MOON_DEPLOY_MIN : RelaySiegeGame.ADVANCED_MOON_DEPLOY_MIN;
            high = 96_000;
        }
        float y1 = yForPosition(low);
        float y2 = yForPosition(high);
        RectF zone = new RectF(laneRect.left, Math.min(y1, y2), laneRect.right, Math.max(y1, y2));
        canvas.drawRoundRect(zone, dp(16), dp(16), fill);
    }

    private void drawGrid(Canvas canvas, RectF left, RectF right) {
        stroke.setColor(GRID);
        stroke.setStrokeWidth(dp(1));
        for (int p = 10_000; p < 100_000; p += 10_000) {
            float y = yForPosition(p);
            canvas.drawLine(left.left + dp(6), y, left.right - dp(6), y, stroke);
            canvas.drawLine(right.left + dp(6), y, right.right - dp(6), y, stroke);
        }
        float center = yForPosition(50_000);
        stroke.setColor(Color.argb(110, 96, 224, 255));
        stroke.setStrokeWidth(dp(2));
        canvas.drawLine(left.left + dp(4), center, right.right - dp(4), center, stroke);
    }

    private void drawObjectives(Canvas canvas, RectF left, RectF right) {
        drawRelay(canvas, centerX(left), yForPosition(RelaySiegeGame.SUN_RELAY_POSITION),
                state.sunLeftRelayHp, SUN, "S-L");
        drawRelay(canvas, centerX(right), yForPosition(RelaySiegeGame.SUN_RELAY_POSITION),
                state.sunRightRelayHp, SUN, "S-R");
        drawRelay(canvas, centerX(left), yForPosition(RelaySiegeGame.MOON_RELAY_POSITION),
                state.moonLeftRelayHp, MOON, "M-L");
        drawRelay(canvas, centerX(right), yForPosition(RelaySiegeGame.MOON_RELAY_POSITION),
                state.moonRightRelayHp, MOON, "M-R");

        drawCore(canvas, yForPosition(RelaySiegeGame.SUN_CORE_POSITION), state.sunCoreHp, SUN, "SUN CORE");
        drawCore(canvas, yForPosition(RelaySiegeGame.MOON_CORE_POSITION), state.moonCoreHp, MOON, "MOON CORE");
    }

    private void drawRelay(Canvas canvas, float x, float y, int hp, int color, String label) {
        float radius = dp(14);
        fill.setColor(hp > 0 ? Color.argb(225, Color.red(color), Color.green(color), Color.blue(color))
                : Color.rgb(48, 52, 62));
        canvas.drawCircle(x, y, radius, fill);
        if (hp > 0) {
            drawHealthBar(canvas, x - dp(19), y - dp(23), dp(38), hp, RelaySiegeGame.RELAY_MAX_HP, color);
        }
        drawText(canvas, label, x, y + dp(4), BG, 8, true, Paint.Align.CENTER);
    }

    private void drawCore(Canvas canvas, float y, int hp, int color, String label) {
        float width = Math.min(getWidth() * 0.42f, dp(160));
        float left = (getWidth() - width) / 2f;
        RectF rect = new RectF(left, y - dp(8), left + width, y + dp(8));
        fill.setColor(Color.argb(185, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRoundRect(rect, dp(8), dp(8), fill);
        drawHealthBar(canvas, left, y - dp(16), width, hp, RelaySiegeGame.CORE_MAX_HP, color);
        drawText(canvas, label, getWidth() / 2f, y + dp(4), BG, 8, true, Paint.Align.CENTER);
    }

    private void drawEntities(Canvas canvas, RectF left, RectF right) {
        for (RelaySiegeNetworkState.Entity entity : state.entities) {
            RectF lane = entity.lane == RelaySiegeGame.Lane.LEFT ? left : right;
            float x = centerX(lane);
            float y = yForPosition(entity.position);
            int color = entity.owner == RelaySiegeGame.Player.SUN ? SUN : MOON;
            RelaySiegeCards.Card card = RelaySiegeCards.card(entity.cardId);
            float size = entity.building ? dp(16) : dp(card.count > 1 ? 10 : 13);

            fill.setColor(Color.argb(235, Color.red(color), Color.green(color), Color.blue(color)));
            if (entity.building) {
                canvas.drawRoundRect(new RectF(x - size, y - size, x + size, y + size), dp(5), dp(5), fill);
            } else {
                canvas.drawCircle(x, y, size, fill);
            }
            if (entity.airborne) {
                stroke.setColor(CYAN);
                stroke.setStrokeWidth(dp(1.5f));
                canvas.drawCircle(x, y, size + dp(3), stroke);
            }
            if (entity.slowTicks > 0) {
                stroke.setColor(CYAN);
                stroke.setStrokeWidth(dp(2));
                canvas.drawCircle(x, y, size + dp(5), stroke);
            }
            if (entity.overclockTicks > 0) {
                stroke.setColor(GREEN);
                stroke.setStrokeWidth(dp(2));
                canvas.drawCircle(x, y, size + dp(6), stroke);
            }
            drawText(canvas, shortName(card.name), x, y + dp(3), BG, 7, true, Paint.Align.CENTER);
            drawHealthBar(canvas, x - dp(15), y - size - dp(8), dp(30), entity.hp, entity.maxHp, color);
        }
    }

    private void drawCenter(Canvas canvas, RectF left, RectF right) {
        float y = yForPosition(50_000);
        drawText(canvas, "RELAY LINE", (left.left + right.right) / 2f, y - dp(7),
                Color.argb(150, 220, 231, 244), 8, true, Paint.Align.CENTER);
    }

    private void drawHealthBar(Canvas canvas, float x, float y, float width, int hp, int maxHp, int color) {
        float ratio = maxHp <= 0 ? 0f : Math.max(0f, Math.min(1f, hp / (float) maxHp));
        fill.setColor(Color.argb(150, 0, 0, 0));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + dp(3)), dp(2), dp(2), fill);
        fill.setColor(color);
        canvas.drawRoundRect(new RectF(x, y, x + width * ratio, y + dp(3)), dp(2), dp(2), fill);
    }

    private float yForPosition(int position) {
        float top = arenaTop();
        float bottom = arenaBottom();
        float ratio = Math.max(0f, Math.min(1f, position / 100_000f));
        if (state != null && state.perspective == RelaySiegeGame.Player.MOON)
            return top + ratio * (bottom - top);
        return bottom - ratio * (bottom - top);
    }

    private int worldPositionForY(float y) {
        float top = arenaTop();
        float bottom = arenaBottom();
        float ratio = Math.max(0f, Math.min(1f, (y - top) / Math.max(1f, bottom - top)));
        if (state != null && state.perspective == RelaySiegeGame.Player.MOON)
            return clamp(Math.round(ratio * 100_000f), 0, 100_000);
        return clamp(Math.round((1f - ratio) * 100_000f), 0, 100_000);
    }

    private float arenaTop() { return dp(26); }
    private float arenaBottom() { return Math.max(arenaTop() + dp(120), getHeight() - dp(22)); }
    private float centerX(RectF rect) { return (rect.left + rect.right) / 2f; }

    private void drawCentered(Canvas canvas, String text, float y, int color, float sp, boolean bold) {
        drawText(canvas, text, getWidth() / 2f, y, color, sp, bold, Paint.Align.CENTER);
    }

    private void drawText(Canvas canvas, String text, float x, float y, int color,
                          float sp, boolean bold, Paint.Align align) {
        fill.setColor(color);
        fill.setTextSize(sp(sp));
        fill.setFakeBoldText(bold);
        fill.setTextAlign(align);
        canvas.drawText(text, x, y, fill);
    }

    private static String shortName(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.split(" ");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
