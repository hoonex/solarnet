package com.hoonex.solarnet.gridduel;

public final class RelaySiegeAiSmoke {
    public static void main(String[] args) {
        lowFluxPrefersWaiting();
        hiddenOpponentDeckDoesNotChangeDecision();
        airThreatGetsAnAirCapableAnswer();
        structureHunterGetsARealBuildingPull();
        overclockTargetsFriendlySurvivors();
        aiVsAiMatchMakesProgressAndTerminates();
        System.out.println("Relay Siege AI smoke: PASS");
    }

    private static void lowFluxPrefersWaiting() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 91L);
        game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 1_000);
        RelaySiegeAi.Decision decision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
        require(decision.wait, "AI should bank Flux when no affordable positive trade exists: " + decision);
    }

    private static void hiddenOpponentDeckDoesNotChangeDecision() {
        RelaySiegeCards.Deck own = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame a = new RelaySiegeGame(own, RelaySiegeCards.deck("spark_cycle"), 92L);
        RelaySiegeGame b = new RelaySiegeGame(own, RelaySiegeCards.deck("split_voltage"), 92L);
        a.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
        b.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
        a.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 34_000);
        b.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 34_000);

        RelaySiegeAi.Decision da = RelaySiegeAi.choose(a, RelaySiegeGame.Player.SUN);
        RelaySiegeAi.Decision db = RelaySiegeAi.choose(b, RelaySiegeGame.Player.SUN);
        require(da.toString().equals(db.toString()),
                "AI decision must not depend on opponent hidden deck/hand: " + da + " vs " + db);
    }

    private static void airThreatGetsAnAirCapableAnswer() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 93L);
        game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
        game.scenarioSpawn("needlewing", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 31_000);

        RelaySiegeAi.Decision decision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
        require(!decision.wait, "urgent air pressure should produce a response");
        RelaySiegeCards.Card card = RelaySiegeCards.card(decision.cardId);
        require(card.attacksAir, "chosen answer must actually be able to hit air: " + decision);
        require(decision.lane == RelaySiegeGame.Lane.LEFT, "air answer belongs in threatened lane: " + decision);
    }

    private static void structureHunterGetsARealBuildingPull() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 94L);
        game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
        game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 34_000);

        RelaySiegeAi.Decision decision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
        require(!decision.wait, "structure pressure should produce a response");
        require(RelaySiegeCards.card(decision.cardId).kind == RelaySiegeCards.Kind.BUILDING,
                "AI should use a building to redirect a structure-only attacker: " + decision);
        RelaySiegeGame.PlayResult result = RelaySiegeAi.apply(game, RelaySiegeGame.Player.SUN, decision);
        require(result.accepted, "chosen building pull must be legal: " + result.reason);
    }

    private static void overclockTargetsFriendlySurvivors() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 95L);
        String[] opening = {"bulwark", "duelist", "pulse_guard", "relay_beacon"};
        for (int i = 0; i < opening.length; i++) {
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
            RelaySiegeGame.PlayResult result = game.tryPlay(
                    RelaySiegeGame.Player.SUN, opening[i], RelaySiegeGame.Lane.RIGHT, 26_000 + i * 700);
            require(result.accepted, "setup play must cycle legally: " + opening[i] + " / " + result.reason);
        }
        require(game.getHand(RelaySiegeGame.Player.SUN).contains("overclock"), "Overclock must be in cycled hand");

        game.scenarioSpawn("bulwark", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT, 60_000);
        game.scenarioSpawn("duelist", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT, 62_000);
        game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 5_000);

        RelaySiegeAi.Decision decision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
        require(!decision.wait && "overclock".equals(decision.cardId),
                "surviving forward HP should be converted with Overclock: " + decision);
        require(decision.lane == RelaySiegeGame.Lane.LEFT, "Overclock must target the survivor lane: " + decision);
        require(decision.position >= 60_000 && decision.position <= 62_000,
                "Overclock focus must come from friendly survivors, not enemy coordinates: " + decision.position);

        RelaySiegeGame.PlayResult result = RelaySiegeAi.apply(game, RelaySiegeGame.Player.SUN, decision);
        require(result.accepted, "Overclock decision must be legal: " + result.reason);
        int buffed = 0;
        for (RelaySiegeGame.EntityView entity : game.getEntities()) {
            if (entity.owner == RelaySiegeGame.Player.SUN
                    && entity.lane == RelaySiegeGame.Lane.LEFT
                    && !entity.building
                    && entity.overclockTicks > 0) buffed++;
        }
        require(buffed >= 2, "both forward survivors should receive the real spell effect");
    }

    private static void aiVsAiMatchMakesProgressAndTerminates() {
        RelaySiegeGame game = new RelaySiegeGame(
                RelaySiegeCards.deck("spark_cycle"),
                RelaySiegeCards.deck("split_voltage"),
                96L);
        int sunPlays = 0;
        int moonPlays = 0;

        while (!game.isFinished()) {
            RelaySiegeAi.Decision sun = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
            RelaySiegeAi.Decision moon = RelaySiegeAi.choose(game, RelaySiegeGame.Player.MOON);
            if (!sun.wait) {
                game.submitPlay(RelaySiegeGame.Player.SUN, sun.cardId, sun.lane, sun.position);
                sunPlays++;
            }
            if (!moon.wait) {
                game.submitPlay(RelaySiegeGame.Player.MOON, moon.cardId, moon.lane, moon.position);
                moonPlays++;
            }
            game.advanceTicks(5);
        }

        require(sunPlays >= 4 && moonPlays >= 4,
                "both AIs should actually play the game instead of waiting to the hard cap: " + sunPlays + "/" + moonPlays);
        require(game.getFluxSpentMilli(RelaySiegeGame.Player.SUN) > 0
                        && game.getFluxSpentMilli(RelaySiegeGame.Player.MOON) > 0,
                "both sides must spend real Flux");
        for (RelaySiegeGame.BattleEvent event : game.getEvents())
            require(!"PLAY_REJECTED".equals(event.type), "AI submitted an illegal action: " + event);
        require(game.getTick() <= RelaySiegeGame.MAX_MATCH_TICKS, "AI match must respect hard termination");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
