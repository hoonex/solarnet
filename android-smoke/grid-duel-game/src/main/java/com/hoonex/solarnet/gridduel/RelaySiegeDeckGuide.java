package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deck-learning content backed by the real RelaySiegeGame simulation.
 * The UI can render text metrics and play the deterministic demo frames without maintaining a fake second ruleset.
 */
public final class RelaySiegeDeckGuide {
    public static final class Guide {
        public final String deckId;
        public final String winCondition;
        public final String opening;
        public final String defensePlan;
        public final String conversionPlan;
        public final String commonMistake;
        public final int pressure;
        public final int defense;
        public final int control;
        public final int cycle;
        public final int complexity;
        public final List<CardNote> cardNotes;
        public final List<DemoScenario> demos;

        Guide(
                String deckId,
                String winCondition,
                String opening,
                String defensePlan,
                String conversionPlan,
                String commonMistake,
                int pressure,
                int defense,
                int control,
                int cycle,
                int complexity,
                List<CardNote> cardNotes,
                List<DemoScenario> demos) {
            this.deckId = deckId;
            this.winCondition = winCondition;
            this.opening = opening;
            this.defensePlan = defensePlan;
            this.conversionPlan = conversionPlan;
            this.commonMistake = commonMistake;
            this.pressure = boundedRating(pressure);
            this.defense = boundedRating(defense);
            this.control = boundedRating(control);
            this.cycle = boundedRating(cycle);
            this.complexity = boundedRating(complexity);
            this.cardNotes = Collections.unmodifiableList(new ArrayList<>(cardNotes));
            this.demos = Collections.unmodifiableList(new ArrayList<>(demos));
        }

        public RelaySiegeCards.Deck deck() { return RelaySiegeCards.deck(deckId); }
        public double averageFlux() { return deck().averageFlux(); }
        public int fourCardCycleCost() { return deck().fourCardCycleCost(); }
    }

    public static final class CardNote {
        public final String cardId;
        public final String job;
        public final String goodInto;
        public final String badInto;
        public final String timing;

        CardNote(String cardId, String job, String goodInto, String badInto, String timing) {
            RelaySiegeCards.card(cardId);
            this.cardId = cardId;
            this.job = job;
            this.goodInto = goodInto;
            this.badInto = badInto;
            this.timing = timing;
        }
    }

    public static final class DemoScenario {
        public final String id;
        public final String title;
        public final String lesson;
        public final String watchFor;
        final DemoScript script;

        DemoScenario(String id, String title, String lesson, String watchFor, DemoScript script) {
            this.id = id;
            this.title = title;
            this.lesson = lesson;
            this.watchFor = watchFor;
            this.script = script;
        }

        /** Runs the actual deterministic combat engine and returns snapshots suitable for an embedded mini replay. */
        public DemoRun run() {
            RelaySiegeCards.Deck deck = RelaySiegeCards.deck(script.deckId);
            RelaySiegeGame game = new RelaySiegeGame(deck, deck, script.seed);
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, RelaySiegeGame.MAX_FLUX_MILLI);
            game.scenarioSetFlux(RelaySiegeGame.Player.MOON, RelaySiegeGame.MAX_FLUX_MILLI);
            script.setup.apply(game);

            List<DemoFrame> frames = new ArrayList<>();
            List<DemoAnnotation> annotations = new ArrayList<>();
            int nextAction = 0;
            frames.add(DemoFrame.capture(game));

