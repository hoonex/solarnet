package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic fixed-step combat simulation for Relay Siege.
 *
 * World coordinates use integer milli-arena units from 0 (SUN core) to 100000 (MOON core).
 * One simulation tick is 100 ms. No wall-clock or floating-point state is used by gameplay.
 */
public final class RelaySiegeGame {
    public enum Player {
        SUN(1), MOON(-1), NONE(0);
        final int direction;
        Player(int direction) { this.direction = direction; }
        public Player opponent() { return this == SUN ? MOON : this == MOON ? SUN : NONE; }
    }

    public enum Lane { LEFT, RIGHT }

    public enum EndReason { NONE, CORE_DESTROYED, TIME_TIEBREAK, DRAW }

    public static final int TICKS_PER_SECOND = 10;
    public static final int MAX_FLUX_MILLI = 10_000;
    public static final int START_FLUX_MILLI = 5_000;
    public static final int RELAY_MAX_HP = 2_650;
    public static final int CORE_MAX_HP = 4_200;
    public static final int SUN_RELAY_POSITION = 13_000;
    public static final int MOON_RELAY_POSITION = 87_000;
    public static final int SUN_CORE_POSITION = 2_000;
    public static final int MOON_CORE_POSITION = 98_000;
    public static final int BASE_SUN_DEPLOY_MAX = 42_000;
    public static final int BASE_MOON_DEPLOY_MIN = 58_000;
    public static final int ADVANCED_SUN_DEPLOY_MAX = 55_000;
    public static final int ADVANCED_MOON_DEPLOY_MIN = 45_000;
    public static final int REGULATION_TICKS = 1_800;
    public static final int MAX_MATCH_TICKS = 2_100;

    public static final class PlayResult {
        public final boolean accepted;
        public final String reason;
        private PlayResult(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }
        static PlayResult ok() { return new PlayResult(true, "OK"); }
        static PlayResult reject(String reason) { return new PlayResult(false, reason); }
    }

    public static final class BattleEvent {
        public final int tick;
        public final String type;
        public final Player player;
        public final Lane lane;
        public final String detail;

        BattleEvent(int tick, String type, Player player, Lane lane, String detail) {
            this.tick = tick;
            this.type = type;
            this.player = player;
            this.lane = lane;
            this.detail = detail;
        }

        @Override public String toString() {
            return tick + ":" + type + ":" + player + ":" + lane + ":" + detail;
        }
    }

    public static final class EntityView {
        public final long id;
        public final String cardId;
        public final Player owner;
        public final Lane lane;
        public final int position;
        public final int hp;
        public final int maxHp;
        public final boolean airborne;
        public final boolean building;
        public final int slowTicks;
        public final int overclockTicks;

        EntityView(Entity entity) {
            id = entity.id;
            cardId = entity.card.id;
            owner = entity.owner;
            lane = entity.lane;
            position = entity.position;
            hp = entity.hp;
            maxHp = entity.card.hp;
            airborne = entity.card.airborne;
            building = entity.card.kind == RelaySiegeCards.Kind.BUILDING;
            slowTicks = entity.slowTicks;
            overclockTicks = entity.overclockTicks;
        }
    }

    private static final class PlayerState {
        final RelaySiegeCards.Deck deck;
        final String[] hand = new String[4];
        int nextIndex;
        int fluxMilli = START_FLUX_MILLI;
        int fluxSpentMilli;
        int relayLeftHp = RELAY_MAX_HP;
        int relayRightHp = RELAY_MAX_HP;
        int coreHp = CORE_MAX_HP;

        PlayerState(RelaySiegeCards.Deck deck) {
            this.deck = deck;
            for (int i = 0; i < 4; i++) hand[i] = deck.cardIds.get(i);
            nextIndex = 4;
        }

        int relayHp(Lane lane) { return lane == Lane.LEFT ? relayLeftHp : relayRightHp; }
        void setRelayHp(Lane lane, int hp) {
            if (lane == Lane.LEFT) relayLeftHp = hp;
            else relayRightHp = hp;
        }
    }

