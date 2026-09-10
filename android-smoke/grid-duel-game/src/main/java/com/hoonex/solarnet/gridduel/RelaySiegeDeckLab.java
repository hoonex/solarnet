package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure-Java deck construction, analysis and matchup-probe owner for Relay Siege. */
public final class RelaySiegeDeckLab {
    public static final int DECK_SIZE = 8;
    public static final int PROBE_TICKS = 900;
    public static final int AI_DECISION_INTERVAL_TICKS = 5;

    public static final class Analysis {
        public final double averageFlux;
        public final int fourCardCycleCost;
        public final int winConditions;
        public final int dedicatedAntiAir;
        public final int splashAnswers;
        public final int defensiveBuildings;
        public final int controlCards;
        public final int cheapCycleCards;
        public final int airThreats;
        public final int durableFrontliners;
        public final int pressureScore;
        public final int defenseScore;
        public final int cycleScore;
        public final int airCoverageScore;
        public final List<String> strengths;
        public final List<String> warnings;

        private Analysis(
                double averageFlux,
                int fourCardCycleCost,
                int winConditions,
                int dedicatedAntiAir,
                int splashAnswers,
                int defensiveBuildings,
                int controlCards,
                int cheapCycleCards,
                int airThreats,
                int durableFrontliners,
                int pressureScore,
                int defenseScore,
                int cycleScore,
                int airCoverageScore,
                List<String> strengths,
                List<String> warnings) {
            this.averageFlux = averageFlux;
            this.fourCardCycleCost = fourCardCycleCost;
            this.winConditions = winConditions;
            this.dedicatedAntiAir = dedicatedAntiAir;
            this.splashAnswers = splashAnswers;
            this.defensiveBuildings = defensiveBuildings;
            this.controlCards = controlCards;
            this.cheapCycleCards = cheapCycleCards;
            this.airThreats = airThreats;
            this.durableFrontliners = durableFrontliners;
            this.pressureScore = pressureScore;
            this.defenseScore = defenseScore;
            this.cycleScore = cycleScore;
            this.airCoverageScore = airCoverageScore;
            this.strengths = Collections.unmodifiableList(new ArrayList<>(strengths));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public String compactSummary() {
            return String.format(Locale.US,
                    "AVG %.1f  ·  4-CARD %d  ·  PRESS %d/5  ·  DEF %d/5  ·  CYCLE %d/5  ·  AIR %d/5",
                    averageFlux, fourCardCycleCost, pressureScore, defenseScore, cycleScore, airCoverageScore);
        }
    }

    public static final class MatchupProbe {
        public final String opponentDeckId;
        public final String opponentDeckName;
        public final RelaySiegeGame.Player winner;
        public final RelaySiegeGame.EndReason endReason;
        public final int ticks;
        public final int sunRelayHp;
        public final int moonRelayHp;
        public final int sunCoreHp;
        public final int moonCoreHp;
        public final int sunPlays;
        public final int moonPlays;

