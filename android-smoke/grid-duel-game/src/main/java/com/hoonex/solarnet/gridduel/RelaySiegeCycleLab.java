package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Exact-hand cycle analysis backed by RelaySiegeGame itself.
 *
 * Card-to-hand distance is measured by replaying legal plays through the real hand/next-card
 * implementation. Flux/timing numbers use a deterministic cheapest-first cycling policy; they are
 * not advertised as a global optimum.
 */
public final class RelaySiegeCycleLab {
    public enum Mode { DRAW_IN, RECOVER_AFTER_SPEND }

    public static final class Step {
        public final String cardId;
        public final int waitTicks;
        public final int tickAfter;
        public final int fluxAfterMilli;
        public final List<String> handAfter;
        public final String nextCardAfter;

        private Step(
                String cardId,
                int waitTicks,
                int tickAfter,
                int fluxAfterMilli,
                List<String> handAfter,
                String nextCardAfter) {
            this.cardId = cardId;
            this.waitTicks = waitTicks;
            this.tickAfter = tickAfter;
            this.fluxAfterMilli = fluxAfterMilli;
            this.handAfter = Collections.unmodifiableList(new ArrayList<>(handAfter));
            this.nextCardAfter = nextCardAfter;
        }
    }

    public static final class Route {
        public final String targetCardId;
        public final Mode mode;
        public final List<String> openingHand;
        public final String openingNextCard;
        public final int openingFluxMilli;
        public final List<Step> steps;
        public final int cardsToReady;
        public final int cycleCardsAfterSpend;
        public final int fluxToReadyMilli;
        public final int extraCycleFluxMilli;
        public final int ticksToReady;
        public final int ticksAfterSpend;
        public final List<String> finalHand;
        public final String finalNextCard;
        public final String stateDigest;

        private Route(
                String targetCardId,
                Mode mode,
                List<String> openingHand,
                String openingNextCard,
                int openingFluxMilli,
                List<Step> steps,
                int cardsToReady,
                int cycleCardsAfterSpend,
                int fluxToReadyMilli,
                int extraCycleFluxMilli,
                int ticksToReady,
                int ticksAfterSpend,
                List<String> finalHand,
                String finalNextCard,
                String stateDigest) {
            this.targetCardId = targetCardId;
            this.mode = mode;
            this.openingHand = Collections.unmodifiableList(new ArrayList<>(openingHand));
            this.openingNextCard = openingNextCard;
            this.openingFluxMilli = openingFluxMilli;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.cardsToReady = cardsToReady;
            this.cycleCardsAfterSpend = cycleCardsAfterSpend;
            this.fluxToReadyMilli = fluxToReadyMilli;
            this.extraCycleFluxMilli = extraCycleFluxMilli;
            this.ticksToReady = ticksToReady;
            this.ticksAfterSpend = ticksAfterSpend;
            this.finalHand = Collections.unmodifiableList(new ArrayList<>(finalHand));
            this.finalNextCard = finalNextCard;
            this.stateDigest = stateDigest;
        }

