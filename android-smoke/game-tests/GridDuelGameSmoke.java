package com.hoonex.solarnet.gridduel;

public final class GridDuelGameSmoke {
    public static void main(String[] args) {
        basicTurnAndCombat();
        readyProtocolRoundTrip();
        stateProtocolRoundTrip();
        actionProtocolRoundTrip();
        aiMovesTowardOpponent();
        malformedFramesAreRejected();
        System.out.println("Grid Duel game smoke: PASS");
    }

    private static void basicTurnAndCombat() {
        GridDuelGame game = new GridDuelGame();
        require(game.getCurrentPlayer() == GridDuelGame.Player.SUN, "SUN must start");
        require(game.getSunX() == 0 && game.getSunY() == 2, "SUN start position");
        require(game.getMoonX() == 4 && game.getMoonY() == 2, "MOON start position");

        require(!game.tap(GridDuelGame.Player.SUN, 1, 3).accepted, "diagonal move must fail");
        require(game.getTurnIndex() == 0, "invalid move must not advance turn");

        require(game.tap(GridDuelGame.Player.SUN, 1, 2).accepted, "SUN move");
        require(game.tap(GridDuelGame.Player.MOON, 3, 2).accepted, "MOON move");
        require(game.tap(GridDuelGame.Player.SUN, 2, 2).accepted, "SUN closes distance");

        GridDuelGame.Outcome moonAttack1 = game.tap(GridDuelGame.Player.MOON, 2, 2);
        require(moonAttack1.accepted && moonAttack1.attack, "MOON first attack");
        require(game.getSunHp() == 2, "SUN loses one HP");

        GridDuelGame.Outcome sunAttack1 = game.tap(GridDuelGame.Player.SUN, 3, 2);
        require(sunAttack1.accepted && sunAttack1.attack, "SUN first attack");
        require(game.getMoonHp() == 2, "MOON loses one HP");

        game.tap(GridDuelGame.Player.MOON, 2, 2);
        game.tap(GridDuelGame.Player.SUN, 3, 2);
        GridDuelGame.Outcome finalMoonAttack = game.tap(GridDuelGame.Player.MOON, 2, 2);
        require(finalMoonAttack.winner == GridDuelGame.Player.MOON, "MOON should win on third hit");
        require(game.getSunHp() == 0, "SUN HP reaches zero");
        require(game.getCurrentPlayer() == GridDuelGame.Player.NONE, "finished match has no active player");
    }

    private static void readyProtocolRoundTrip() {
        GameProtocol.Frame ready = GameProtocol.decode(GameProtocol.encodeReady());
        require(ready.type == GameProtocol.Frame.Type.READY, "decoded ready type");
    }

    private static void stateProtocolRoundTrip() {
        GridDuelGame source = new GridDuelGame();
        source.tap(GridDuelGame.Player.SUN, 1, 2);
        source.tap(GridDuelGame.Player.MOON, 3, 2);

        GameProtocol.Frame frame = GameProtocol.decode(GameProtocol.encodeState(source));
        require(frame.type == GameProtocol.Frame.Type.STATE, "decoded state type");

        GridDuelGame restored = new GridDuelGame();
        frame.applyTo(restored);
        require(restored.getTurnIndex() == source.getTurnIndex(), "turn round trip");
        require(restored.getSunX() == source.getSunX(), "SUN x round trip");
        require(restored.getMoonX() == source.getMoonX(), "MOON x round trip");
        require(restored.getSunHp() == source.getSunHp(), "SUN hp round trip");
        require(restored.getMoonHp() == source.getMoonHp(), "MOON hp round trip");
    }

    private static void actionProtocolRoundTrip() {
        GameProtocol.Frame action = GameProtocol.decode(GameProtocol.encodeAction(7, 3, 4));
        require(action.type == GameProtocol.Frame.Type.ACTION, "decoded action type");
        require(action.turnIndex == 7, "action turn");
        require(action.x == 3 && action.y == 4, "action target");
    }

    private static void aiMovesTowardOpponent() {
        GridDuelGame game = new GridDuelGame();
        game.tap(GridDuelGame.Player.SUN, 1, 2);
        int target = game.chooseAiTarget(GridDuelGame.Player.MOON);
        require(target >= 0, "AI needs a legal target");
        int x = GridDuelGame.decodeX(target);
        int y = GridDuelGame.decodeY(target);
        int before = Math.abs(game.getMoonX() - game.getSunX()) + Math.abs(game.getMoonY() - game.getSunY());
        int after = Math.abs(x - game.getSunX()) + Math.abs(y - game.getSunY());
        require(after < before, "AI should close Manhattan distance when no attack exists");
    }

    private static void malformedFramesAreRejected() {
        boolean rejected = false;
        try {
            GameProtocol.decode("GD1|STATE|broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "malformed state must be rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