    private static final class Entity {
        final long id;
        final RelaySiegeCards.Card card;
        final Player owner;
        final Lane lane;
        int position;
        int hp;
        int attackCooldown;
        int lifetimeTicks;
        int slowTicks;
        int overclockTicks;

        Entity(long id, RelaySiegeCards.Card card, Player owner, Lane lane, int position) {
            this.id = id;
            this.card = card;
            this.owner = owner;
            this.lane = lane;
            this.position = position;
            this.hp = card.hp;
            this.lifetimeTicks = card.lifetimeTicks;
        }

        boolean alive() { return hp > 0 && (card.lifetimeTicks == 0 || lifetimeTicks > 0); }
        boolean building() { return card.kind == RelaySiegeCards.Kind.BUILDING; }
    }

    private static final class ScheduledPlay {
        final int tick;
        final long sequence;
        final Player player;
        final String cardId;
        final Lane lane;
        final int position;

        ScheduledPlay(int tick, long sequence, Player player, String cardId, Lane lane, int position) {
            this.tick = tick;
            this.sequence = sequence;
            this.player = player;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
        }
    }

    private final PlayerState sun;
    private final PlayerState moon;
    private final long matchSeed;
    private final List<Entity> entities = new ArrayList<>();
    private final List<ScheduledPlay> scheduled = new ArrayList<>();
    private final List<BattleEvent> events = new ArrayList<>();
    private long nextEntityId = 1;
    private long nextCommandSequence = 1;
    private int tick;
    private Player winner = Player.NONE;
    private EndReason endReason = EndReason.NONE;

    public RelaySiegeGame(RelaySiegeCards.Deck sunDeck, RelaySiegeCards.Deck moonDeck, long matchSeed) {
        if (sunDeck == null || moonDeck == null) throw new IllegalArgumentException("both decks are required");
        this.sun = new PlayerState(sunDeck);
        this.moon = new PlayerState(moonDeck);
        this.matchSeed = matchSeed;
    }

    public int getTick() { return tick; }
    public long getMatchSeed() { return matchSeed; }
    public Player getWinner() { return winner; }
    public EndReason getEndReason() { return endReason; }
    public boolean isFinished() { return endReason != EndReason.NONE; }
    public int getFluxMilli(Player player) { return state(player).fluxMilli; }
    public int getFluxSpentMilli(Player player) { return state(player).fluxSpentMilli; }
    public int getRelayHp(Player player, Lane lane) { return state(player).relayHp(lane); }
    public int getCoreHp(Player player) { return state(player).coreHp; }

    public List<String> getHand(Player player) {
        PlayerState state = state(player);
        List<String> result = new ArrayList<>(4);
        Collections.addAll(result, state.hand);
        return Collections.unmodifiableList(result);
    }

    public String getNextCard(Player player) {
        PlayerState state = state(player);
        return state.deck.cardIds.get(state.nextIndex);
    }

    public List<EntityView> getEntities() {
        List<EntityView> result = new ArrayList<>();
        for (Entity entity : entities) if (entity.alive()) result.add(new EntityView(entity));
        result.sort(Comparator.comparingLong(view -> view.id));
        return Collections.unmodifiableList(result);
    }

    public List<BattleEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** Queue a deployment to be resolved at a deterministic simulation tick. */
    public void schedulePlay(int atTick, Player player, String cardId, Lane lane, int position) {
        if (atTick < tick) throw new IllegalArgumentException("cannot schedule in the past");
        requirePlayer(player);
        RelaySiegeCards.card(cardId);
        if (lane == null) throw new IllegalArgumentException("lane is required");
        scheduled.add(new ScheduledPlay(atTick, nextCommandSequence++, player, cardId, lane, position));
    }

    /** Interactive callers can submit for the next fixed step so both peers share a clear boundary. */
    public void submitPlay(Player player, String cardId, Lane lane, int position) {
        schedulePlay(tick + 1, player, cardId, lane, position);
    }

