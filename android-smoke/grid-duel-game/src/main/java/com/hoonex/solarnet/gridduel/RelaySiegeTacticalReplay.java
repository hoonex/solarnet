package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replays Tactical Trainer choices through the same RelaySiegeGame used for scoring.
 * The final canonical state must match RelaySiegeTacticalTrainer.runChoice(), preventing UI replay drift.
 */
public final class RelaySiegeTacticalReplay {
    private static final int FRAME_INTERVAL_TICKS = 5;

    private RelaySiegeTacticalReplay() { }

    public static RelaySiegeDeckGuide.DemoRun preview(String lessonId) {
        RelaySiegeTacticalTrainer.Lesson lesson = RelaySiegeTacticalTrainer.lesson(lessonId);
        RelaySiegeGame game = RelaySiegeTacticalTrainer.buildScenario(lesson.id);
        List<RelaySiegeDeckGuide.DemoFrame> frames = new ArrayList<>();
        frames.add(RelaySiegeDeckGuide.DemoFrame.capture(game));
        List<RelaySiegeDeckGuide.DemoAnnotation> notes = new ArrayList<>();
        notes.add(new RelaySiegeDeckGuide.DemoAnnotation(game.getTick(), "SITUATION", lesson.situation));
        return new RelaySiegeDeckGuide.DemoRun(
                null,
                frames,
                notes,
                game.getEvents());
    }

    public static RelaySiegeDeckGuide.DemoRun run(String lessonId, String choiceId) {
        RelaySiegeTacticalTrainer.Lesson lesson = RelaySiegeTacticalTrainer.lesson(lessonId);
        RelaySiegeTacticalTrainer.Choice choice = choice(lesson, choiceId);
        RelaySiegeTacticalTrainer.Outcome expected = RelaySiegeTacticalTrainer.runChoice(lesson, choice);

        RelaySiegeGame game = RelaySiegeTacticalTrainer.buildScenario(lesson.id);
        List<RelaySiegeDeckGuide.DemoFrame> frames = new ArrayList<>();
        List<RelaySiegeDeckGuide.DemoAnnotation> annotations = new ArrayList<>();
        frames.add(RelaySiegeDeckGuide.DemoFrame.capture(game));

        if (choice.isWait()) {
            annotations.add(new RelaySiegeDeckGuide.DemoAnnotation(
                    game.getTick(), "WAIT", choice.label + " · " + choice.hypothesis));
        } else {
            RelaySiegeGame.PlayResult result = game.tryPlay(
                    RelaySiegeGame.Player.SUN,
                    choice.cardId,
                    choice.lane,
                    choice.position);
            if (!result.accepted) {
                throw new IllegalStateException(
                        "Trainer replay choice became illegal: " + lessonId + "/" + choiceId + " [" + result.reason + "]");
            }
            annotations.add(new RelaySiegeDeckGuide.DemoAnnotation(
                    game.getTick(), "PLAY", choice.label + " · " + choice.hypothesis));
            frames.add(RelaySiegeDeckGuide.DemoFrame.capture(game));
        }

        for (int i = 1; i <= lesson.settleTicks && !game.isFinished(); i++) {
            game.advanceTicks(1);
            if (i % FRAME_INTERVAL_TICKS == 0 || i == lesson.settleTicks || game.isFinished()) {
                frames.add(RelaySiegeDeckGuide.DemoFrame.capture(game));
            }
        }

        String actualDigest = game.canonicalState();
        if (!actualDigest.equals(expected.stateDigest)) {
            throw new IllegalStateException(
                    "Trainer replay diverged from scored outcome: " + lessonId + "/" + choiceId);
        }

        return new RelaySiegeDeckGuide.DemoRun(
                null,
                frames,
                annotations,
                game.getEvents());
    }

    public static List<RelaySiegeTacticalTrainer.Outcome> rankedOutcomes(String lessonId) {
        List<RelaySiegeTacticalTrainer.Outcome> ranked = new ArrayList<>(
                RelaySiegeTacticalTrainer.evaluate(lessonId).outcomes);
        ranked.sort((a, b) -> {
            int score = Integer.compare(b.score, a.score);
            if (score != 0) return score;
            return a.choiceId.compareTo(b.choiceId);
        });
        return Collections.unmodifiableList(ranked);
    }

    public static RelaySiegeTacticalTrainer.Choice choice(
            RelaySiegeTacticalTrainer.Lesson lesson,
            String choiceId) {
        for (RelaySiegeTacticalTrainer.Choice choice : lesson.choices) {
            if (choice.id.equals(choiceId)) return choice;
        }
        throw new IllegalArgumentException("Unknown tactical choice: " + lesson.id + "/" + choiceId);
    }
}
