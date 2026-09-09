package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical card/deck catalog for Relay Siege. Rules data lives here, not in UI code. */
public final class RelaySiegeCards {
    public enum Kind { UNIT, BUILDING, SPELL }
    public enum TargetRule { ANY, GROUND_ONLY, STRUCTURES_ONLY }
    public enum Role { WIN_CONDITION, TANK, DUELIST, SPLASH, RANGED, SWARM, AIR, BUILDING, CONTROL, TEMPO }

    public static final class Card {
        public final String id;
        public final String name;
        public final int fluxCost;
        public final Kind kind;
        public final Role primaryRole;
        public final int count;
        public final int hp;
        public final int damage;
        public final int attackTicks;
        public final int range;
        public final int aggroRange;
        public final int speed;
        public final int splashRadius;
        public final boolean airborne;
        public final boolean attacksAir;
        public final TargetRule targetRule;
        public final int lifetimeTicks;
        public final String shortDescription;

        private Card(
                String id,
                String name,
                int fluxCost,
                Kind kind,
                Role primaryRole,
                int count,
                int hp,
                int damage,
                int attackTicks,
                int range,
                int aggroRange,
                int speed,
                int splashRadius,
                boolean airborne,
                boolean attacksAir,
                TargetRule targetRule,
                int lifetimeTicks,
                String shortDescription) {
            this.id = id;
            this.name = name;
            this.fluxCost = fluxCost;
            this.kind = kind;
            this.primaryRole = primaryRole;
            this.count = count;
            this.hp = hp;
            this.damage = damage;
            this.attackTicks = attackTicks;
            this.range = range;
            this.aggroRange = aggroRange;
            this.speed = speed;
            this.splashRadius = splashRadius;
            this.airborne = airborne;
            this.attacksAir = attacksAir;
            this.targetRule = targetRule;
            this.lifetimeTicks = lifetimeTicks;
            this.shortDescription = shortDescription;
        }
    }

    public static final class Deck {
        public final String id;
        public final String name;
        public final String archetype;
        public final List<String> cardIds;
        public final String gamePlan;
        public final String weakness;

        private Deck(
                String id,
                String name,
                String archetype,
                String gamePlan,
                String weakness,
                String... cardIds) {
            if (cardIds.length != 8) throw new IllegalArgumentException("Relay Siege decks require exactly 8 cards");
            this.id = id;
            this.name = name;
            this.archetype = archetype;
            this.cardIds = Collections.unmodifiableList(Arrays.asList(cardIds.clone()));
            this.gamePlan = gamePlan;
            this.weakness = weakness;
            for (String cardId : this.cardIds) card(cardId);
        }

        public double averageFlux() {
            int total = 0;
            for (String id : cardIds) total += card(id).fluxCost;
            return total / 8.0;
        }

        /** Four cheapest distinct cards: a useful approximation of how cheaply a deck can rotate. */
        public int fourCardCycleCost() {
            List<Integer> costs = new ArrayList<>();
            for (String id : cardIds) costs.add(card(id).fluxCost);
            Collections.sort(costs);
            int total = 0;
            for (int i = 0; i < 4; i++) total += costs.get(i);
            return total;
        }
    }

    private static final Map<String, Card> CARDS = new LinkedHashMap<>();
    private static final Map<String, Deck> DECKS = new LinkedHashMap<>();