    /** Immediate play is useful for deterministic scenarios/tests and still obeys hand/cost/deployment rules. */
    public PlayResult tryPlay(Player player, String cardId, Lane lane, int position) {
        if (isFinished()) return PlayResult.reject("MATCH_FINISHED");
        requirePlayer(player);
        if (lane == null) return PlayResult.reject("LANE_REQUIRED");
        RelaySiegeCards.Card card;
        try { card = RelaySiegeCards.card(cardId); }
        catch (IllegalArgumentException e) { return PlayResult.reject("UNKNOWN_CARD"); }

        PlayerState state = state(player);
        int handIndex = handIndex(state, cardId);
        if (handIndex < 0) return PlayResult.reject("CARD_NOT_IN_HAND");
        int costMilli = card.fluxCost * 1_000;
        if (state.fluxMilli < costMilli) return PlayResult.reject("NOT_ENOUGH_FLUX");
        if (!validPosition(player, card, lane, position)) return PlayResult.reject("INVALID_DEPLOYMENT_POSITION");

        state.fluxMilli -= costMilli;
        state.fluxSpentMilli += costMilli;
        cycleHand(state, handIndex);

        if (card.kind == RelaySiegeCards.Kind.UNIT) {
            spawnUnits(card, player, lane, position);
        } else if (card.kind == RelaySiegeCards.Kind.BUILDING) {
            spawnEntity(card, player, lane, position);
        } else {
            applySpell(card, player, lane, position);
        }
        events.add(new BattleEvent(tick, "PLAY", player, lane, card.id + "@" + position));
        return PlayResult.ok();
    }

    public void advanceTicks(int count) {
        if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        for (int i = 0; i < count && !isFinished(); i++) step();
    }

    private void step() {
        tick++;
        regenerateFlux(sun);
        regenerateFlux(moon);
        resolveScheduledForTick();
        updateStatusesAndLifetime();

        // Stable entity-id order makes targeting and simultaneous-looking exchanges reproducible.
        entities.sort(Comparator.comparingLong(entity -> entity.id));
        List<Entity> attackers = new ArrayList<>(entities);
        for (Entity entity : attackers) {
            if (!entity.alive() || isFinished()) continue;
            if (entity.attackCooldown > 0) entity.attackCooldown--;
            if (entity.building()) updateBuilding(entity);
            else updateUnit(entity);
        }

        removeDeadEntities();
        if (!isFinished() && tick >= MAX_MATCH_TICKS) resolveTimeTiebreak();
    }

    private void resolveScheduledForTick() {
        List<ScheduledPlay> now = new ArrayList<>();
        Iterator<ScheduledPlay> iterator = scheduled.iterator();
        while (iterator.hasNext()) {
            ScheduledPlay play = iterator.next();
            if (play.tick == tick) {
                now.add(play);
                iterator.remove();
            }
        }
        now.sort((a, b) -> {
            int playerOrder = Integer.compare(a.player.ordinal(), b.player.ordinal());
            if (playerOrder != 0) return playerOrder;
            return Long.compare(a.sequence, b.sequence);
        });
        for (ScheduledPlay play : now) {
            PlayResult result = tryPlay(play.player, play.cardId, play.lane, play.position);
            if (!result.accepted)
                events.add(new BattleEvent(tick, "PLAY_REJECTED", play.player, play.lane,
                        play.cardId + ":" + result.reason));
        }
    }

    private void regenerateFlux(PlayerState state) {
        int regen;
        if (tick < 900) regen = 38;          // 0.38 Flux / second
        else if (tick < REGULATION_TICKS) regen = 58;
        else regen = 82;                     // overtime acceleration
        state.fluxMilli = Math.min(MAX_FLUX_MILLI, state.fluxMilli + regen);
    }

    private void updateStatusesAndLifetime() {
        for (Entity entity : entities) {
            if (entity.slowTicks > 0) entity.slowTicks--;
            if (entity.overclockTicks > 0) entity.overclockTicks--;
            if (entity.card.lifetimeTicks > 0 && entity.lifetimeTicks > 0) entity.lifetimeTicks--;
        }
    }

