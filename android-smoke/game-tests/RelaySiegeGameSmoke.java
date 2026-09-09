package com.hoonex.solarnet.gridduel;

import java.util.List;

public final class RelaySiegeGameSmoke {
    public static void main(String[] args) {
        deckIdentityAndCycleAreStable();
        illegalDeploymentDoesNotConsumeResources();
        fixedStepReplayIsDeterministic();
        swarmPunishesSingleTargetDefender();
        splashPunishesSwarm();
        airChangesTargetingInteraction();
        defensiveBuildingRedirectsStructureHunter();
        matchAlwaysTerminates();
        deckGuidesUseRealSimulationFrames();
        System.out.println("Relay Siege foundation smoke: PASS");
    }

    private static void deckIdentityAndCycleAreStable() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("spark_cycle");
        require(deck.cardIds.size() == 8, "deck must have eight cards");
        require(deck.averageFlux() > 2.0 && deck.averageFlux() < 4.0, "cycle deck average cost should stay light");
        require(deck.fourCardCycleCost() <= 11, "cycle deck four-card floor should stay cheap");

        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 1L);
        require(game.getHand(RelaySiegeGame.Player.SUN).equals(deck.cardIds.subList(0, 4)), "opening hand follows canonical deck order");
        require(game.getNextCard(RelaySiegeGame.Player.SUN).equals(deck.cardIds.get(4)), "next card is visible and deterministic");

        RelaySiegeGame.PlayResult result = game.tryPlay(
                RelaySiegeGame.Player.SUN,
                "runner",
                RelaySiegeGame.Lane.LEFT,
                35_000);
        require(result.accepted, "opening Runner should be playable");
        require(game.getHand(RelaySiegeGame.Player.SUN).contains(deck.cardIds.get(4)), "played slot is replaced by next card");
        require(game.getNextCard(RelaySiegeGame.Player.SUN).equals(deck.cardIds.get(5)), "cycle advances exactly once");
    }

    private static void illegalDeploymentDoesNotConsumeResources() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("spark_cycle");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 2L);
        int beforeFlux = game.getFluxMilli(RelaySiegeGame.Player.SUN);
        List<String> beforeHand = game.getHand(RelaySiegeGame.Player.SUN);
        RelaySiegeGame.PlayResult result = game.tryPlay(
                RelaySiegeGame.Player.SUN,
                "runner",
                RelaySiegeGame.Lane.LEFT,
                80_000);
        require(!result.accepted && "INVALID_DEPLOYMENT_POSITION".equals(result.reason), "unit cannot start in enemy territory");
        require(game.getFluxMilli(RelaySiegeGame.Player.SUN) == beforeFlux, "rejected play cannot spend Flux");
        require(game.getHand(RelaySiegeGame.Player.SUN).equals(beforeHand), "rejected play cannot cycle hand");
    }

    private static void fixedStepReplayIsDeterministic() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("spark_cycle");
        RelaySiegeGame a = scriptedMirror(deck, 260909L);
        RelaySiegeGame b = scriptedMirror(deck, 260909L);
        require(a.canonicalState().equals(b.canonicalState()), "same seed/actions must produce byte-for-byte canonical state equality");
        require(a.getEvents().toString().equals(b.getEvents().toString()), "event timeline must replay deterministically");
    }

    private static RelaySiegeGame scriptedMirror(RelaySiegeCards.Deck deck, long seed) {
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, seed);
        game.schedulePlay(1, RelaySiegeGame.Player.SUN, "runner", RelaySiegeGame.Lane.RIGHT, 39_000);
        game.schedulePlay(1, RelaySiegeGame.Player.MOON, "runner", RelaySiegeGame.Lane.LEFT, 61_000);
        game.schedulePlay(25, RelaySiegeGame.Player.SUN, "spark_swarm", RelaySiegeGame.Lane.LEFT, 39_000);
        game.schedulePlay(25, RelaySiegeGame.Player.MOON, "spark_swarm", RelaySiegeGame.Lane.RIGHT, 61_000);
        game.advanceTicks(320);
        return game;
    }

    private static void swarmPunishesSingleTargetDefender() {
        RelaySiegeGame game = blankScenario(3L);
        game.scenarioSpawn("duelist", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000);
        for (int i = 0; i < 4; i++)
            game.scenarioSpawn("spark_swarm", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT, 47_000 + i * 120);
        game.advanceTicks(170);
        require(count(game, RelaySiegeGame.Player.MOON, "duelist") == 0,
                "four-body swarm should punish an isolated single-target Duelist");
        require(count(game, RelaySiegeGame.Player.SUN, "spark_swarm") > 0,
                "swarm interaction should leave at least one body for counter-pressure");
    }

    private static void splashPunishesSwarm() {
        RelaySiegeGame game = blankScenario(4L);
        game.scenarioSpawn("pulse_guard", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT, 47_000);
        for (int i = 0; i < 4; i++)
            game.scenarioSpawn("spark_swarm", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000 + i * 120);
        game.advanceTicks(180);
        require(count(game, RelaySiegeGame.Player.MOON, "spark_swarm") == 0,
                "Pulse Guard splash should erase a compact ground swarm");
        require(count(game, RelaySiegeGame.Player.SUN, "pulse_guard") > 0,
                "splash defender should retain some counterpush value in the intended interaction");
    }

    private static void airChangesTargetingInteraction() {
        RelaySiegeGame groundOnly = blankScenario(5L);
        groundOnly.scenarioSpawn("duelist", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 47_000);
        groundOnly.scenarioSpawn("needlewing", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 53_000);
        groundOnly.advanceTicks(180);
        require(count(groundOnly, RelaySiegeGame.Player.SUN, "duelist") == 0,
                "ground-only Duelist cannot answer Needlewing");

        RelaySiegeGame rangedCoverage = blankScenario(6L);
        rangedCoverage.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 47_000);
        rangedCoverage.scenarioSpawn("needlewing", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 53_000);
        rangedCoverage.advanceTicks(180);
        require(count(rangedCoverage, RelaySiegeGame.Player.MOON, "needlewing") == 0,
                "Arc Slinger should provide deliberate anti-air coverage");
    }

    private static void defensiveBuildingRedirectsStructureHunter() {
        RelaySiegeGame direct = blankScenario(7L);
        direct.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 50_000);
        direct.advanceTicks(430);
        int directRelayHp = direct.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT);

        RelaySiegeGame pulled = blankScenario(7L);
        pulled.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 50_000);
        pulled.scenarioSpawn("relay_beacon", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 33_000);
        pulled.advanceTicks(430);
        int pulledRelayHp = pulled.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT);

        require(pulledRelayHp > directRelayHp,
                "building placement should redirect a structure-hunter and preserve relay HP");
    }

    private static void matchAlwaysTerminates() {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        RelaySiegeGame game = new RelaySiegeGame(deck, deck, 8L);
        game.advanceTicks(RelaySiegeGame.MAX_MATCH_TICKS + 100);
        require(game.isFinished(), "empty/stalled match must terminate");
        require(game.getTick() <= RelaySiegeGame.MAX_MATCH_TICKS, "hard match cap must be respected");
        require(game.getEndReason() == RelaySiegeGame.EndReason.DRAW || game.getEndReason() == RelaySiegeGame.EndReason.TIME_TIEBREAK,
                "time cap resolves through explicit tiebreak/draw state");
    }

    private static void deckGuidesUseRealSimulationFrames() {
        require(RelaySiegeDeckGuide.guides().size() == RelaySiegeCards.starterDecks().size(),
                "every starter deck must ship with a guide");
        for (RelaySiegeDeckGuide.Guide guide : RelaySiegeDeckGuide.guides()) {
            require(guide.cardNotes.size() == 8, "guide must explain all eight cards: " + guide.deckId);
            require(!guide.demos.isEmpty(), "guide must include an embedded combat example: " + guide.deckId);
            require(guide.averageFlux() > 0 && guide.fourCardCycleCost() > 0, "guide exposes deck tempo metrics");
            for (RelaySiegeDeckGuide.DemoScenario demo : guide.demos) {
                RelaySiegeDeckGuide.DemoRun run = demo.run();
                require(run.frames.size() >= 4, "combat example must contain enough real simulation frames: " + demo.id);
                for (RelaySiegeDeckGuide.DemoAnnotation annotation : run.annotations)
                    require(!"REJECTED".equals(annotation.type), "guide script contains illegal card use: " + demo.id + " / " + annotation.text);
            }
        }
    }

    private static RelaySiegeGame blankScenario(long seed) {
        RelaySiegeCards.Deck deck = RelaySiegeCards.deck("counterforge");
        return new RelaySiegeGame(deck, deck, seed);
    }

    private static int count(RelaySiegeGame game, RelaySiegeGame.Player owner, String cardId) {
        int count = 0;
        for (RelaySiegeGame.EntityView entity : game.getEntities())
            if (entity.owner == owner && entity.cardId.equals(cardId)) count++;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
