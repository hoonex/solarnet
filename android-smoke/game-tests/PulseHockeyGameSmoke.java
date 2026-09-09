package com.hoonex.solarnet.gridduel;

public final class PulseHockeyGameSmoke {
    public static void main(String[] args) {
        matchCannotLoopForever();
        openingInitiativeVariesBySeed();
        tacticsUseEnergyAndExpire();
        aiProducesLegalActions();
        aiVsAiHasNoStructuralOpeningLock();
        System.out.println("Pulse Hockey game smoke: PASS");
    }

    private static void matchCannotLoopForever() {
        PulseHockeyGame game = new PulseHockeyGame(11L);
        int safety = 0;
        while (!game.isFinished() && safety++ < 100) {
            PulseHockeyGame.TurnResult result = game.apply(PulseHockeyGame.Action.guard(0f), false);
            require(result.accepted, "guard loop action must be accepted");
        }
        require(game.isFinished(), "all-defense match must still terminate");
        require(game.getTurnNumber() == PulseHockeyGame.MAX_TURNS, "turn cap must be exact");
        require(game.isDraw(), "zero-score capped match should be a draw");
        require(game.getGoalHalfWidth() > 4f, "Overdrive must widen the goal during a stall");
    }

    private static void openingInitiativeVariesBySeed() {
        int sunOpeners = 0;
        int moonOpeners = 0;
        for (long seed = 1; seed <= 64; seed++) {
            PulseHockeyGame game = new PulseHockeyGame(seed);
            if (game.getOpeningPlayer() == PulseHockeyGame.Player.SUN) sunOpeners++;
            else if (game.getOpeningPlayer() == PulseHockeyGame.Player.MOON) moonOpeners++;
        }
        require(sunOpeners >= 20, "SUN should open a meaningful share of seeded matches");
        require(moonOpeners >= 20, "MOON should open a meaningful share of seeded matches");
    }

    private static void tacticsUseEnergyAndExpire() {
        PulseHockeyGame game = new PulseHockeyGame(7L);
        PulseHockeyGame.Player first = game.getActivePlayer();
        int before = game.getEnergy(first);
        PulseHockeyGame.TurnResult counter = game.apply(PulseHockeyGame.Action.counter(1.5f), false);
        require(counter.accepted, "counter should be accepted");
        require(game.getStance(first) == PulseHockeyGame.Stance.COUNTER, "counter must arm stance");
        require(game.getEnergy(first) == before, "counter cost and recovery should balance");

        PulseHockeyGame.Player second = game.getActivePlayer();
        PulseHockeyGame.TurnResult strike = game.apply(
                PulseHockeyGame.Action.strike(0f, second == PulseHockeyGame.Player.SUN ? 1f : -1f, 0.8f),
                false);
        require(strike.accepted, "opponent strike should be accepted");
        require(game.getStance(first) == PulseHockeyGame.Stance.NONE,
                "defensive stance must expire after one opposing attack");
    }

    private static void aiProducesLegalActions() {
        for (long seed = 101; seed < 113; seed++) {
            PulseHockeyGame game = new PulseHockeyGame(seed);
            for (int turn = 0; turn < 5 && !game.isFinished(); turn++) {
                PulseHockeyGame.Action action = PulseHockeyAi.chooseAction(game);
                require(action != null, "AI action required");
                PulseHockeyGame.TurnResult result = game.apply(action, false);
                require(result.accepted, "AI must only return legal actions");
            }
        }
    }

    private static void aiVsAiHasNoStructuralOpeningLock() {
        int openerWins = 0;
        int responderWins = 0;
        int draws = 0;
        int sunWins = 0;
        int moonWins = 0;

        for (long seed = 2001; seed < 2025; seed++) {
            PulseHockeyGame game = new PulseHockeyGame(seed);
            PulseHockeyGame.Player opener = game.getOpeningPlayer();
            int safety = 0;
            while (!game.isFinished() && safety++ < PulseHockeyGame.MAX_TURNS + 2) {
                PulseHockeyGame.Action action = PulseHockeyAi.chooseAction(game);
                require(action != null, "AI-vs-AI action required");
                PulseHockeyGame.TurnResult result = game.apply(action, false);
                require(result.accepted, "AI-vs-AI action must be legal");
            }
            require(game.isFinished(), "AI-vs-AI match must terminate");
            require(game.getTurnNumber() <= PulseHockeyGame.MAX_TURNS, "match exceeded turn cap");

            if (game.isDraw()) {
                draws++;
            } else {
                if (game.getWinner() == opener) openerWins++;
                else responderWins++;
                if (game.getWinner() == PulseHockeyGame.Player.SUN) sunWins++;
                else moonWins++;
            }
        }

        int decisive = openerWins + responderWins;
        require(decisive >= 6, "AI simulation should produce enough decisive matches to measure initiative");
        require(openerWins * 4 <= decisive * 3,
                "opening player wins more than 75% of decisive matches; initiative is structurally biased");
        require(sunWins > 0 && moonWins > 0, "both sides must be capable of winning seeded AI matches");
        require(draws < 20, "AI should not collapse into mostly drawn avoidance loops");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