    private void updateBuilding(Entity building) {
        Entity target = nearestEnemyEntity(building, building.card.range, false);
        if (target != null && building.attackCooldown <= 0) {
            dealEntityDamage(building, target, effectiveDamage(building));
            building.attackCooldown = effectiveAttackTicks(building);
        }
    }

    private void updateUnit(Entity unit) {
        int aggro = unit.card.aggroRange;
        Entity target = nearestEnemyEntity(unit, aggro, unit.card.targetRule == RelaySiegeCards.TargetRule.STRUCTURES_ONLY);
        if (target != null) {
            int distance = Math.abs(target.position - unit.position);
            if (distance <= unit.card.range) {
                if (unit.attackCooldown <= 0) {
                    dealEntityDamage(unit, target, effectiveDamage(unit));
                    unit.attackCooldown = effectiveAttackTicks(unit);
                }
            } else {
                moveToward(unit, target.position);
            }
            return;
        }

        Player enemy = unit.owner.opponent();
        int objectivePosition = relayAlive(enemy, unit.lane)
                ? relayPosition(enemy)
                : corePosition(enemy);
        int distance = Math.abs(objectivePosition - unit.position);
        if (distance <= unit.card.range) {
            if (unit.attackCooldown <= 0) {
                attackObjective(unit, enemy, unit.lane, effectiveDamage(unit));
                unit.attackCooldown = effectiveAttackTicks(unit);
            }
        } else {
            moveToward(unit, objectivePosition);
        }
    }

