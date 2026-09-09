package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small deterministic look-ahead AI for Pulse Hockey. */
public final class PulseHockeyAi {
    private static final int FIRST_PLY_KEEP = 5;
    private static final int RESPONSE_LIMIT = 10;

    private PulseHockeyAi() { }

    public static PulseHockeyGame.Action chooseAction(PulseHockeyGame game) {
        if (game == null || game.isFinished()) return null;
        PulseHockeyGame.Player perspective = game.getActivePlayer();
        List<PulseHockeyGame.Action> candidates = buildCandidates(game, perspective, false);
        if (candidates.isEmpty()) return null;

        List<ScoredAction> firstPly = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            PulseHockeyGame.Action action = candidates.get(i);
            PulseHockeyGame simulated = new PulseHockeyGame(game);
            PulseHockeyGame.TurnResult result = simulated.apply(action, false);
            if (!result.accepted) continue;
            float score = evaluate(simulated, perspective) + jitter(game, i) * 3.5f;
            firstPly.add(new ScoredAction(action, simulated, score));
        }
        if (firstPly.isEmpty()) return null;

        firstPly.sort(Comparator.comparingDouble((ScoredAction item) -> item.firstScore).reversed());
        int keep = Math.min(FIRST_PLY_KEEP, firstPly.size());

        PulseHockeyGame.Action bestAction = firstPly.get(0).action;
        float bestValue = -Float.MAX_VALUE;
        for (int i = 0; i < keep; i++) {
            ScoredAction option = firstPly.get(i);
            if (option.after.isFinished()) {
                float terminal = evaluate(option.after, perspective);
                if (terminal > bestValue) {
                    bestValue = terminal;
                    bestAction = option.action;
                }
                continue;
            }

            List<PulseHockeyGame.Action> responses = buildCandidates(
                    option.after,
                    option.after.getActivePlayer(),
                    true);
            float worstResponse = option.firstScore;
            int considered = 0;
            for (PulseHockeyGame.Action response : responses) {
                if (considered >= RESPONSE_LIMIT) break;
                PulseHockeyGame replied = new PulseHockeyGame(option.after);
                PulseHockeyGame.TurnResult responseResult = replied.apply(response, false);
                if (!responseResult.accepted) continue;
                considered++;
                float responseValue = evaluate(replied, perspective);
                worstResponse = Math.min(worstResponse, responseValue);
            }

            // Immediate quality still matters, but a move with an obvious tactical refutation loses value.
            float minimaxValue = option.firstScore * 0.66f + worstResponse * 0.34f;
            if (minimaxValue > bestValue) {
                bestValue = minimaxValue;
                bestAction = option.action;
            }
        }
        return bestAction;
    }

    private static List<PulseHockeyGame.Action> buildCandidates(
            PulseHockeyGame game,
            PulseHockeyGame.Player player,
            boolean compact) {
        List<PulseHockeyGame.Action> result = new ArrayList<>();
        if (player == PulseHockeyGame.Player.NONE) return result;

        float malletX = game.getMalletX(player);
        float malletZ = game.getMalletZ(player);
        float puckX = game.getPuckX();
        float puckZ = game.getPuckZ();
        float attackDirection = player == PulseHockeyGame.Player.SUN ? 1f : -1f;

        float[] offsets = compact
                ? new float[]{-0.75f, 0f, 0.75f}
                : new float[]{-1.25f, -0.65f, 0f, 0.65f, 1.25f};
        for (float offset : offsets) {
            float targetX = puckX + offset;
            float targetZ = puckZ;
            float dx = targetX - malletX;
            float dz = targetZ - malletZ;
            result.add(PulseHockeyGame.Action.strike(dx, dz, 0.72f));
            result.add(PulseHockeyGame.Action.strike(dx, dz, 1.0f));
            if (game.canUse(player, PulseHockeyGame.Tactic.POWER))
                result.add(PulseHockeyGame.Action.power(dx, dz, 0.94f));
        }

        // Bank-shot families prevent the AI from collapsing into one direct trajectory.
        result.add(PulseHockeyGame.Action.strike(2.6f, attackDirection * 6.5f, 0.92f));
        result.add(PulseHockeyGame.Action.strike(-2.6f, attackDirection * 6.5f, 0.92f));
        if (!compact && game.canUse(player, PulseHockeyGame.Tactic.POWER)) {
            result.add(PulseHockeyGame.Action.power(2.25f, attackDirection * 6.2f, 1f));
            result.add(PulseHockeyGame.Action.power(-2.25f, attackDirection * 6.2f, 1f));
        }

        float defensiveX = clamp(puckX, -3.55f, 3.55f);
        result.add(PulseHockeyGame.Action.guard(defensiveX));
        if (!compact) result.add(PulseHockeyGame.Action.guard(0f));
        if (game.canUse(player, PulseHockeyGame.Tactic.COUNTER)) {
            result.add(PulseHockeyGame.Action.counter(defensiveX));
            if (!compact) result.add(PulseHockeyGame.Action.counter(0f));
        }
        return result;
    }

    private static float evaluate(PulseHockeyGame game, PulseHockeyGame.Player perspective) {
        PulseHockeyGame.Player opponent = perspective.opponent();
        if (game.getWinner() == perspective) return 100_000f;
        if (game.getWinner() == opponent) return -100_000f;

        int scoreDifference = game.getScore(perspective) - game.getScore(opponent);
        if (game.isDraw()) return scoreDifference * 5_000f;

        float direction = perspective == PulseHockeyGame.Player.SUN ? 1f : -1f;
        float value = scoreDifference * 5_500f;
        value += direction * game.getPuckZ() * 58f;
        value -= Math.abs(game.getPuckX()) * 2.5f;
        value += (game.getEnergy(perspective) - game.getEnergy(opponent)) * 18f;

        // Reward a defender that is actually between puck and goal, not merely sitting at center.
        float ownGoalZ = perspective == PulseHockeyGame.Player.SUN ? -PulseHockeyGame.HALF_LENGTH : PulseHockeyGame.HALF_LENGTH;
        float ownMalletZ = game.getMalletZ(perspective);
        float ownMalletX = game.getMalletX(perspective);
        float puckGoalDistance = Math.abs(game.getPuckZ() - ownGoalZ);
        if (puckGoalDistance < 5.0f) {
            value -= Math.abs(game.getPuckX() - ownMalletX) * 16f;
            value -= Math.abs(ownMalletZ - ownGoalZ) * 8f;
        }

        PulseHockeyGame.Stance stance = game.getStance(perspective);
        if (stance == PulseHockeyGame.Stance.GUARD) value += 32f;
        else if (stance == PulseHockeyGame.Stance.COUNTER) value += 25f;

        // Pressure is intentionally slightly valuable to the side already pushing forward:
        // Overdrive opens the arena and prevents defensive loops.
        value += game.getOverdriveLevel() * direction * game.getPuckZ() * 2.4f;
        return value;
    }

    private static float jitter(PulseHockeyGame game, int candidateIndex) {
        long value = game.getMatchSeed();
        value ^= ((long) game.getTurnNumber() + 1L) * 0x9E3779B97F4A7C15L;
        value ^= ((long) candidateIndex + 17L) * 0xBF58476D1CE4E5B9L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 40) & 0xFFFFL) / 65535f - 0.5f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ScoredAction {
        final PulseHockeyGame.Action action;
        final PulseHockeyGame after;
        final float firstScore;

        ScoredAction(PulseHockeyGame.Action action, PulseHockeyGame after, float firstScore) {
            this.action = action;
            this.after = after;
            this.firstScore = firstScore;
        }
    }
}
