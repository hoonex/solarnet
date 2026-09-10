package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Engine-backed tactical lessons for Relay Siege.
 *
 * Every option rebuilds the same scenario, executes a legal real-game action (or WAIT), advances
 * the fixed-step simulation and measures the resulting trade. UI never owns a second ruleset.
 */
public final class RelaySiegeTacticalTrainer {
    public static final class Choice {
        public final String id;
        public final String label;
        public final String cardId;
        public final RelaySiegeGame.Lane lane;
        public final int position;
        public final String hypothesis;

        private Choice(
                String id,
                String label,
                String cardId,
                RelaySiegeGame.Lane lane,
                int position,
                String hypothesis) {
            this.id = id;
            this.label = label;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
            this.hypothesis = hypothesis;
        }

        public boolean isWait() { return cardId == null; }
    }

    public static final class Lesson {
        public final String id;
        public final String title;
        public final String principle;
        public final String situation;
        public final String expectedBestChoiceId;
        public final List<Choice> choices;
        final int settleTicks;

        private Lesson(
                String id,
                String title,
                String principle,
                String situation,
                String expectedBestChoiceId,
                int settleTicks,
                Choice... choices) {
            this.id = id;
            this.title = title;
            this.principle = principle;
            this.situation = situation;
            this.expectedBestChoiceId = expectedBestChoiceId;
            this.settleTicks = settleTicks;
            this.choices = Collections.unmodifiableList(Arrays.asList(choices));
        }
    }

    public static final class Outcome {
        public final String choiceId;
        public final boolean legal;
        public final String rejectionReason;
        public final int ownRelayDamage;
        public final int enemyRelayDamage;
        public final int friendlySurvivorHp;
        public final int enemyThreatHp;
        public final int fluxSpentMilli;
        public final int score;
        public final String stateDigest;

        private Outcome(
                String choiceId,
                boolean legal,
                String rejectionReason,
                int ownRelayDamage,
                int enemyRelayDamage,
                int friendlySurvivorHp,
                int enemyThreatHp,
                int fluxSpentMilli,
                int score,
                String stateDigest) {
            this.choiceId = choiceId;
            this.legal = legal;
            this.rejectionReason = rejectionReason;
            this.ownRelayDamage = ownRelayDamage;
            this.enemyRelayDamage = enemyRelayDamage;
            this.friendlySurvivorHp = friendlySurvivorHp;
            this.enemyThreatHp = enemyThreatHp;
            this.fluxSpentMilli = fluxSpentMilli;
            this.score = score;
            this.stateDigest = stateDigest;
        }

        public String compact() {
            return String.format(Locale.US,
                    "score %+d · own relay -%d · enemy relay -%d · survivors %d · threat %d · %.1fF",
                    score, ownRelayDamage, enemyRelayDamage, friendlySurvivorHp, enemyThreatHp,
                    fluxSpentMilli / 1000f);
        }
    }

    public static final class Result {
        public final Lesson lesson;
        public final List<Outcome> outcomes;
        public final Outcome best;

        private Result(Lesson lesson, List<Outcome> outcomes) {
            this.lesson = lesson;
            this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
            this.best = Collections.max(outcomes, Comparator.comparingInt(outcome -> outcome.score));
        }

        public Outcome forChoice(String choiceId) {
            for (Outcome outcome : outcomes) if (outcome.choiceId.equals(choiceId)) return outcome;
            throw new IllegalArgumentException("Unknown tactical choice: " + choiceId);
        }
    }

