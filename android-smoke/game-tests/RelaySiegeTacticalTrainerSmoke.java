package com.hoonex.solarnet.gridduel;

public final class RelaySiegeTacticalTrainerSmoke {
    public static void main(String[] args) {
        intendedLessonsMatchRealEngineOutcomes();
        outcomesAreDeterministicAndChoicesStayLegal();
        System.out.println("Relay Siege Tactical Trainer smoke: PASS");
    }

    private static void intendedLessonsMatchRealEngineOutcomes() {
        for (RelaySiegeTacticalTrainer.Lesson lesson : RelaySiegeTacticalTrainer.lessons()) {
            RelaySiegeTacticalTrainer.Result result = RelaySiegeTacticalTrainer.evaluate(lesson.id);
            require(result.best.choiceId.equals(lesson.expectedBestChoiceId),
                    lesson.id + " expected " + lesson.expectedBestChoiceId + " but real engine preferred "
                            + result.best.choiceId + " / " + result.best.compact());
            require(result.outcomes.size() >= 3, "trainer lesson needs meaningful alternatives: " + lesson.id);
            int distinctScores = 0;
            for (int i = 0; i < result.outcomes.size(); i++) {
                RelaySiegeTacticalTrainer.Outcome outcome = result.outcomes.get(i);
                require(outcome.legal, lesson.id + " has illegal scripted choice: " + outcome.choiceId
                        + " / " + outcome.rejectionReason);
                boolean unique = true;
                for (int j = 0; j < i; j++)
                    if (result.outcomes.get(j).score == outcome.score) unique = false;
                if (unique) distinctScores++;
            }
            require(distinctScores >= 2, "lesson alternatives must produce measurably different trades: " + lesson.id);
        }
    }

    private static void outcomesAreDeterministicAndChoicesStayLegal() {
        for (RelaySiegeTacticalTrainer.Lesson lesson : RelaySiegeTacticalTrainer.lessons()) {
            RelaySiegeTacticalTrainer.Result a = RelaySiegeTacticalTrainer.evaluate(lesson.id);
            RelaySiegeTacticalTrainer.Result b = RelaySiegeTacticalTrainer.evaluate(lesson.id);
            require(a.best.choiceId.equals(b.best.choiceId), "best choice must replay deterministically: " + lesson.id);
            for (RelaySiegeTacticalTrainer.Outcome left : a.outcomes) {
                RelaySiegeTacticalTrainer.Outcome right = b.forChoice(left.choiceId);
                require(left.score == right.score, "score deterministic: " + lesson.id + "/" + left.choiceId);
                require(left.stateDigest.equals(right.stateDigest), "state deterministic: " + lesson.id + "/" + left.choiceId);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
