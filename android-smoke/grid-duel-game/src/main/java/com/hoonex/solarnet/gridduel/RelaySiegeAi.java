package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic information-fair AI for Relay Siege.
 *
 * It reads only public battlefield/objective/Flux state plus its own hand. It does not inspect the
 * opponent's hidden hand or next card. Waiting is an explicit option; cheap cards are not rewarded
 * merely for being cheap unless they answer or create a concrete tactical obligation.
 */
public final class RelaySiegeAi {
    public static final class Decision {
        public final boolean wait;
        public final String cardId;
        public final RelaySiegeGame.Lane lane;
        public final int position;
        public final int score;
        public final String reason;

        private Decision(boolean wait, String cardId, RelaySiegeGame.Lane lane, int position, int score, String reason) {
            this.wait = wait;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
            this.score = score;
            this.reason = reason;
        }

        static Decision waitFor(int score, String reason) {
            return new Decision(true, null, null, 0, score, reason);
        }

        static Decision play(String cardId, RelaySiegeGame.Lane lane, int position, int score, String reason) {
            return new Decision(false, cardId, lane, position, score, reason);
        }

        @Override public String toString() {
            return wait ? "WAIT(" + score + "," + reason + ")"
                    : cardId + "@" + lane + ":" + position + "(" + score + "," + reason + ")";
        }
    }

    private static final class LaneState {
        int enemyCount;
        int enemyAir;
        int enemyGround;
        int enemyStructureHunters;
        int enemyHp;
        int enemyDamagePressure;
        int friendlyCount;
        int friendlyHp;
        int friendlyForwardHp;
        int friendlyAir;
        int friendlyGround;
        int nearestEnemyToOwnRelay = Integer.MAX_VALUE;
        int nearestFriendlyToEnemyRelay = Integer.MAX_VALUE;
    }

    private RelaySiegeAi() { }

    public static Decision choose(RelaySiegeGame game, RelaySiegeGame.Player player) {
        if (game == null || game.isFinished() || player == null || player == RelaySiegeGame.Player.NONE)
            return Decision.waitFor(Integer.MAX_VALUE, "inactive");

        LaneState left = analyzeLane(game, player, RelaySiegeGame.Lane.LEFT);
        LaneState right = analyzeLane(game, player, RelaySiegeGame.Lane.RIGHT);
        int flux = game.getFluxMilli(player);
        int waitScore = scoreWait(game, player, left, right, flux);
        Decision best = Decision.waitFor(waitScore, waitReason(left, right, flux));

        List<String> hand = game.getHand(player);
        for (int handIndex = 0; handIndex < hand.size(); handIndex++) {
            String cardId = hand.get(handIndex);
            RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
            if (flux < card.fluxCost * 1_000) continue;

            for (RelaySiegeGame.Lane lane : RelaySiegeGame.Lane.values()) {
                LaneState state = lane == RelaySiegeGame.Lane.LEFT ? left : right;
                for (int position : candidatePositions(game, player, card, lane, state)) {
                    int score = scoreCard(game, player, card, lane, position, state,
                            lane == RelaySiegeGame.Lane.LEFT ? right : left, handIndex);
                    String reason = explain(card, state, lane == RelaySiegeGame.Lane.LEFT ? right : left, game, player);
                    Decision candidate = Decision.play(card.id, lane, position, score, reason);
                    if (better(candidate, best, game, player, handIndex)) best = candidate;
                }
            }
        }
        return best;
    }

    public static RelaySiegeGame.PlayResult apply(RelaySiegeGame game, RelaySiegeGame.Player player, Decision decision) {
        if (decision == null || decision.wait) return RelaySiegeGame.PlayResult.reject("WAIT");
        return game.tryPlay(player, decision.cardId, decision.lane, decision.position);
    }