    private static final List<Lesson> LESSONS = Collections.unmodifiableList(Arrays.asList(
            new Lesson(
                    "building_pull",
                    "공성 유닛은 몸으로 막지 말고 경로를 바꿔라",
                    "AGGRO / BUILDING PULL",
                    "MOON Siege Walker가 오른쪽 Relay로 직행 중입니다. Walker는 일반 유닛을 무시하고 구조물만 노립니다.",
                    "beacon_pull",
                    430,
                    choice("beacon_pull", "Relay Beacon을 안쪽에 배치", "relay_beacon", RelaySiegeGame.Lane.RIGHT, 33_000,
                            "Walker의 구조물 타겟을 Beacon으로 바꿔 Relay가 공격할 시간을 번다."),
                    choice("duelist_body", "Duelist로 앞을 막기", "duelist", RelaySiegeGame.Lane.RIGHT, 32_000,
                            "높은 단일 DPS로 정면에서 처리한다."),
                    waitChoice("wait", "Flux를 아끼고 기다리기", "아무것도 쓰지 않고 Relay 화력에 맡긴다.")),
            new Lesson(
                    "splash_vs_swarm",
                    "다수 유닛에는 단일 DPS보다 한 번의 광역 교환",
                    "SPLASH / POSITIVE TRADE",
                    "MOON Spark Swarm 4기가 왼쪽으로 뭉쳐 들어옵니다. 모두 지상이며 서로 간격이 좁습니다.",
                    "pulse_guard",
                    190,
                    choice("pulse_guard", "Pulse Guard로 뭉친 곳을 받기", "pulse_guard", RelaySiegeGame.Lane.LEFT, 39_000,
                            "광역 피해가 네 몸을 동시에 깎고 살아남은 Guard가 역공 자산이 된다."),
                    choice("duelist", "Duelist 한 기로 처리", "duelist", RelaySiegeGame.Lane.LEFT, 39_000,
                            "단일 공격 속도로 하나씩 제거한다."),
                    choice("bulwark", "Bulwark로 전부 받아내기", "bulwark", RelaySiegeGame.Lane.LEFT, 39_000,
                            "체력으로 버티면서 Relay가 정리하도록 한다.")),
            new Lesson(
                    "survivor_conversion",
                    "방어에 성공한 유닛의 남은 체력은 이미 지불한 공격 자원",
                    "DEFENSE → COUNTERPUSH",
                    "방어를 끝낸 SUN Pulse Guard와 Arc Slinger가 오른쪽 중앙을 넘어 살아 있습니다. 지금 추가 Flux를 어디에 쓰느냐가 역공 크기를 결정합니다.",
                    "overclock_survivors",
                    330,
                    choice("overclock_survivors", "생존 유닛에 Overclock", "overclock", RelaySiegeGame.Lane.RIGHT, 57_000,
                            "이미 필드에 남은 두 유닛의 이동·공격 템포를 동시에 올려 기존 투자 가치를 증폭한다."),
                    choice("fresh_runner", "반대쪽에 Flux Runner 새로 배치", "runner", RelaySiegeGame.Lane.LEFT, 38_000,
                            "새 라인에 별도 압박을 만든다."),
                    waitChoice("hold_flux", "아무것도 더 쓰지 않기", "생존 유닛만 보내고 다음 수를 위해 Flux를 저장한다."))
    ));

    private RelaySiegeTacticalTrainer() { }

    public static List<Lesson> lessons() { return LESSONS; }

    public static Lesson lesson(String id) {
        for (Lesson lesson : LESSONS) if (lesson.id.equals(id)) return lesson;
        throw new IllegalArgumentException("Unknown tactical lesson: " + id);
    }

    public static Result evaluate(String lessonId) {
        Lesson lesson = lesson(lessonId);
        List<Outcome> outcomes = new ArrayList<>();
        for (Choice choice : lesson.choices) outcomes.add(runChoice(lesson, choice));
        return new Result(lesson, outcomes);
    }

