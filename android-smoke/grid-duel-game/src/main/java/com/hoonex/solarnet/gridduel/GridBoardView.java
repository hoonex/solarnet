package com.hoonex.solarnet.gridduel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

public final class GridBoardView extends View {
    public interface Listener {
        void onCellTapped(int x, int y);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF board = new RectF();
    private final Set<Integer> legalTargets = new HashSet<>();
    private GridDuelGame game;
    private Listener listener;
    private boolean inputEnabled;

    public GridBoardView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public GridBoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void bind(GridDuelGame game) {
        this.game = game;
        refreshLegalTargets();
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        refreshLegalTargets();
        invalidate();
    }

    public void refresh() {
        refreshLegalTargets();
        invalidate();
    }

    private void refreshLegalTargets() {
        legalTargets.clear();
        if (!inputEnabled || game == null) return;
        legalTargets.addAll(game.legalTargets(game.getCurrentPlayer()));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desired = Math.max(300, width);
        int height = resolveSize(desired, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (game == null) return;

        float side = Math.min(getWidth(), getHeight()) - dp(24);
        float left = (getWidth() - side) * 0.5f;
        float top = (getHeight() - side) * 0.5f;
        board.set(left, top, left + side, top + side);

        paint.setShader(new LinearGradient(
                board.left, board.top, board.right, board.bottom,
                Color.rgb(28, 35, 52), Color.rgb(12, 15, 23), Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(12), 0, dp(5), 0x55000000);
        canvas.drawRoundRect(board, dp(24), dp(24), paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        float inset = dp(8);
        float gap = dp(5);
        float cell = (side - inset * 2 - gap * 4) / GridDuelGame.BOARD_SIZE;

        for (int y = 0; y < GridDuelGame.BOARD_SIZE; y++) {
            for (int x = 0; x < GridDuelGame.BOARD_SIZE; x++) {
                float cl = board.left + inset + x * (cell + gap);
                float ct = board.top + inset + y * (cell + gap);
                RectF rect = new RectF(cl, ct, cl + cell, ct + cell);
                int base = ((x + y) & 1) == 0 ? Color.rgb(25, 31, 45) : Color.rgb(29, 36, 52);
                paint.setColor(base);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, dp(12), dp(12), paint);

                int encoded = GridDuelGame.encodeCell(x, y);
                if (legalTargets.contains(encoded)) {
                    GridDuelGame.Player occupant = game.playerAt(x, y);
                    if (occupant == GridDuelGame.Player.NONE) {
                        paint.setColor(0xAA6EE7FF);
                        canvas.drawCircle(rect.centerX(), rect.centerY(), dp(5), paint);
                    } else {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(3));
                        paint.setColor(0xFFFF6B7A);
                        canvas.drawRoundRect(rect, dp(12), dp(12), paint);
                        paint.setStyle(Paint.Style.FILL);
                    }
                }
            }
        }

        drawPlayer(canvas, GridDuelGame.Player.SUN, cell, inset, gap);
        drawPlayer(canvas, GridDuelGame.Player.MOON, cell, inset, gap);
    }

    private void drawPlayer(
            Canvas canvas,
            GridDuelGame.Player player,
            float cell,
            float inset,
            float gap) {
        int x = game.getX(player);
        int y = game.getY(player);
        float cx = board.left + inset + x * (cell + gap) + cell * 0.5f;
        float cy = board.top + inset + y * (cell + gap) + cell * 0.5f;
        float radius = cell * 0.31f;

        boolean current = game.getCurrentPlayer() == player;
        if (current) {
            paint.setColor(player == GridDuelGame.Player.SUN ? 0x44FFB84D : 0x447C8CFF);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius * 1.42f, paint);
        }

        paint.setShader(new LinearGradient(
                cx - radius, cy - radius, cx + radius, cy + radius,
                player == GridDuelGame.Player.SUN ? Color.rgb(255, 220, 122) : Color.rgb(180, 190, 255),
                player == GridDuelGame.Player.SUN ? Color.rgb(255, 153, 47) : Color.rgb(89, 105, 255),
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(8), 0, dp(3), player == GridDuelGame.Player.SUN ? 0x55FFB84D : 0x557C8CFF);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(10, 12, 18));
        paint.setTextSize(radius * 0.72f);
        paint.setFakeBoldText(true);
        float baseline = cy - (paint.ascent() + paint.descent()) * 0.5f;
        canvas.drawText(player == GridDuelGame.Player.SUN ? "S" : "M", cx, baseline, paint);
        paint.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || !inputEnabled || game == null)
            return true;
        if (!board.contains(event.getX(), event.getY())) return true;

        float side = board.width();
        float inset = dp(8);
        float gap = dp(5);
        float cell = (side - inset * 2 - gap * 4) / GridDuelGame.BOARD_SIZE;
        float localX = event.getX() - board.left - inset;
        float localY = event.getY() - board.top - inset;
        int x = (int) (localX / (cell + gap));
        int y = (int) (localY / (cell + gap));
        if (x < 0 || y < 0 || x >= GridDuelGame.BOARD_SIZE || y >= GridDuelGame.BOARD_SIZE)
            return true;
        float withinX = localX - x * (cell + gap);
        float withinY = localY - y * (cell + gap);
        if (withinX > cell || withinY > cell) return true;

        if (listener != null) listener.onCellTapped(x, y);
        performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
