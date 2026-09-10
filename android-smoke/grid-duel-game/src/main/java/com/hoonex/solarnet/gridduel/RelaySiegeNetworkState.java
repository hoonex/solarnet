package com.hoonex.solarnet.gridduel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Perspective-safe authoritative snapshot for a remote Relay Siege player.
 *
 * The remote player's own hand/next card are included, but the opponent's hidden hand, next card
 * and Flux are deliberately not serialized. Public battlefield/objective state is complete enough
 * for rendering and reconnect recovery without running a second gameplay simulation on the client.
 */
public final class RelaySiegeNetworkState {
    public static final class Entity {
        public final long id;
        public final String cardId;
        public final RelaySiegeGame.Player owner;
        public final RelaySiegeGame.Lane lane;
        public final int position;
        public final int hp;
        public final int maxHp;
        public final boolean airborne;
        public final boolean building;
        public final int slowTicks;
        public final int overclockTicks;

        Entity(RelaySiegeGame.EntityView view) {
            this(view.id, view.cardId, view.owner, view.lane, view.position, view.hp, view.maxHp,
                    view.airborne, view.building, view.slowTicks, view.overclockTicks);
        }

        Entity(long id, String cardId, RelaySiegeGame.Player owner, RelaySiegeGame.Lane lane,
               int position, int hp, int maxHp, boolean airborne, boolean building,
               int slowTicks, int overclockTicks) {
            this.id = id;
            this.cardId = cardId;
            this.owner = owner;
            this.lane = lane;
            this.position = position;
            this.hp = hp;
            this.maxHp = maxHp;
            this.airborne = airborne;
            this.building = building;
            this.slowTicks = slowTicks;
            this.overclockTicks = overclockTicks;
        }
    }

    public final int tick;
    public final RelaySiegeGame.Player perspective;
    public final int yourFluxMilli;
    public final int yourFluxSpentMilli;
    public final List<String> yourHand;
    public final String yourNextCard;
    public final int sunLeftRelayHp;
    public final int sunRightRelayHp;
    public final int moonLeftRelayHp;
    public final int moonRightRelayHp;
    public final int sunCoreHp;
    public final int moonCoreHp;
    public final RelaySiegeGame.Player winner;
    public final RelaySiegeGame.EndReason endReason;
    public final List<Entity> entities;

    RelaySiegeNetworkState(
            int tick,
            RelaySiegeGame.Player perspective,
            int yourFluxMilli,
            int yourFluxSpentMilli,
            List<String> yourHand,
            String yourNextCard,
            int sunLeftRelayHp,
            int sunRightRelayHp,
            int moonLeftRelayHp,
            int moonRightRelayHp,
            int sunCoreHp,
            int moonCoreHp,
            RelaySiegeGame.Player winner,
            RelaySiegeGame.EndReason endReason,
            List<Entity> entities) {
        if (perspective != RelaySiegeGame.Player.SUN && perspective != RelaySiegeGame.Player.MOON)
            throw new IllegalArgumentException("perspective must be SUN or MOON");
        if (yourHand == null || yourHand.size() != 4) throw new IllegalArgumentException("four-card hand required");
        this.tick = tick;
        this.perspective = perspective;
        this.yourFluxMilli = yourFluxMilli;
        this.yourFluxSpentMilli = yourFluxSpentMilli;
        this.yourHand = Collections.unmodifiableList(new ArrayList<>(yourHand));
        this.yourNextCard = yourNextCard;
        this.sunLeftRelayHp = sunLeftRelayHp;
        this.sunRightRelayHp = sunRightRelayHp;
        this.moonLeftRelayHp = moonLeftRelayHp;
        this.moonRightRelayHp = moonRightRelayHp;
        this.sunCoreHp = sunCoreHp;
        this.moonCoreHp = moonCoreHp;
        this.winner = winner;
        this.endReason = endReason;
        this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
    }

    public static RelaySiegeNetworkState capture(RelaySiegeGame game, RelaySiegeGame.Player perspective) {
        if (game == null) throw new IllegalArgumentException("game is required");
        List<Entity> entities = new ArrayList<>();
        for (RelaySiegeGame.EntityView view : game.getEntities()) entities.add(new Entity(view));
        return new RelaySiegeNetworkState(
                game.getTick(),
                perspective,
                game.getFluxMilli(perspective),
                game.getFluxSpentMilli(perspective),
                game.getHand(perspective),
                game.getNextCard(perspective),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT),
                game.getRelayHp(RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT),
                game.getCoreHp(RelaySiegeGame.Player.SUN),
                game.getCoreHp(RelaySiegeGame.Player.MOON),
                game.getWinner(),
                game.getEndReason(),
                entities);
    }

    public int relayHp(RelaySiegeGame.Player player, RelaySiegeGame.Lane lane) {
        if (player == RelaySiegeGame.Player.SUN)
            return lane == RelaySiegeGame.Lane.LEFT ? sunLeftRelayHp : sunRightRelayHp;
        if (player == RelaySiegeGame.Player.MOON)
            return lane == RelaySiegeGame.Lane.LEFT ? moonLeftRelayHp : moonRightRelayHp;
        throw new IllegalArgumentException("NONE has no relay");
    }

    public int coreHp(RelaySiegeGame.Player player) {
        if (player == RelaySiegeGame.Player.SUN) return sunCoreHp;
        if (player == RelaySiegeGame.Player.MOON) return moonCoreHp;
        throw new IllegalArgumentException("NONE has no core");
    }

    public boolean isFinished() {
        return endReason != RelaySiegeGame.EndReason.NONE;
    }
}