    public static Outcome runChoice(Lesson lesson, Choice choice) {
        RelaySiegeGame game = buildScenario(lesson.id);
        int ownRelayBefore = relayTotal(game, RelaySiegeGame.Player.SUN);
        int enemyRelayBefore = relayTotal(game, RelaySiegeGame.Player.MOON);
        int spentBefore = game.getFluxSpentMilli(RelaySiegeGame.Player.SUN);

        boolean legal = true;
        String rejection = "OK";
        if (!choice.isWait()) {
            RelaySiegeGame.PlayResult play = game.tryPlay(
                    RelaySiegeGame.Player.SUN, choice.cardId, choice.lane, choice.position);
            legal = play.accepted;
            rejection = play.reason;
        }
        game.advanceTicks(lesson.settleTicks);

        int ownRelayDamage = ownRelayBefore - relayTotal(game, RelaySiegeGame.Player.SUN);
        int enemyRelayDamage = enemyRelayBefore - relayTotal(game, RelaySiegeGame.Player.MOON);
        int friendlyHp = entityHp(game, RelaySiegeGame.Player.SUN);
        int enemyHp = entityHp(game, RelaySiegeGame.Player.MOON);
        int spent = game.getFluxSpentMilli(RelaySiegeGame.Player.SUN) - spentBefore;
        int score = legal ? score(lesson.id, ownRelayDamage, enemyRelayDamage, friendlyHp, enemyHp, spent) : -100_000;

        return new Outcome(
                choice.id, legal, rejection, ownRelayDamage, enemyRelayDamage,
                friendlyHp, enemyHp, spent, score, game.canonicalState());
    }

    static RelaySiegeGame buildScenario(String lessonId) {
        if ("building_pull".equals(lessonId)) {
            RelaySiegeGame game = new RelaySiegeGame(
                    RelaySiegeCards.deck("counterforge"), RelaySiegeCards.deck("spark_cycle"), 28001L);
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
            game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 50_000);
            return game;
        }
        if ("splash_vs_swarm".equals(lessonId)) {
            RelaySiegeGame game = new RelaySiegeGame(
                    RelaySiegeCards.deck("counterforge"), RelaySiegeCards.deck("spark_cycle"), 28002L);
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
            for (int i = 0; i < 4; i++)
                game.scenarioSpawn("spark_swarm", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000 + i * 120);
            return game;
        }
        if ("survivor_conversion".equals(lessonId)) {
            RelaySiegeCards.Deck lessonDeck = RelaySiegeCards.customDeck(
                    "trainer_conversion",
                    "Trainer Conversion",
                    Arrays.asList("overclock", "runner", "siege_walker", "relay_beacon",
                            "arc_slinger", "pulse_guard", "duelist", "gravity_well"));
            RelaySiegeGame game = new RelaySiegeGame(
                    lessonDeck, RelaySiegeCards.deck("counterforge"), 28003L);
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
            game.scenarioSpawn("pulse_guard", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 55_000);
            game.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 51_000);
            return game;
        }
        throw new IllegalArgumentException("Unknown tactical scenario: " + lessonId);
    }

    private static int score(
            String lessonId,
            int ownRelayDamage,
            int enemyRelayDamage,
            int friendlyHp,
            int enemyHp,
            int spentMilli) {
        // The same measured dimensions are used in every lesson; lesson weights reflect the stated tactical objective.
        if ("building_pull".equals(lessonId)) {
            return -ownRelayDamage * 12 - enemyHp * 2 + friendlyHp / 4 - spentMilli / 18;
        }
        if ("splash_vs_swarm".equals(lessonId)) {
            return -ownRelayDamage * 10 - enemyHp * 3 + friendlyHp / 2 - spentMilli / 20;
        }
        if ("survivor_conversion".equals(lessonId)) {
            return enemyRelayDamage * 9 + friendlyHp / 5 - enemyHp - spentMilli / 24;
        }
        throw new IllegalArgumentException("Unknown lesson score: " + lessonId);
    }

    private static int relayTotal(RelaySiegeGame game, RelaySiegeGame.Player player) {
        return game.getRelayHp(player, RelaySiegeGame.Lane.LEFT)
                + game.getRelayHp(player, RelaySiegeGame.Lane.RIGHT);
    }

    private static int entityHp(RelaySiegeGame game, RelaySiegeGame.Player player) {
        int total = 0;
        for (RelaySiegeGame.EntityView entity : game.getEntities()) if (entity.owner == player) total += entity.hp;
        return total;
    }

    private static Choice choice(
            String id,
            String label,
            String cardId,
            RelaySiegeGame.Lane lane,
            int position,
            String hypothesis) {
        return new Choice(id, label, cardId, lane, position, hypothesis);
    }

    private static Choice waitChoice(String id, String label, String hypothesis) {
        return new Choice(id, label, null, RelaySiegeGame.Lane.LEFT, 0, hypothesis);
    }
}