            for (int targetTick = 1; targetTick <= script.durationTicks; targetTick++) {
                while (nextAction < script.actions.size() && script.actions.get(nextAction).tick == targetTick) {
                    ScriptAction action = script.actions.get(nextAction++);
                    RelaySiegeGame.PlayResult result = game.tryPlay(
                            action.player, action.cardId, action.lane, action.position);
                    annotations.add(new DemoAnnotation(
                            game.getTick(),
                            result.accepted ? "PLAY" : "REJECTED",
                            action.caption + (result.accepted ? "" : " [" + result.reason + "]")));
                }
                game.advanceTicks(1);
                if (targetTick % 5 == 0 || game.isFinished()) frames.add(DemoFrame.capture(game));
                if (game.isFinished()) break;
            }
            return new DemoRun(this, frames, annotations, game.getEvents());
        }
    }

    public static final class DemoRun {
        public final DemoScenario scenario;
        public final List<DemoFrame> frames;
        public final List<DemoAnnotation> annotations;
        public final List<RelaySiegeGame.BattleEvent> battleEvents;

        DemoRun(
                DemoScenario scenario,
                List<DemoFrame> frames,
                List<DemoAnnotation> annotations,
                List<RelaySiegeGame.BattleEvent> battleEvents) {
            this.scenario = scenario;
            this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
            this.battleEvents = Collections.unmodifiableList(new ArrayList<>(battleEvents));
        }
    }

    public static final class DemoFrame {
        public final int tick;
        public final int sunFluxMilli;
        public final int moonFluxMilli;
        public final int sunLeftRelayHp;
        public final int sunRightRelayHp;
        public final int moonLeftRelayHp;
        public final int moonRightRelayHp;
        public final List<RelaySiegeGame.EntityView> entities;

        private DemoFrame(
                int tick,
                int sunFluxMilli,
                int moonFluxMilli,
                int sunLeftRelayHp,
                int sunRightRelayHp,
                int moonLeftRelayHp,
                int moonRightRelayHp,
                List<RelaySiegeGame.EntityView> entities) {
            this.tick = tick;
            this.sunFluxMilli = sunFluxMilli;
            this.moonFluxMilli = moonFluxMilli;
            this.sunLeftRelayHp = sunLeftRelayHp;
            this.sunRightRelayHp = sunRightRelayHp;
            this.moonLeftRelayHp = moonLeftRelayHp;
            this.moonRightRelayHp = moonRightRelayHp;
            this.entities = entities;
        }

        static DemoFrame capture(RelaySiegeGame game) {
            return new DemoFrame(
                    game.getTick(),
                    game.getFluxMilli(RelaySiegeGame.Player.SUN),
                    game.getFluxMilli(RelaySiegeGame.Player.MOON),
                    game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT),
                    game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT),
                    game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT),
                    game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT),
                    game.getEntities());
        }
    }

    public static final class DemoAnnotation {
        public final int tick;
        public final String type;
        public final String text;
        DemoAnnotation(int tick, String type, String text) {
            this.tick = tick;
            this.type = type;
            this.text = text;
        }
    }

    private interface Setup { void apply(RelaySiegeGame game); }

    private static final class ScriptAction {
        final int tick;
        final RelaySiegeGame.Player player;
        final String cardId;
        final RelaySiegeGame.Lane lane;
        final int position;
        final String caption;

        ScriptAction(int tick, RelaySiegeGame.Player player, String cardId,
                     RelaySiegeGame.Lane lane, int position, String caption) {
            this.tick = tick;
            this.player = player;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
            this.caption = caption;
        }
    }

    private static final class DemoScript {
        final String deckId;
        final long seed;
        final int durationTicks;
        final Setup setup;
        final List<ScriptAction> actions;

        DemoScript(String deckId, long seed, int durationTicks, Setup setup, ScriptAction... actions) {
            this.deckId = deckId;
            this.seed = seed;
            this.durationTicks = durationTicks;
            this.setup = setup;
            List<ScriptAction> ordered = new ArrayList<>();
            Collections.addAll(ordered, actions);
            ordered.sort((a, b) -> Integer.compare(a.tick, b.tick));
            this.actions = Collections.unmodifiableList(ordered);
        }
    }

    private static final Map<String, Guide> GUIDES = new LinkedHashMap<>();

    static {
        add(new Guide(
                "counterforge",
                "Let the opponent cross first, win a compact defense, then turn surviving bodies into a Siege Walker lane push.",
                "Safe starts are Arc Slinger behind your relay or waiting near full Flux. Do not open with all 5 Flux on Siege Walker without knowing the response.",
                "Pulse Guard handles clustered ground pressure; Arc Slinger covers air; Relay Beacon changes pathing so expensive attackers spend time away from your relay.",
                "The deck becomes dangerous when a defender survives. Put Bulwark/Siege Walker in front, then Overclock the surviving group instead of building a new push from zero.",
                "Treating every defense as a reason to spend another card. A clean 4-for-5 defense is only valuable if you stop spending and keep the survivor.",
                3, 5, 4, 2, 4,
                notes(
                        note("bulwark", "Damage sponge / counterpush screen", "single-target defenders", "air pressure", "place after a surviving support unit, not automatically at full Flux"),
                        note("duelist", "efficient single-target stop", "slow tanks and isolated melee", "Spark Swarm", "drop late enough that your relay helps without giving free travel time"),
                        note("arc_slinger", "long-range support and air coverage", "Needlewing / units distracted by Bulwark", "fast units dropped on top of it", "protect it; surviving ranged DPS is the deck's best counterpush asset"),
                        note("pulse_guard", "ground splash defender", "Spark Swarm / clustered pushes", "Needlewing", "wait for units to cluster before committing"),
                        note("siege_walker", "relay win condition", "opponent low on Flux or building out of cycle", "Relay Beacon / cheap structures", "best behind surviving defense"),
                        note("relay_beacon", "pull and time-buying building", "structure-targeters", "Gravity Well plus ranged focus", "place to force extra walking, not directly on the attacker"),
                        note("gravity_well", "slow + area chip", "clumped support behind a tank", "single healthy unit", "cast when the slow changes how long defenders can attack"),
                        note("overclock", "counterpush converter", "2+ surviving friendly bodies", "empty lane", "do not use for theoretical future value; buff units already alive")),
                demos(counterforgeDefenseDemo(), counterforgePullDemo())));

        add(new Guide(
                "spark_cycle",
                "Force a specific answer with cheap pressure, then rotate four cards and present Siege Walker before that answer is available again.",
                "Runner is the lowest-commitment opener. Split it from your next real push so the opponent has to reveal which lane they value.",
                "Duelist handles one durable threat; Coil Turret buys time; Gravity Well prevents a swarm from turning a cheap defense into relay damage.",
                "Your advantage is not raw stats. It is returning to a key card while the opponent's expensive counter is still buried in cycle.",
                "Dumping every 2–3 Flux card because it is cheap. Cheap cards only create a cycle advantage if each one forces or answers something.",
                5, 3, 3, 5, 5,
                notes(
                        note("runner", "cycle / opposite-lane poke / kite", "slow expensive setups", "Pulse Guard", "use when 2 Flux forces a response worth more than 2"),
                        note("spark_swarm", "high body-count punish", "isolated Duelist / Siege Walker", "Pulse Guard and Gravity Well", "deploy after splash is shown elsewhere"),
                        note("duelist", "compact defense", "isolated durable units", "swarms / air", "avoid pairing with Swarm unless necessary; preserve cycle flexibility"),
                        note("needlewing", "air lane pressure", "ground-only defenders", "Arc Slinger / Coil Turret", "punish after opponent commits ground coverage"),
                        note("siege_walker", "repeatable relay threat", "counter out of hand", "buildings", "track four-card rotation before replaying it"),
                        note("coil_turret", "cheap ranged building", "medium pushes / air", "heavy focused push", "central-ish pull timing gets more value than early placement"),
                        note("gravity_well", "tempo control", "clustered counterpush", "spread lanes", "buy exactly the time your cheap units need"),
                        note("overclock", "survivor acceleration", "small surviving group", "fresh unsupported deployment", "use after defense, not as a standalone attack card")),
                demos(sparkCycleDemo())));

        add(new Guide(
                "split_voltage",
                "Create a commitment mismatch: make the opponent spend heavily in one lane, then attack the other with a different target profile.",
                "Bulwark in the back is acceptable only near full Flux. Runner is safer when you do not want to reveal whether your main pressure will be ground or air.",
                "Pulse Guard stabilizes ground; Arc Slinger and Coil Turret ensure an air punish cannot freely bypass the defense.",
                "The key conversion is lane switching. Do not stack every support unit behind Bulwark when the opponent already spent the correct answer there.",
                "Splitting just because the deck is called Split Voltage. Split pressure only when the opponent's Flux or counters are actually committed.",
                4, 4, 4, 3, 5,
                notes(
                        note("bulwark", "commitment probe / tank", "single-target lane defenses", "Needlewing", "force a meaningful answer before revealing the second lane"),
                        note("runner", "cheap punish", "opponent at low Flux", "splash", "opposite lane immediately after a heavy response"),
                        note("arc_slinger", "flex ranged coverage", "air / distracted units", "fast dive", "place where it can choose either lane late"),
                        note("needlewing", "alternate target profile", "ground-only lane", "ranged anti-air", "best after ranged anti-air is committed elsewhere"),
                        note("pulse_guard", "ground reset", "swarm", "air", "keep it for defense unless you know ground answers are exhausted"),
                        note("coil_turret", "low-cost anchor", "mixed medium pressure", "heavy structure pressure", "use it to stabilize the weak lane while you attack the other"),
                        note("gravity_well", "control / retarget timing", "clumps", "split formations", "cast where slowing changes who reaches whom first"),
                        note("overclock", "tempo swing", "surviving split-lane force", "no survivors", "accelerate whichever lane gained the better trade")),
                demos(splitPressureDemo())));
    }

    private RelaySiegeDeckGuide() { }

    public static Guide guide(String deckId) {
        Guide guide = GUIDES.get(deckId);
        if (guide == null) throw new IllegalArgumentException("No guide for deck: " + deckId);
        return guide;
    }

    public static List<Guide> guides() {
        return Collections.unmodifiableList(new ArrayList<>(GUIDES.values()));
    }

    private static void add(Guide guide) {
        RelaySiegeCards.deck(guide.deckId);
        if (GUIDES.put(guide.deckId, guide) != null) throw new IllegalStateException("duplicate guide " + guide.deckId);
    }

    private static CardNote note(String cardId, String job, String goodInto, String badInto, String timing) {
        return new CardNote(cardId, job, goodInto, badInto, timing);
    }

    private static List<CardNote> notes(CardNote... notes) {
        List<CardNote> result = new ArrayList<>();
        Collections.addAll(result, notes);
        return result;
    }

    private static List<DemoScenario> demos(DemoScenario... demos) {
        List<DemoScenario> result = new ArrayList<>();
        Collections.addAll(result, demos);
        return result;
    }

    private static DemoScenario counterforgeDefenseDemo() {
        DemoScript script = new DemoScript(
                "counterforge", 260901L, 150,
                game -> {
                    game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 49_000);
                    game.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 57_000);
                },
                new ScriptAction(1, RelaySiegeGame.Player.SUN, "pulse_guard", RelaySiegeGame.Lane.LEFT, 35_000,
                        "Pulse Guard meets the Walker after it crosses instead of walking unsupported into enemy range"),
                new ScriptAction(60, RelaySiegeGame.Player.SUN, "arc_slinger", RelaySiegeGame.Lane.LEFT, 28_000,
                        "Arc Slinger stays behind the surviving defender and adds safe damage"));
        return new DemoScenario(
                "counterforge_defend_convert",
                "Defend once, keep the survivors",
                "A defense is not finished when the enemy dies; remaining HP is stored offensive value.",
                "Watch Pulse Guard absorb contact while Arc Slinger remains untouched behind it.", script);
    }

    private static DemoScenario counterforgePullDemo() {
        DemoScript script = new DemoScript(
                "counterforge", 260902L, 135,
                game -> game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 50_000),
                new ScriptAction(1, RelaySiegeGame.Player.SUN, "relay_beacon", RelaySiegeGame.Lane.RIGHT, 32_000,
                        "Beacon becomes the Walker's nearer structure target and buys relay firing time"));
        return new DemoScenario(
                "counterforge_building_pull",
                "Redirect a structure hunter",
                "Placement changes pathing. A defensive building is time and distance, not only its damage stat.",
                "Watch the Siege Walker spend attacks on the Beacon instead of marching straight to the relay.", script);
    }

    private static DemoScenario sparkCycleDemo() {
        DemoScript script = new DemoScript(
                "spark_cycle", 260903L, 145,
                game -> game.scenarioSpawn("duelist", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 48_000),
                new ScriptAction(1, RelaySiegeGame.Player.SUN, "spark_swarm", RelaySiegeGame.Lane.LEFT, 39_000,
                        "Four bodies surround a single-target Duelist"),
                new ScriptAction(35, RelaySiegeGame.Player.SUN, "runner", RelaySiegeGame.Lane.RIGHT, 39_000,
                        "Runner immediately asks a second-lane question instead of stacking into the same defense"));
        return new DemoScenario(
                "spark_cycle_force_two_answers",
                "Cheap cards must force different answers",
                "Cycle speed matters only if low-cost plays create separate tactical obligations.",
                "Watch the Swarm occupy the left defender while Runner creates right-lane tempo.", script);
    }

    private static DemoScenario splitPressureDemo() {
        DemoScript script = new DemoScript(
                "split_voltage", 260904L, 150,
                game -> game.scenarioSpawn("pulse_guard", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 56_000),
                new ScriptAction(1, RelaySiegeGame.Player.SUN, "bulwark", RelaySiegeGame.Lane.LEFT, 28_000,
                        "Bulwark shows a heavy ground commitment on the left"),
                new ScriptAction(55, RelaySiegeGame.Player.SUN, "needlewing", RelaySiegeGame.Lane.RIGHT, 40_000,
                        "Needlewing attacks the other lane with an air target profile after ground defense is committed"));
        return new DemoScenario(
                "split_voltage_commit_then_switch",
                "Force the answer, then change lanes and target type",
                "Split pressure is strongest when the second threat attacks a weakness created by the first response.",
                "Watch ground defense stay occupied left while an airborne threat enters right.", script);
    }

    private static int boundedRating(int rating) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("guide ratings must be 1..5");
        return rating;
    }
}