    private static LaneState analyzeLane(RelaySiegeGame game, RelaySiegeGame.Player player, RelaySiegeGame.Lane lane) {
        LaneState out = new LaneState();
        int ownRelay = player == RelaySiegeGame.Player.SUN
                ? RelaySiegeGame.SUN_RELAY_POSITION : RelaySiegeGame.MOON_RELAY_POSITION;
        int enemyRelay = player == RelaySiegeGame.Player.SUN
                ? RelaySiegeGame.MOON_RELAY_POSITION : RelaySiegeGame.SUN_RELAY_POSITION;
        int direction = player == RelaySiegeGame.Player.SUN ? 1 : -1;

        for (RelaySiegeGame.EntityView entity : game.getEntities()) {
            if (entity.lane != lane) continue;
            RelaySiegeCards.Card card = RelaySiegeCards.card(entity.cardId);
            if (entity.owner == player) {
                out.friendlyCount++;
                out.friendlyHp += entity.hp;
                if (entity.airborne) out.friendlyAir++; else out.friendlyGround++;
                int toEnemyRelay = Math.abs(enemyRelay - entity.position);
                out.nearestFriendlyToEnemyRelay = Math.min(out.nearestFriendlyToEnemyRelay, toEnemyRelay);
                boolean hasCrossedCenter = direction > 0 ? entity.position > 50_000 : entity.position < 50_000;
                if (hasCrossedCenter && !entity.building) out.friendlyForwardHp += entity.hp;
            } else {
                out.enemyCount++;
                out.enemyHp += entity.hp;
                if (entity.airborne) out.enemyAir++; else out.enemyGround++;
                if (card.targetRule == RelaySiegeCards.TargetRule.STRUCTURES_ONLY) out.enemyStructureHunters++;
                int toOwnRelay = Math.abs(entity.position - ownRelay);
                out.nearestEnemyToOwnRelay = Math.min(out.nearestEnemyToOwnRelay, toOwnRelay);
                int proximity = Math.max(0, 45_000 - toOwnRelay);
                out.enemyDamagePressure += card.damage * 10 + proximity / 70 + entity.hp / 12;
            }
        }
        return out;
    }

    private static int scoreWait(
            RelaySiegeGame game,
            RelaySiegeGame.Player player,
            LaneState left,
            LaneState right,
            int flux) {
        int threat = threatScore(left) + threatScore(right);
        int score = 1_400;
        if (threat > 2_000) score -= threat / 2;
        if (flux < 3_000) score += 1_600;
        if (flux < 5_000) score += 650;
        if (flux > 9_300) score -= 1_150; // wasting regeneration is a real cost.
        if (left.friendlyForwardHp + right.friendlyForwardHp > 900) score -= 450; // convert survivors while they exist.
        if (game.getTick() > RelaySiegeGame.REGULATION_TICKS) score -= 600;
        return score;
    }

    private static String waitReason(LaneState left, LaneState right, int flux) {
        if (flux < 3_000) return "bank Flux before committing";
        if (threatScore(left) + threatScore(right) < 800) return "no urgent trade; preserve information and Flux";
        return "current hand lacks a positive-enough response";
    }

