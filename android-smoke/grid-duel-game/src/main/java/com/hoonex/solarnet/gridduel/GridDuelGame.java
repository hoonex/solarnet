package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GridDuelGame {
    public static final int BOARD_SIZE = 5;
    public static final int MAX_HP = 3;

    public enum Player {
        SUN,
        MOON,
        NONE;

        public Player opponent() {
            return this == SUN ? MOON : this == MOON ? SUN : NONE;
        }
    }

    public static final class Outcome {
        public final boolean accepted;
        public final boolean attack;
        public final String message;
        public final Player winner;

        Outcome(boolean accepted, boolean attack, String message, Player winner) {
            this.accepted = accepted;
            this.attack = attack;
            this.message = message;
            this.winner = winner;
        }
    }

    private int turnIndex;
    private int sunX;
    private int sunY;
    private int moonX;
    private int moonY;
    private int sunHp;
    private int moonHp;
    private Player winner;

    public GridDuelGame() {
        reset();
    }

    public void reset() {
        turnIndex = 0;
        sunX = 0;
        sunY = 2;
        moonX = 4;
        moonY = 2;
        sunHp = MAX_HP;
        moonHp = MAX_HP;
        winner = Player.NONE;
    }

    public int getTurnIndex() { return turnIndex; }
    public int getSunX() { return sunX; }
    public int getSunY() { return sunY; }
    public int getMoonX() { return moonX; }
    public int getMoonY() { return moonY; }
    public int getSunHp() { return sunHp; }
    public int getMoonHp() { return moonHp; }
    public Player getWinner() { return winner; }

    public Player getCurrentPlayer() {
        if (winner != Player.NONE) return Player.NONE;
        return (turnIndex & 1) == 0 ? Player.SUN : Player.MOON;
    }

    public int getX(Player player) {
        return player == Player.SUN ? sunX : moonX;
    }

    public int getY(Player player) {
        return player == Player.SUN ? sunY : moonY;
    }

    public int getHp(Player player) {
        return player == Player.SUN ? sunHp : moonHp;
    }

    public boolean isOccupied(int x, int y) {
        return (sunX == x && sunY == y) || (moonX == x && moonY == y);
    }

    public Player playerAt(int x, int y) {
        if (sunX == x && sunY == y) return Player.SUN;
        if (moonX == x && moonY == y) return Player.MOON;
        return Player.NONE;
    }

    public Outcome tap(Player actor, int targetX, int targetY) {
        if (winner != Player.NONE)
            return new Outcome(false, false, "Match already finished", winner);
        if (actor == null || actor == Player.NONE || actor != getCurrentPlayer())
            return new Outcome(false, false, "Not this player's turn", winner);
        if (!inBounds(targetX, targetY))
            return new Outcome(false, false, "Target is outside the board", winner);

        int actorX = getX(actor);
        int actorY = getY(actor);
        int distance = Math.abs(targetX - actorX) + Math.abs(targetY - actorY);
        if (distance != 1)
            return new Outcome(false, false, "Move or attack exactly one orthogonal cell", winner);

        Player targetPlayer = playerAt(targetX, targetY);
        if (targetPlayer == actor)
            return new Outcome(false, false, "That cell contains your unit", winner);

        if (targetPlayer == actor.opponent()) {
            if (targetPlayer == Player.SUN) sunHp--;
            else moonHp--;
            turnIndex++;
            if (sunHp <= 0) winner = Player.MOON;
            if (moonHp <= 0) winner = Player.SUN;
            return new Outcome(true, true,
                    actor + " attacked " + targetPlayer,
                    winner);
        }

        if (actor == Player.SUN) {
            sunX = targetX;
            sunY = targetY;
        } else {
            moonX = targetX;
            moonY = targetY;
        }
        turnIndex++;
        return new Outcome(true, false, actor + " moved", winner);
    }

    public List<Integer> legalTargets(Player actor) {
        if (actor == null || actor == Player.NONE || actor != getCurrentPlayer())
            return Collections.emptyList();
        int x = getX(actor);
        int y = getY(actor);
        int[][] directions = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
        List<Integer> result = new ArrayList<>(4);
        for (int[] direction : directions) {
            int nx = x + direction[0];
            int ny = y + direction[1];
            if (!inBounds(nx, ny)) continue;
            Player occupant = playerAt(nx, ny);
            if (occupant == Player.NONE || occupant == actor.opponent())
                result.add(encodeCell(nx, ny));
        }
        return result;
    }

    public int chooseAiTarget(Player actor) {
        List<Integer> legal = legalTargets(actor);
        if (legal.isEmpty()) return -1;
        Player opponent = actor.opponent();
        int opponentX = getX(opponent);
        int opponentY = getY(opponent);

        for (int cell : legal) {
            int x = decodeX(cell);
            int y = decodeY(cell);
            if (playerAt(x, y) == opponent) return cell;
        }

        int bestCell = legal.get(0);
        int bestDistance = Integer.MAX_VALUE;
        for (int cell : legal) {
            int x = decodeX(cell);
            int y = decodeY(cell);
            int distance = Math.abs(opponentX - x) + Math.abs(opponentY - y);
            if (distance < bestDistance || (distance == bestDistance && cell < bestCell)) {
                bestDistance = distance;
                bestCell = cell;
            }
        }
        return bestCell;
    }

    public void restore(
            int turnIndex,
            int sunX,
            int sunY,
            int sunHp,
            int moonX,
            int moonY,
            int moonHp,
            Player winner) {
        if (turnIndex < 0) throw new IllegalArgumentException("turnIndex must be non-negative");
        if (!inBounds(sunX, sunY) || !inBounds(moonX, moonY))
            throw new IllegalArgumentException("player position outside board");
        if (sunX == moonX && sunY == moonY)
            throw new IllegalArgumentException("players cannot occupy the same cell");
        if (sunHp < 0 || sunHp > MAX_HP || moonHp < 0 || moonHp > MAX_HP)
            throw new IllegalArgumentException("HP outside valid range");
        if (winner == null) throw new IllegalArgumentException("winner is required");
        if (winner == Player.SUN && moonHp > 0)
            throw new IllegalArgumentException("SUN winner requires MOON at zero HP");
        if (winner == Player.MOON && sunHp > 0)
            throw new IllegalArgumentException("MOON winner requires SUN at zero HP");
        if (winner == Player.NONE && (sunHp <= 0 || moonHp <= 0))
            throw new IllegalArgumentException("zero HP requires a winner");

        this.turnIndex = turnIndex;
        this.sunX = sunX;
        this.sunY = sunY;
        this.sunHp = sunHp;
        this.moonX = moonX;
        this.moonY = moonY;
        this.moonHp = moonHp;
        this.winner = winner;
    }

    public static int encodeCell(int x, int y) {
        return y * BOARD_SIZE + x;
    }

    public static int decodeX(int cell) {
        return cell % BOARD_SIZE;
    }

    public static int decodeY(int cell) {
        return cell / BOARD_SIZE;
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < BOARD_SIZE && y < BOARD_SIZE;
    }
}