    private Entity nearestEnemyEntity(Entity source, int maxDistance, boolean buildingsOnly) {
        Entity best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Entity candidate : entities) {
            if (!candidate.alive() || candidate.owner == source.owner || candidate.lane != source.lane) continue;
            if (buildingsOnly && !candidate.building()) continue;
            if (!canAttack(source, candidate)) continue;
            int distance = Math.abs(candidate.position - source.position);
            if (distance > maxDistance) continue;
            if (distance < bestDistance || (distance == bestDistance && candidate.id < best.id)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean canAttack(Entity attacker, Entity target) {
        if (attacker.card.targetRule == RelaySiegeCards.TargetRule.STRUCTURES_ONLY && !target.building()) return false;
        if (target.card.airborne && !attacker.card.attacksAir) return false;
        if (attacker.card.targetRule == RelaySiegeCards.TargetRule.GROUND_ONLY && target.card.airborne) return false;
        return true;
    }

    private void moveToward(Entity entity, int targetPosition) {
        if (entity.card.speed <= 0) return;
        int speed = entity.card.speed;
        if (entity.slowTicks > 0) speed = speed * 68 / 100;
        if (entity.overclockTicks > 0) speed = speed * 125 / 100;
        int delta = targetPosition > entity.position ? speed : -speed;
        if (Math.abs(targetPosition - entity.position) < Math.abs(delta)) entity.position = targetPosition;
        else entity.position = clamp(entity.position + delta, 0, 100_000);
    }

    private int effectiveDamage(Entity entity) {
        int damage = entity.card.damage;
        if (entity.overclockTicks > 0) damage = damage * 118 / 100;
        return Math.max(1, damage);
    }

    private int effectiveAttackTicks(Entity entity) {
        int ticks = Math.max(1, entity.card.attackTicks);
        if (entity.overclockTicks > 0) ticks = Math.max(1, ticks * 82 / 100);
        if (entity.slowTicks > 0) ticks = Math.max(1, ticks * 118 / 100);
        return ticks;
    }

    private void dealEntityDamage(Entity attacker, Entity target, int damage) {
        target.hp -= damage;
        if (attacker.card.splashRadius > 0) {
            for (Entity other : entities) {
                if (other == target || !other.alive() || other.owner == attacker.owner || other.lane != attacker.lane) continue;
                if (other.card.airborne && !attacker.card.attacksAir) continue;
                if (Math.abs(other.position - target.position) <= attacker.card.splashRadius)
                    other.hp -= Math.max(1, damage * 70 / 100);
            }
        }
        if (target.hp <= 0)
            events.add(new BattleEvent(tick, "ENTITY_DESTROYED", attacker.owner, attacker.lane,
                    target.card.id + "#" + target.id));
    }

    private void attackObjective(Entity attacker, Player defender, Lane lane, int damage) {
        PlayerState defendingState = state(defender);
        if (relayAlive(defender, lane)) {
            int before = defendingState.relayHp(lane);
            int after = Math.max(0, before - damage);
            defendingState.setRelayHp(lane, after);
            events.add(new BattleEvent(tick, "RELAY_DAMAGE", attacker.owner, lane,
                    defender + ":" + before + "->" + after));
            if (after == 0)
                events.add(new BattleEvent(tick, "RELAY_DESTROYED", attacker.owner, lane, defender.name()));
            return;
        }

        int before = defendingState.coreHp;
        defendingState.coreHp = Math.max(0, before - damage);
        events.add(new BattleEvent(tick, "CORE_DAMAGE", attacker.owner, lane,
                defender + ":" + before + "->" + defendingState.coreHp));
        if (defendingState.coreHp == 0) {
            winner = attacker.owner;
            endReason = EndReason.CORE_DESTROYED;
            events.add(new BattleEvent(tick, "MATCH_END", winner, lane, endReason.name()));
        }
    }

    private void removeDeadEntities() {
        Iterator<Entity> iterator = entities.iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (!entity.alive()) iterator.remove();
        }
    }

    private void applySpell(RelaySiegeCards.Card card, Player player, Lane lane, int position) {
        if ("gravity_well".equals(card.id)) {
            for (Entity entity : entities) {
                if (!entity.alive() || entity.owner == player || entity.lane != lane) continue;
                if (Math.abs(entity.position - position) > 8_500) continue;
                entity.hp -= 165;
                entity.slowTicks = Math.max(entity.slowTicks, 42);
            }
            removeDeadEntities();
            return;
        }
        if ("overclock".equals(card.id)) {
            for (Entity entity : entities) {
                if (!entity.alive() || entity.owner != player || entity.lane != lane) continue;
                if (Math.abs(entity.position - position) > 11_000) continue;
                entity.overclockTicks = Math.max(entity.overclockTicks, 50);
            }
            return;
        }
        throw new IllegalStateException("Unhandled spell: " + card.id);
    }

    private void spawnUnits(RelaySiegeCards.Card card, Player player, Lane lane, int position) {
        int count = Math.max(1, card.count);
        for (int i = 0; i < count; i++) {
            int spacing = (i - (count - 1) / 2) * 260;
            spawnEntity(card, player, lane, clamp(position + spacing, 0, 100_000));
        }
    }

    private Entity spawnEntity(RelaySiegeCards.Card card, Player player, Lane lane, int position) {
        Entity entity = new Entity(nextEntityId++, card, player, lane, position);
        entities.add(entity);
        return entity;
    }

    private boolean validPosition(Player player, RelaySiegeCards.Card card, Lane lane, int position) {
        if (position < 0 || position > 100_000) return false;
        if (card.kind == RelaySiegeCards.Kind.SPELL) return true;
        if (player == Player.SUN) {
            int max = relayAlive(Player.MOON, lane) ? BASE_SUN_DEPLOY_MAX : ADVANCED_SUN_DEPLOY_MAX;
            return position >= 4_000 && position <= max;
        }
        int min = relayAlive(Player.SUN, lane) ? BASE_MOON_DEPLOY_MIN : ADVANCED_MOON_DEPLOY_MIN;
        return position >= min && position <= 96_000;
    }

    private void cycleHand(PlayerState state, int handIndex) {
        state.hand[handIndex] = state.deck.cardIds.get(state.nextIndex);
        state.nextIndex = (state.nextIndex + 1) % state.deck.cardIds.size();
    }

    private int handIndex(PlayerState state, String cardId) {
        for (int i = 0; i < state.hand.length; i++) if (state.hand[i].equals(cardId)) return i;
        return -1;
    }

    private void resolveTimeTiebreak() {
        long sunIntegrity = integrityScore(Player.SUN);
        long moonIntegrity = integrityScore(Player.MOON);
        if (sunIntegrity > moonIntegrity) {
            winner = Player.SUN;
            endReason = EndReason.TIME_TIEBREAK;
        } else if (moonIntegrity > sunIntegrity) {
            winner = Player.MOON;
            endReason = EndReason.TIME_TIEBREAK;
        } else {
            // Damage equality is allowed, but the simulation still terminates deterministically.
            winner = Player.NONE;
            endReason = EndReason.DRAW;
        }
        events.add(new BattleEvent(tick, "MATCH_END", winner, Lane.LEFT, endReason.name()));
    }

    private long integrityScore(Player player) {
        PlayerState state = state(player);
        return state.coreHp * 10_000L
                + state.relayLeftHp * 1_000L
                + state.relayRightHp * 1_000L
                + state.fluxMilli;
    }

    private boolean relayAlive(Player player, Lane lane) { return state(player).relayHp(lane) > 0; }
    private int relayPosition(Player player) { return player == Player.SUN ? SUN_RELAY_POSITION : MOON_RELAY_POSITION; }
    private int corePosition(Player player) { return player == Player.SUN ? SUN_CORE_POSITION : MOON_CORE_POSITION; }

    private PlayerState state(Player player) {
        if (player == Player.SUN) return sun;
        if (player == Player.MOON) return moon;
        throw new IllegalArgumentException("NONE has no player state");
    }

    private static void requirePlayer(Player player) {
        if (player != Player.SUN && player != Player.MOON) throw new IllegalArgumentException("player must be SUN or MOON");
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    // Package-private scenario hooks. They bypass hand/Flux only for deterministic interaction tests and guide demos.
    void scenarioSetFlux(Player player, int fluxMilli) {
        state(player).fluxMilli = clamp(fluxMilli, 0, MAX_FLUX_MILLI);
    }

    long scenarioSpawn(String cardId, Player player, Lane lane, int position) {
        RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
        if (card.kind == RelaySiegeCards.Kind.SPELL) throw new IllegalArgumentException("cannot spawn a spell");
        return spawnEntity(card, player, lane, position).id;
    }

    public String canonicalState() {
        StringBuilder out = new StringBuilder(1024);
        out.append("t=").append(tick)
                .append(";w=").append(winner)
                .append(";e=").append(endReason)
                .append(";sun=").append(sun.fluxMilli).append(',').append(sun.fluxSpentMilli)
                .append(',').append(sun.relayLeftHp).append(',').append(sun.relayRightHp).append(',').append(sun.coreHp)
                .append(";moon=").append(moon.fluxMilli).append(',').append(moon.fluxSpentMilli)
                .append(',').append(moon.relayLeftHp).append(',').append(moon.relayRightHp).append(',').append(moon.coreHp)
                .append(";sh=");
        for (String card : sun.hand) out.append(card).append(',');
        out.append("n").append(sun.nextIndex).append(";mh=");
        for (String card : moon.hand) out.append(card).append(',');
        out.append("n").append(moon.nextIndex).append(";entities=");

        List<Entity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingLong(entity -> entity.id));
        for (Entity entity : sorted) {
            if (!entity.alive()) continue;
            out.append(entity.id).append(':').append(entity.card.id).append(':').append(entity.owner)
                    .append(':').append(entity.lane).append(':').append(entity.position).append(':').append(entity.hp)
                    .append(':').append(entity.attackCooldown).append(':').append(entity.lifetimeTicks)
                    .append(':').append(entity.slowTicks).append(':').append(entity.overclockTicks).append('|');
        }
        return out.toString();
    }
}