        private MatchupProbe(
                RelaySiegeCards.Deck opponent,
                RelaySiegeGame game,
                int sunPlays,
                int moonPlays) {
            this.opponentDeckId = opponent.id;
            this.opponentDeckName = opponent.name;
            this.winner = game.getWinner();
            this.endReason = game.getEndReason();
            this.ticks = game.getTick();
            this.sunRelayHp = game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT)
                    + game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT);
            this.moonRelayHp = game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT)
                    + game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT);
            this.sunCoreHp = game.getCoreHp(RelaySiegeGame.Player.SUN);
            this.moonCoreHp = game.getCoreHp(RelaySiegeGame.Player.MOON);
            this.sunPlays = sunPlays;
            this.moonPlays = moonPlays;
        }

        public String resultLabel() {
            if (winner == RelaySiegeGame.Player.SUN) return "WIN";
            if (winner == RelaySiegeGame.Player.MOON) return "LOSS";
            return "DRAW";
        }

        public String detail() {
            return String.format(Locale.US,
                    "%s · %s · %.1fs · Relay %d:%d · Core %d:%d · Plays %d:%d",
                    resultLabel(), endReason, ticks / 10f,
                    sunRelayHp, moonRelayHp, sunCoreHp, moonCoreHp, sunPlays, moonPlays);
        }
    }

    private RelaySiegeDeckLab() { }

    public static RelaySiegeCards.Deck buildDeck(String name, List<String> orderedCardIds) {
        validate(orderedCardIds);
        return RelaySiegeCards.customDeck(
                "custom_lab",
                name == null || name.trim().isEmpty() ? "Custom Lab" : name.trim(),
                orderedCardIds);
    }

    public static void validate(List<String> orderedCardIds) {
        if (orderedCardIds == null || orderedCardIds.size() != DECK_SIZE)
            throw new IllegalArgumentException("Relay Siege custom decks require exactly 8 cards");
        Set<String> unique = new HashSet<>();
        for (String id : orderedCardIds) {
            RelaySiegeCards.card(id);
            if (!unique.add(id)) throw new IllegalArgumentException("Duplicate card in deck: " + id);
        }
    }

    public static String encode(List<String> orderedCardIds) {
        validate(orderedCardIds);
        return String.join(",", orderedCardIds);
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty())
            throw new IllegalArgumentException("Encoded deck is empty");
        String[] parts = encoded.split(",", -1);
        List<String> ids = new ArrayList<>();
        for (String part : parts) ids.add(part.trim());
        validate(ids);
        return Collections.unmodifiableList(ids);
    }

    public static Analysis analyze(List<String> orderedCardIds) {
        RelaySiegeCards.Deck deck = buildDeck("Analysis", orderedCardIds);
        int win = 0;
        int antiAir = 0;
        int splash = 0;
        int buildings = 0;
        int control = 0;
        int cheap = 0;
        int air = 0;
        int frontline = 0;
        int pressurePieces = 0;

        for (String id : orderedCardIds) {
            RelaySiegeCards.Card card = RelaySiegeCards.card(id);
            if (card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION) win++;
            if (card.attacksAir && card.kind != RelaySiegeCards.Kind.SPELL) antiAir++;
            if (card.splashRadius > 0 || "gravity_well".equals(card.id)) splash++;
            if (card.kind == RelaySiegeCards.Kind.BUILDING) buildings++;
            if (card.primaryRole == RelaySiegeCards.Role.CONTROL) control++;
            if (card.fluxCost <= 2) cheap++;
            if (card.airborne) air++;
            if (card.hp >= 900 || card.primaryRole == RelaySiegeCards.Role.TANK) frontline++;
            if (card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION
                    || card.primaryRole == RelaySiegeCards.Role.TEMPO
                    || card.primaryRole == RelaySiegeCards.Role.AIR) pressurePieces++;
        }

        List<String> strengths = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        double avg = deck.averageFlux();
        int cycle = deck.fourCardCycleCost();

        if (win > 0) strengths.add("명확한 Relay 압박 수단이 있음");
        else warnings.add("승리조건 카드가 없어 상대 Relay를 안정적으로 압박하기 어려움");

        if (antiAir >= 2) strengths.add("대공 커버리지가 넉넉함");
        else if (antiAir == 1) warnings.add("전용 대공이 1장뿐이라 순환이 꼬이면 공중 압박에 취약함");
        else warnings.add("전용 대공 유닛/건물이 없음");

        if (splash >= 2) strengths.add("스웜/다수 유닛 대응 수단이 충분함");
        else if (splash == 0) warnings.add("광역 대응이 없어 Spark Swarm류에 손해 보기 쉬움");

        if (buildings > 0) strengths.add("공성 유닛을 건물로 유인할 수 있음");
        else warnings.add("방어 건물이 없어 Siege Walker 경로를 강제로 틀기 어려움");

        if (avg <= 3.25 && cycle <= 11) strengths.add("빠른 순환으로 핵심 카드를 다시 잡기 쉬움");
        if (avg >= 4.1 || cycle >= 15) warnings.add("덱이 무거워 반대 라인 압박과 재순환 대응이 느릴 수 있음");
        if (frontline == 0) warnings.add("튼튼한 전열이 없어 원거리/생존 유닛 역공을 만들기 어려움");
        if (pressurePieces >= 3) strengths.add("라인 전환과 역압박 옵션이 많음");
        if (control > 0) strengths.add("전투 타이밍을 바꾸는 제어 카드가 있음");

        int pressureScore = clampScore(1 + win * 2 + pressurePieces / 2 + air);
        int defenseScore = clampScore(1 + buildings * 2 + splash + antiAir + frontline);
        int cycleScore = clampScore(cycle <= 10 ? 5 : cycle <= 12 ? 4 : cycle <= 14 ? 3 : cycle <= 16 ? 2 : 1);
        int airScore = clampScore(antiAir + (control > 0 ? 1 : 0) + (air > 0 ? 1 : 0));

        return new Analysis(avg, cycle, win, antiAir, splash, buildings, control, cheap, air, frontline,
                pressureScore, defenseScore, cycleScore, airScore, strengths, warnings);
    }

    public static List<MatchupProbe> runStarterProbes(List<String> orderedCardIds, long seedBase) {
        RelaySiegeCards.Deck custom = buildDeck("Custom Lab", orderedCardIds);
        List<MatchupProbe> out = new ArrayList<>();
        int index = 0;
        for (RelaySiegeCards.Deck opponent : RelaySiegeCards.starterDecks()) {
            out.add(runProbe(custom, opponent, seedBase + index * 1009L));
            index++;
        }
        return Collections.unmodifiableList(out);
    }

    public static MatchupProbe runProbe(RelaySiegeCards.Deck custom, RelaySiegeCards.Deck opponent, long seed) {
        RelaySiegeGame game = new RelaySiegeGame(custom, opponent, seed);
        int sunPlays = 0;
        int moonPlays = 0;

        while (!game.isFinished() && game.getTick() < PROBE_TICKS) {
            if (game.getTick() % AI_DECISION_INTERVAL_TICKS == 0) {
                RelaySiegeAi.Decision sunDecision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.SUN);
                RelaySiegeAi.Decision moonDecision = RelaySiegeAi.choose(game, RelaySiegeGame.Player.MOON);
                boolean sunFirst = ((game.getTick() / AI_DECISION_INTERVAL_TICKS) & 1) == 0;
                if (sunFirst) {
                    if (apply(game, RelaySiegeGame.Player.SUN, sunDecision)) sunPlays++;
                    if (apply(game, RelaySiegeGame.Player.MOON, moonDecision)) moonPlays++;
                } else {
                    if (apply(game, RelaySiegeGame.Player.MOON, moonDecision)) moonPlays++;
                    if (apply(game, RelaySiegeGame.Player.SUN, sunDecision)) sunPlays++;
                }
            }
            game.advanceTicks(1);
        }
        return new MatchupProbe(opponent, game, sunPlays, moonPlays);
    }

    public static Map<RelaySiegeCards.Role, Integer> roleHistogram(List<String> orderedCardIds) {
        validate(orderedCardIds);
        Map<RelaySiegeCards.Role, Integer> map = new LinkedHashMap<>();
        for (RelaySiegeCards.Role role : RelaySiegeCards.Role.values()) map.put(role, 0);
        for (String id : orderedCardIds) {
            RelaySiegeCards.Role role = RelaySiegeCards.card(id).primaryRole;
            map.put(role, map.get(role) + 1);
        }
        return Collections.unmodifiableMap(map);
    }

    private static boolean apply(RelaySiegeGame game, RelaySiegeGame.Player player, RelaySiegeAi.Decision decision) {
        if (decision == null || decision.wait) return false;
        return RelaySiegeAi.apply(game, player, decision).accepted;
    }

    private static int clampScore(int value) {
        return Math.max(1, Math.min(5, value));
    }
}