    private static int scoreCard(
            RelaySiegeGame game,
            RelaySiegeGame.Player player,
            RelaySiegeCards.Card card,
            RelaySiegeGame.Lane lane,
            int position,
            LaneState here,
            LaneState other,
            int handIndex) {
        int cost = card.fluxCost;
        int score = -cost * 420;
        int ownRelayHp = game.getRelayHp(player, lane);
        int enemyRelayHp = game.getRelayHp(player.opponent(), lane);
        boolean urgent = here.nearestEnemyToOwnRelay < 25_000 || ownRelayHp < RelaySiegeGame.RELAY_MAX_HP * 55 / 100;
        boolean pressureOpportunity = other.enemyCount >= 2 && here.enemyCount == 0;

        // Concrete defensive fit. Multiple tactical dimensions can create value, but each is tied to current board evidence.
        if (here.enemyCount > 0) {
            if (card.primaryRole == RelaySiegeCards.Role.SPLASH && here.enemyGround >= 2)
                score += 1_250 + here.enemyGround * 260;
            if (card.primaryRole == RelaySiegeCards.Role.SWARM && here.enemyCount == 1 && here.enemyAir == 0)
                score += 950;
            if (card.attacksAir && here.enemyAir > 0)
                score += 950 + here.enemyAir * 350;
            if (!card.attacksAir && here.enemyAir > 0 && here.enemyGround == 0)
                score -= 1_350;
            if (card.kind == RelaySiegeCards.Kind.BUILDING && here.enemyStructureHunters > 0)
                score += 1_650 + here.enemyStructureHunters * 420;
            if (card.primaryRole == RelaySiegeCards.Role.DUELIST && here.enemyCount == 1 && here.enemyAir == 0)
                score += 850;
            if ("gravity_well".equals(card.id) && here.enemyCount >= 2)
                score += 800 + here.enemyCount * 220;
            if (urgent) score += defensiveCapability(card, here);
        }

        // Surviving defenders are stored offensive value. Prefer adding a screen/win condition/buff to an existing push.
        if (here.friendlyForwardHp > 0) {
            if (card.primaryRole == RelaySiegeCards.Role.TANK) score += 540;
            if (card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION) score += 900 + here.friendlyForwardHp / 4;
            if ("overclock".equals(card.id)) score += 900 + here.friendlyForwardHp / 3 + here.friendlyCount * 180;
        } else if ("overclock".equals(card.id)) {
            score -= 1_700; // no speculative buff spam.
        }

        // Lane-pressure and profile switching.
        if (pressureOpportunity) {
            if (card.primaryRole == RelaySiegeCards.Role.TEMPO || card.primaryRole == RelaySiegeCards.Role.AIR
                    || card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION)
                score += 700;
        }
        if (card.airborne && here.enemyAir == 0 && here.enemyGround > 0) score += 420;
        if (card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION) {
            score += Math.max(0, RelaySiegeGame.RELAY_MAX_HP - enemyRelayHp) / 3;
            if (enemyRelayHp <= RelaySiegeGame.RELAY_MAX_HP / 3) score += 850;
            if (urgent && here.enemyCount > 0) score -= 1_200; // don't race blindly through lethal pressure.
        }

        // Cheap cycle gets a small value only when near Flux cap or when it produces a real second-lane obligation.
        if (cost <= 2) {
            if (game.getFluxMilli(player) > 8_800) score += 260;
            if (pressureOpportunity) score += 320;
            if (!urgent && here.enemyCount == 0 && other.enemyCount == 0 && game.getFluxMilli(player) < 7_500)
                score -= 260;
        }

        // Buildings belong in defensive geometry; units should not be thrown at extreme invalid-looking edges.
        if (card.kind == RelaySiegeCards.Kind.BUILDING) {
            int desired = defensiveBuildingPosition(player);
            score -= Math.abs(position - desired) / 35;
        } else if (card.kind == RelaySiegeCards.Kind.UNIT) {
            int desired = urgent ? lateDefensePosition(player) : safeDeploymentPosition(player);
            score -= Math.abs(position - desired) / 80;
        } else if (card.kind == RelaySiegeCards.Kind.SPELL && here.enemyCount == 0 && !"overclock".equals(card.id)) {
            score -= 1_200;
        }

        // Lower hand index is not strategically better; this tiny deterministic term only stabilizes exact ties.
        score -= handIndex;
        return score;
    }

    private static int defensiveCapability(RelaySiegeCards.Card card, LaneState threat) {
        int value = 0;
        if (card.kind == RelaySiegeCards.Kind.BUILDING) value += 700;
        if (card.primaryRole == RelaySiegeCards.Role.SPLASH && threat.enemyGround >= 2) value += 700;
        if (card.primaryRole == RelaySiegeCards.Role.DUELIST && threat.enemyCount == 1) value += 500;
        if (card.attacksAir && threat.enemyAir > 0) value += 620;
        if (card.hp >= 800) value += 260;
        return value;
    }

    private static int threatScore(LaneState lane) {
        if (lane.enemyCount == 0) return 0;
        int proximity = lane.nearestEnemyToOwnRelay == Integer.MAX_VALUE ? 0
                : Math.max(0, 40_000 - lane.nearestEnemyToOwnRelay) / 20;
        return lane.enemyDamagePressure + proximity + lane.enemyCount * 140;
    }