        public String sequenceLabel() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) out.append(" → ");
                out.append(RelaySiegeCards.card(steps.get(i).cardId).name);
            }
            return out.toString();
        }

        public String compact() {
            if (mode == Mode.RECOVER_AFTER_SPEND) {
                return String.format(Locale.US,
                        "+%d plays · +%.1fF · %.1fs",
                        cycleCardsAfterSpend,
                        extraCycleFluxMilli / 1000f,
                        ticksAfterSpend / 10f);
            }
            return String.format(Locale.US,
                    "%d plays · %.1fF · %.1fs",
                    cardsToReady,
                    fluxToReadyMilli / 1000f,
                    ticksToReady / 10f);
        }
    }

    private static final int MAX_ROUTE_PLAYS = 12;
    private static final int MAX_WAIT_TICKS = 1_200;
    private static final int SAFE_DEPLOY_POSITION = 6_000;

    private RelaySiegeCycleLab() { }

    public static List<Route> analyze(List<String> orderedCardIds) {
        RelaySiegeDeckLab.validate(orderedCardIds);
        List<Route> routes = new ArrayList<>();
        for (String cardId : orderedCardIds) routes.add(analyzeTarget(orderedCardIds, cardId));
        return Collections.unmodifiableList(routes);
    }

    public static Route analyzeTarget(List<String> orderedCardIds, String targetCardId) {
        RelaySiegeDeckLab.validate(orderedCardIds);
        if (!orderedCardIds.contains(targetCardId))
            throw new IllegalArgumentException("Target is not in deck: " + targetCardId);

        RelaySiegeCards.Deck deck = RelaySiegeDeckLab.buildDeck("Cycle Lab", orderedCardIds);
        RelaySiegeGame game = new RelaySiegeGame(deck, RelaySiegeCards.deck("counterforge"),
                61_000L + orderedCardIds.indexOf(targetCardId));
        List<String> openingHand = game.getHand(RelaySiegeGame.Player.SUN);
        String openingNext = game.getNextCard(RelaySiegeGame.Player.SUN);
        int openingFlux = game.getFluxMilli(RelaySiegeGame.Player.SUN);
        int spentAtStart = game.getFluxSpentMilli(RelaySiegeGame.Player.SUN);
        boolean initiallyInHand = openingHand.contains(targetCardId);
        Mode mode = initiallyInHand ? Mode.RECOVER_AFTER_SPEND : Mode.DRAW_IN;
        List<Step> steps = new ArrayList<>();

        int tickAfterTargetSpend = 0;
        int spentAfterTarget = spentAtStart;
        if (initiallyInHand) {
            play(game, targetCardId, steps);
            tickAfterTargetSpend = game.getTick();
            spentAfterTarget = game.getFluxSpentMilli(RelaySiegeGame.Player.SUN);
        }

        while (!game.getHand(RelaySiegeGame.Player.SUN).contains(targetCardId)) {
            if (steps.size() >= MAX_ROUTE_PLAYS)
                throw new IllegalStateException("Cycle route exceeded bounded play count for " + targetCardId);
            String next = cheapestCard(game.getHand(RelaySiegeGame.Player.SUN));
            play(game, next, steps);
        }

        int totalSpent = game.getFluxSpentMilli(RelaySiegeGame.Player.SUN) - spentAtStart;
        int totalTicks = game.getTick();
        int cardsToReady = initiallyInHand ? Math.max(0, steps.size() - 1) : steps.size();
        int afterSpendTicks = initiallyInHand ? game.getTick() - tickAfterTargetSpend : totalTicks;
        int extraFlux = initiallyInHand
                ? game.getFluxSpentMilli(RelaySiegeGame.Player.SUN) - spentAfterTarget
                : totalSpent;

        return new Route(
                targetCardId,
                mode,
                openingHand,
                openingNext,
                openingFlux,
                steps,
                cardsToReady,
                initiallyInHand ? cardsToReady : 0,
                totalSpent,
                extraFlux,
                totalTicks,
                afterSpendTicks,
                game.getHand(RelaySiegeGame.Player.SUN),
                game.getNextCard(RelaySiegeGame.Player.SUN),
                game.canonicalState());
    }

    private static String cheapestCard(List<String> hand) {
        if (hand == null || hand.isEmpty()) throw new IllegalStateException("Relay Siege hand is empty");
        String best = hand.get(0);
        int bestCost = RelaySiegeCards.card(best).fluxCost;
        for (int i = 1; i < hand.size(); i++) {
            String candidate = hand.get(i);
            int cost = RelaySiegeCards.card(candidate).fluxCost;
            if (cost < bestCost) {
                best = candidate;
                bestCost = cost;
            }
        }
        return best;
    }

    private static void play(RelaySiegeGame game, String cardId, List<Step> steps) {
        int costMilli = RelaySiegeCards.card(cardId).fluxCost * 1_000;
        int waitTicks = 0;
        while (game.getFluxMilli(RelaySiegeGame.Player.SUN) < costMilli && !game.isFinished()) {
            if (waitTicks >= MAX_WAIT_TICKS)
                throw new IllegalStateException("Flux wait exceeded bound for " + cardId);
            game.advanceTicks(1);
            waitTicks++;
        }
        if (game.isFinished()) throw new IllegalStateException("Match ended during cycle route");

        RelaySiegeGame.Lane lane = (steps.size() & 1) == 0
                ? RelaySiegeGame.Lane.LEFT
                : RelaySiegeGame.Lane.RIGHT;
        RelaySiegeGame.PlayResult result = game.tryPlay(
                RelaySiegeGame.Player.SUN,
                cardId,
                lane,
                SAFE_DEPLOY_POSITION);
        if (!result.accepted)
            throw new IllegalStateException("Cycle Lab produced illegal play " + cardId + ": " + result.reason);

        // Keep a real fixed-step boundary between decisions instead of stacking multiple UI plays at one tick.
        game.advanceTicks(1);
        steps.add(new Step(
                cardId,
                waitTicks,
                game.getTick(),
                game.getFluxMilli(RelaySiegeGame.Player.SUN),
                game.getHand(RelaySiegeGame.Player.SUN),
                game.getNextCard(RelaySiegeGame.Player.SUN)));
    }
}
