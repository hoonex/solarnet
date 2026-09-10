package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.List;

public final class RelaySiegeCycleLabSmoke {
    public static void main(String[] args) {
        exactSlotDistanceMatchesRealGameCycle();
        routesAreDeterministicAndEndWithTargetReady();
        customOrderChangesRecoveryWindow();
        System.out.println("Relay Siege Cycle Lab smoke: PASS");
    }

    private static void exactSlotDistanceMatchesRealGameCycle() {
        for (RelaySiegeCards.Deck deck : RelaySiegeCards.starterDecks()) {
            for (int index = 0; index < deck.cardIds.size(); index++) {
                String target = deck.cardIds.get(index);
                RelaySiegeCycleLab.Route route = RelaySiegeCycleLab.analyzeTarget(deck.cardIds, target);
                require(route.finalHand.contains(target), deck.id + " target must end ready: " + target);
                if (index < 4) {
                    require(route.mode == RelaySiegeCycleLab.Mode.RECOVER_AFTER_SPEND,
                            deck.id + " opening card must use recovery mode: " + target);
                    require(route.cycleCardsAfterSpend == 4 + index,
                            deck.id + " slot " + (index + 1) + " expected " + (4 + index)
                                    + " cycle plays after spend, got " + route.cycleCardsAfterSpend);
                    require(!route.steps.get(0).handAfter.contains(target),
                            deck.id + " spent target must actually leave hand before returning: " + target);
                } else {
                    require(route.mode == RelaySiegeCycleLab.Mode.DRAW_IN,
                            deck.id + " future slot must use draw-in mode: " + target);
                    require(route.cardsToReady == index - 3,
                            deck.id + " slot " + (index + 1) + " expected draw in " + (index - 3)
                                    + " plays, got " + route.cardsToReady);
                }
            }
        }
    }

    private static void routesAreDeterministicAndEndWithTargetReady() {
        for (RelaySiegeCards.Deck deck : RelaySiegeCards.starterDecks()) {
            List<RelaySiegeCycleLab.Route> a = RelaySiegeCycleLab.analyze(deck.cardIds);
            List<RelaySiegeCycleLab.Route> b = RelaySiegeCycleLab.analyze(deck.cardIds);
            require(a.size() == 8 && b.size() == 8, "all eight deck slots need routes: " + deck.id);
            for (int i = 0; i < a.size(); i++) {
                RelaySiegeCycleLab.Route left = a.get(i);
                RelaySiegeCycleLab.Route right = b.get(i);
                require(left.targetCardId.equals(right.targetCardId), "target deterministic: " + deck.id);
                require(left.sequenceLabel().equals(right.sequenceLabel()), "sequence deterministic: " + deck.id);
                require(left.fluxToReadyMilli == right.fluxToReadyMilli, "Flux deterministic: " + deck.id);
                require(left.ticksToReady == right.ticksToReady, "ticks deterministic: " + deck.id);
                require(left.stateDigest.equals(right.stateDigest), "state deterministic: " + deck.id);
                require(left.finalHand.contains(left.targetCardId), "route ends when target is ready: " + deck.id);
            }
        }
    }

    private static void customOrderChangesRecoveryWindow() {
        List<String> order = new ArrayList<>(RelaySiegeCards.deck("spark_cycle").cardIds);
        RelaySiegeCycleLab.Route slotOne = RelaySiegeCycleLab.analyzeTarget(order, order.get(0));
        RelaySiegeCycleLab.Route slotFour = RelaySiegeCycleLab.analyzeTarget(order, order.get(3));
        require(slotOne.cycleCardsAfterSpend == 4, "slot 1 recovery window");
        require(slotFour.cycleCardsAfterSpend == 7, "slot 4 recovery window");
        require(slotOne.cycleCardsAfterSpend < slotFour.cycleCardsAfterSpend,
                "opening slot order must materially change return distance");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