    private static List<Integer> candidatePositions(
            RelaySiegeGame game,
            RelaySiegeGame.Player player,
            RelaySiegeCards.Card card,
            RelaySiegeGame.Lane lane,
            LaneState state) {
        if (card.kind == RelaySiegeCards.Kind.SPELL) {
            if ("overclock".equals(card.id) && state.friendlyCount == 0) return Collections.singletonList(50_000);
            int focus = tacticalFocus(game, player, lane, state);
            return Collections.singletonList(focus);
        }

        List<Integer> positions = new ArrayList<>();
        if (card.kind == RelaySiegeCards.Kind.BUILDING) {
            positions.add(defensiveBuildingPosition(player));
            positions.add(player == RelaySiegeGame.Player.SUN ? 27_000 : 73_000);
        } else {
            positions.add(safeDeploymentPosition(player));
            positions.add(lateDefensePosition(player));
            positions.add(player == RelaySiegeGame.Player.SUN ? 39_500 : 60_500);
        }
        return positions;
    }

    private static int tacticalFocus(
            RelaySiegeGame game,
            RelaySiegeGame.Player player,
            RelaySiegeGame.Lane lane,
            LaneState state) {
        int total = 0;
        int count = 0;
        for (RelaySiegeGame.EntityView entity : game.getEntities()) {
            if (entity.lane != lane) continue;
            if ("overclock".equals("")) { /* keeps spell branch symmetric without hidden state */ }
            if (entity.owner != player) {
                total += entity.position;
                count++;
            }
        }
        if (count > 0) return total / count;
        return player == RelaySiegeGame.Player.SUN ? 55_000 : 45_000;
    }

    private static int safeDeploymentPosition(RelaySiegeGame.Player player) {
        return player == RelaySiegeGame.Player.SUN ? 31_000 : 69_000;
    }

    private static int lateDefensePosition(RelaySiegeGame.Player player) {
        return player == RelaySiegeGame.Player.SUN ? 22_000 : 78_000;
    }

    private static int defensiveBuildingPosition(RelaySiegeGame.Player player) {
        return player == RelaySiegeGame.Player.SUN ? 32_000 : 68_000;
    }

    private static String explain(
            RelaySiegeCards.Card card,
            LaneState here,
            LaneState other,
            RelaySiegeGame game,
            RelaySiegeGame.Player player) {
        if (card.kind == RelaySiegeCards.Kind.BUILDING && here.enemyStructureHunters > 0)
            return "redirect structure pressure and buy relay firing time";
        if (card.primaryRole == RelaySiegeCards.Role.SPLASH && here.enemyGround >= 2)
            return "convert clustered ground pressure into a positive defense";
        if (card.attacksAir && here.enemyAir > 0)
            return "cover an airborne threat that ground-only defenders cannot touch";
        if (card.primaryRole == RelaySiegeCards.Role.SWARM && here.enemyCount == 1)
            return "trade body count into an isolated single-target attacker";
        if ("overclock".equals(card.id) && here.friendlyForwardHp > 0)
            return "cash surviving defensive HP into counterpush tempo";
        if (other.enemyCount >= 2 && here.enemyCount == 0)
            return "punish the opponent's lane commitment with opposite-lane pressure";
        if (card.primaryRole == RelaySiegeCards.Role.WIN_CONDITION)
            return "force a structure answer while support or Flux advantage exists";
        if (game.getFluxMilli(player) > 9_000)
            return "avoid wasting Flux regeneration while preserving a useful role";
        return "best current cost-to-board-impact trade";
    }

    private static boolean better(Decision candidate, Decision incumbent, RelaySiegeGame game,
                                  RelaySiegeGame.Player player, int handIndex) {
        if (candidate.score != incumbent.score) return candidate.score > incumbent.score;
        if (candidate.wait != incumbent.wait) return candidate.wait; // exact tie favors information preservation.
        if (candidate.wait) return false;
        int c = candidate.cardId.compareTo(incumbent.cardId);
        if (c != 0) return c < 0;
        c = candidate.lane.compareTo(incumbent.lane);
        if (c != 0) return c < 0;
        return candidate.position < incumbent.position;
    }
}
