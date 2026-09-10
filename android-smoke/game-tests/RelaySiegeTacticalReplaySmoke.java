package com.hoonex.solarnet.gridduel;

import java.util.List;

public final class RelaySiegeTacticalReplaySmoke {
    public static void main(String[] args) {
        everyChoiceReplayMatchesScoredOutcome();
        rankingMatchesTrainerBestChoice();
        previewUsesRealScenarioFrame();
        System.out.println("Relay Siege tactical replay smoke: PASS");
    }

    private static void everyChoiceReplayMatchesScoredOutcome() {
        for (RelaySiegeTacticalTrainer.Lesson lesson : RelaySiegeTacticalTrainer.lessons()) {
            for (RelaySiegeTacticalTrainer.Choice choice : lesson.choices) {
                RelaySiegeDeckGuide.DemoRun replay = RelaySiegeTacticalReplay.run(lesson.id, choice.id);
                require(replay.frames.size() >= 2,
                        "choice replay needs a time sequence: " + lesson.id + "/" + choice.id);
                require(!replay.annotations.isEmpty(),
                        "choice replay needs a teaching annotation: " + lesson.id + "/" + choice.id);
            }
        }
    }

    private static void rankingMatchesTrainerBestChoice() {
        for (RelaySiegeTacticalTrainer.Lesson lesson : RelaySiegeTacticalTrainer.lessons()) {
            RelaySiegeTacticalTrainer.Result result = RelaySiegeTacticalTrainer.evaluate(lesson.id);
            List<RelaySiegeTacticalTrainer.Outcome> ranked = RelaySiegeTacticalReplay.rankedOutcomes(lesson.id);
            require(!ranked.isEmpty(), "ranked outcomes cannot be empty");
            require(ranked.get(0).choiceId.equals(result.best.choiceId),
                    "trainer and replay ranking disagree: " + lesson.id);
            require(result.best.choiceId.equals(lesson.expectedBestChoiceId),
                    "teaching claim drifted from real engine: " + lesson.id + " expected "
                            + lesson.expectedBestChoiceId + " got " + result.best.choiceId);
        }
    }

    private static void previewUsesRealScenarioFrame() {
        for (RelaySiegeTacticalTrainer.Lesson lesson : RelaySiegeTacticalTrainer.lessons()) {
            RelaySiegeDeckGuide.DemoRun preview = RelaySiegeTacticalReplay.preview(lesson.id);
            require(preview.frames.size() == 1, "preview must not reveal future outcome: " + lesson.id);
            require(preview.frames.get(0).tick == 0, "preview starts at scenario boundary: " + lesson.id);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