    static {
        add(unit("bulwark", "Bulwark", 5, Role.TANK, 1,
                1850, 92, 14, 1500, 9000, 72, 0,
                false, false, TargetRule.GROUND_ONLY,
                "Slow frontliner that absorbs pressure and preserves support units."));
        add(unit("duelist", "Duelist", 3, Role.DUELIST, 1,
                690, 128, 9, 1500, 8500, 145, 0,
                false, false, TargetRule.GROUND_ONLY,
                "Fast single-target defender that converts efficient defense into a counterpush."));
        add(unit("arc_slinger", "Arc Slinger", 4, Role.RANGED, 1,
                560, 122, 11, 8200, 11000, 92, 0,
                false, true, TargetRule.ANY,
                "Long-range anti-air support. Fragile when deployed without a screen."));
        add(unit("spark_swarm", "Spark Swarm", 3, Role.SWARM, 4,
                175, 46, 8, 1100, 7500, 166, 0,
                false, false, TargetRule.GROUND_ONLY,
                "Four cheap bodies that shred isolated slow attackers but fold to splash."));
        add(unit("pulse_guard", "Pulse Guard", 4, Role.SPLASH, 1,
                920, 105, 12, 1700, 8500, 112, 3300,
                false, false, TargetRule.GROUND_ONLY,
                "Durable area defender designed to punish clustered ground units."));
        add(unit("needlewing", "Needlewing", 3, Role.AIR, 1,
                520, 105, 9, 5000, 9000, 158, 0,
                true, true, TargetRule.ANY,
                "Air skirmisher that bypasses ground-only defenders but loses to ranged coverage."));
        add(unit("siege_walker", "Siege Walker", 5, Role.WIN_CONDITION, 1,
                1180, 196, 16, 1700, 12000, 105, 0,
                false, false, TargetRule.STRUCTURES_ONLY,
                "Ignores troops and marches for relays. Can be redirected by defensive buildings."));
        add(unit("runner", "Flux Runner", 2, Role.TEMPO, 1,
                390, 73, 8, 1300, 7200, 205, 0,
                false, false, TargetRule.GROUND_ONLY,
                "Cheap speed and cycle card for pressure, kiting and forcing awkward responses."));

        add(building("relay_beacon", "Relay Beacon", 4, Role.BUILDING,
                1120, 92, 10, 7600, 11200, 0,
                true, TargetRule.ANY, 360,
                "Temporary defensive structure that pulls attackers away from a relay and covers air."));
        add(building("coil_turret", "Coil Turret", 3, Role.BUILDING,
                760, 128, 13, 9200, 11800, 0,
                true, TargetRule.ANY, 270,
                "Cheaper ranged structure with less staying power; strong for tempo-positive defense."));

        add(spell("gravity_well", "Gravity Well", 3, Role.CONTROL,
                "Area control: damages enemies and slows survivors long enough to change targeting and trades."));
        add(spell("overclock", "Overclock", 2, Role.TEMPO,
                "Short lane-wide acceleration buff for surviving friendly troops; strongest after an efficient defense."));

        addDeck(new Deck(
                "counterforge",
                "Counterforge",
                "Defense → Counterpush",
                "Absorb with Bulwark/Beacon, win the trade with Duelist or Pulse Guard, then spend Overclock only on survivors.",
                "If you overspend on defense, the opponent can pressure the opposite lane while your cycle is heavy.",
                "bulwark", "duelist", "arc_slinger", "pulse_guard",
                "siege_walker", "relay_beacon", "gravity_well", "overclock"));

        addDeck(new Deck(
                "spark_cycle",
                "Spark Cycle",
                "Fast pressure / out-cycle",
                "Use cheap Runner and Swarm cards to force answers, rotate back to Siege Walker before the same hard counter returns.",
                "Splash plus a well-timed building can erase the cheap pressure and leave you low on durable defense.",
                "runner", "spark_swarm", "duelist", "needlewing",
                "siege_walker", "coil_turret", "gravity_well", "overclock"));

        addDeck(new Deck(
                "split_voltage",
                "Split Voltage",
                "Two-lane pressure / air-ground split",
                "Show Bulwark in one lane, then punish a heavy response with Needlewing or Runner in the other. Arc Slinger protects either lane from air.",
                "Requires disciplined Flux tracking; committing support to the wrong lane makes the opposite relay vulnerable.",
                "bulwark", "runner", "arc_slinger", "needlewing",
                "pulse_guard", "coil_turret", "gravity_well", "overclock"));
    }

    private RelaySiegeCards() { }

    public static Card card(String id) {
        Card card = CARDS.get(id);
        if (card == null) throw new IllegalArgumentException("Unknown Relay Siege card: " + id);
        return card;
    }

    public static Deck deck(String id) {
        Deck deck = DECKS.get(id);
        if (deck == null) throw new IllegalArgumentException("Unknown Relay Siege deck: " + id);
        return deck;
    }

    public static List<Card> allCards() {
        return Collections.unmodifiableList(new ArrayList<>(CARDS.values()));
    }

    public static List<Deck> starterDecks() {
        return Collections.unmodifiableList(new ArrayList<>(DECKS.values()));
    }

    private static void add(Card card) {
        if (CARDS.put(card.id, card) != null) throw new IllegalStateException("duplicate card " + card.id);
    }

    private static void addDeck(Deck deck) {
        if (DECKS.put(deck.id, deck) != null) throw new IllegalStateException("duplicate deck " + deck.id);
    }

    private static Card unit(
            String id, String name, int cost, Role role, int count,
            int hp, int damage, int attackTicks, int range, int aggroRange,
            int speed, int splashRadius, boolean airborne, boolean attacksAir,
            TargetRule targetRule, String description) {
        return new Card(id, name, cost, Kind.UNIT, role, count, hp, damage, attackTicks,
                range, aggroRange, speed, splashRadius, airborne, attacksAir,
                targetRule, 0, description);
    }

    private static Card building(
            String id, String name, int cost, Role role,
            int hp, int damage, int attackTicks, int range, int aggroRange,
            int splashRadius, boolean attacksAir, TargetRule targetRule,
            int lifetimeTicks, String description) {
        return new Card(id, name, cost, Kind.BUILDING, role, 1, hp, damage, attackTicks,
                range, aggroRange, 0, splashRadius, false, attacksAir,
                targetRule, lifetimeTicks, description);
    }

    private static Card spell(String id, String name, int cost, Role role, String description) {
        return new Card(id, name, cost, Kind.SPELL, role, 0, 0, 0, 0,
                0, 0, 0, 0, false, true, TargetRule.ANY, 0, description);
    }
}
