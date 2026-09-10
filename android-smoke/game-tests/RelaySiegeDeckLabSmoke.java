package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RelaySiegeDeckLabSmoke {
    public static void main(String[] args) {
        orderedCustomDeckRoundTrips();
        duplicateCardsAreRejected();
        coverageWarningsReflectRealCardCapabilities();
        starterMatchupProbesAreDeterministic();
        System.out.println("Relay Siege Deck Lab smoke: PASS");
    }

    private static void orderedCustomDeckRoundTrips() {
        List<String> ids = Arrays.asList(
                "runner", "spark_swarm", "duelist", "needlewing",
                "siege_walker", "coil_turret", "gravity_well", "overclock");
        String encoded = RelaySiegeDeckLab.encode(ids);
        List<String> decoded = RelaySiegeDeckLab.decode(encoded);
        require(decoded.equals(ids), "Deck Lab codec must preserve exact slot order");
        RelaySiegeCards.Deck deck = RelaySiegeDeckLab.buildDeck("Lab Test", decoded);
        require(deck.cardIds.equals(ids), "custom deck object must preserve opening/cycle order");
        require("Lab Test".equals(deck.name), "custom deck name must survive");
    }

    private static void duplicateCardsAreRejected() {
        List<String> invalid = new ArrayList<>(Arrays.asList(
                "runner", "runner", "duelist", "needlewing",
                "siege_walker", "coil_turret", "gravity_well", "overclock"));
        boolean rejected = false;
        try {
            RelaySiegeDeckLab.validate(invalid);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Deck Lab must reject duplicate cards instead of hiding cycle ambiguity");
    }

    private static void coverageWarningsReflectRealCardCapabilities() {
        List<String> balanced = RelaySiegeCards.deck("counterforge").cardIds;
        RelaySiegeDeckLab.Analysis good = RelaySiegeDeckLab.analyze(balanced);
        require(good.winConditions > 0, "Counterforge has a win condition");
        require(good.dedicatedAntiAir > 0, "Counterforge has dedicated anti-air");
        require(good.splashAnswers > 0, "Counterforge has splash");
        require(good.defensiveBuildings > 0, "Counterforge has a building pull");

        List<String> fragile = Arrays.asList(
                "bulwark", "duelist", "spark_swarm", "pulse_guard",
                "runner", "gravity_well", "overclock", "siege_walker");
        RelaySiegeDeckLab.Analysis analysis = RelaySiegeDeckLab.analyze(fragile);
        require(analysis.dedicatedAntiAir == 0, "fragile deck deliberately lacks dedicated anti-air");
        require(contains(analysis.warnings, "대공"), "analysis should explain the anti-air hole");
        require(analysis.defensiveBuildings == 0, "fragile deck deliberately lacks a building");
        require(contains(analysis.warnings, "방어 건물"), "analysis should explain the building-pull hole");
    }

    private static void starterMatchupProbesAreDeterministic() {
        List<String> ids = RelaySiegeCards.deck("spark_cycle").cardIds;
        List<RelaySiegeDeckLab.MatchupProbe> a = RelaySiegeDeckLab.runStarterProbes(ids, 260910L);
        List<RelaySiegeDeckLab.MatchupProbe> b = RelaySiegeDeckLab.runStarterProbes(ids, 260910L);
        require(a.size() == 3 && b.size() == 3, "all starter archetypes must be probed");
        for (int i = 0; i < a.size(); i++) {
            RelaySiegeDeckLab.MatchupProbe left = a.get(i);
            RelaySiegeDeckLab.MatchupProbe right = b.get(i);
            require(left.opponentDeckId.equals(right.opponentDeckId), "probe opponent order deterministic");
            require(left.detail().equals(right.detail()), "same deck/seed must give identical real-engine matchup probe");
            require(left.sunPlays > 0 && left.moonPlays > 0, "probe must exercise both AIs, not just advance an empty clock");
        }
    }

    private static boolean contains(List<String> values, String fragment) {
        for (String value : values) if (value.contains(fragment)) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
