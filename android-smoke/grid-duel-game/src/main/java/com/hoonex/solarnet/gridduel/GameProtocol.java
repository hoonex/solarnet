package com.hoonex.solarnet.gridduel;

import java.nio.charset.StandardCharsets;

public final class GameProtocol {
    private static final String PREFIX = "GD1";

    private GameProtocol() { }

    public static byte[] encodeReady() {
        return (PREFIX + "|READY").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeState(GridDuelGame game) {
        String text = PREFIX + "|STATE|" +
                game.getTurnIndex() + "|" +
                game.getSunX() + "|" + game.getSunY() + "|" + game.getSunHp() + "|" +
                game.getMoonX() + "|" + game.getMoonY() + "|" + game.getMoonHp() + "|" +
                game.getWinner().name();
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeAction(int turnIndex, int x, int y) {
        return (PREFIX + "|ACTION|" + turnIndex + "|" + x + "|" + y)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Frame decode(byte[] payload) {
        if (payload == null || payload.length == 0)
            throw new IllegalArgumentException("empty payload");
        String text = new String(payload, StandardCharsets.UTF_8);
        String[] parts = text.split("\\|", -1);
        if (parts.length < 2 || !PREFIX.equals(parts[0]))
            throw new IllegalArgumentException("not a Grid Duel frame");

        if ("READY".equals(parts[1])) {
            if (parts.length != 2) throw new IllegalArgumentException("invalid ready frame");
            return Frame.ready();
        }

        if ("ACTION".equals(parts[1])) {
            if (parts.length != 5) throw new IllegalArgumentException("invalid action frame");
            return Frame.action(parseInt(parts[2]), parseInt(parts[3]), parseInt(parts[4]));
        }

        if ("STATE".equals(parts[1])) {
            if (parts.length != 10) throw new IllegalArgumentException("invalid state frame");
            GridDuelGame.Player winner;
            try {
                winner = GridDuelGame.Player.valueOf(parts[9]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid winner", e);
            }
            return Frame.state(
                    parseInt(parts[2]),
                    parseInt(parts[3]), parseInt(parts[4]), parseInt(parts[5]),
                    parseInt(parts[6]), parseInt(parts[7]), parseInt(parts[8]),
                    winner);
        }

        throw new IllegalArgumentException("unknown frame type");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer", e);
        }
    }

    public static final class Frame {
        public enum Type { READY, ACTION, STATE }

        public final Type type;
        public final int turnIndex;
        public final int x;
        public final int y;
        public final int sunX;
        public final int sunY;
        public final int sunHp;
        public final int moonX;
        public final int moonY;
        public final int moonHp;
        public final GridDuelGame.Player winner;

        private Frame(
                Type type,
                int turnIndex,
                int x,
                int y,
                int sunX,
                int sunY,
                int sunHp,
                int moonX,
                int moonY,
                int moonHp,
                GridDuelGame.Player winner) {
            this.type = type;
            this.turnIndex = turnIndex;
            this.x = x;
            this.y = y;
            this.sunX = sunX;
            this.sunY = sunY;
            this.sunHp = sunHp;
            this.moonX = moonX;
            this.moonY = moonY;
            this.moonHp = moonHp;
            this.winner = winner;
        }

        static Frame ready() {
            return new Frame(Type.READY, 0, 0, 0, 0, 0, 0, 0, 0, 0, GridDuelGame.Player.NONE);
        }

        static Frame action(int turnIndex, int x, int y) {
            return new Frame(Type.ACTION, turnIndex, x, y, 0, 0, 0, 0, 0, 0, GridDuelGame.Player.NONE);
        }

        static Frame state(
                int turnIndex,
                int sunX,
                int sunY,
                int sunHp,
                int moonX,
                int moonY,
                int moonHp,
                GridDuelGame.Player winner) {
            return new Frame(Type.STATE, turnIndex, 0, 0, sunX, sunY, sunHp, moonX, moonY, moonHp, winner);
        }

        public void applyTo(GridDuelGame game) {
            if (type != Type.STATE) throw new IllegalStateException("not a state frame");
            game.restore(turnIndex, sunX, sunY, sunHp, moonX, moonY, moonHp, winner);
        }
    }
}
