package com.hoonex.solarnet.gridduel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Canonical bounded binary codec for authoritative Relay Siege rendering snapshots. */
public final class RelaySiegeNetworkStateCodec {
    private static final int MAGIC = 0x52535331; // RSS1
    private static final int MAX_STRING_BYTES = 128;
    private static final int MAX_ENTITIES = 512;
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;

    private RelaySiegeNetworkStateCodec() { }

    public static byte[] encode(RelaySiegeNetworkState state) {
        if (state == null) throw new IllegalArgumentException("state is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(state.tick);
            out.writeByte(state.perspective.ordinal());
            out.writeInt(state.yourFluxMilli);
            out.writeInt(state.yourFluxSpentMilli);
            out.writeByte(state.yourHand.size());
            for (String cardId : state.yourHand) writeString(out, cardId);
            writeString(out, state.yourNextCard);
            out.writeInt(state.sunLeftRelayHp);
            out.writeInt(state.sunRightRelayHp);
            out.writeInt(state.moonLeftRelayHp);
            out.writeInt(state.moonRightRelayHp);
            out.writeInt(state.sunCoreHp);
            out.writeInt(state.moonCoreHp);
            out.writeByte(state.winner.ordinal());
            out.writeByte(state.endReason.ordinal());
            if (state.entities.size() > MAX_ENTITIES) throw new IllegalArgumentException("too many entities");
            out.writeShort(state.entities.size());
            for (RelaySiegeNetworkState.Entity entity : state.entities) {
                out.writeLong(entity.id);
                writeString(out, entity.cardId);
                out.writeByte(entity.owner.ordinal());
                out.writeByte(entity.lane.ordinal());
                out.writeInt(entity.position);
                out.writeInt(entity.hp);
                out.writeInt(entity.maxHp);
                int flags = (entity.airborne ? 1 : 0) | (entity.building ? 2 : 0);
                out.writeByte(flags);
                out.writeInt(entity.slowTicks);
                out.writeInt(entity.overclockTicks);
            }
            out.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("snapshot too large");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory snapshot encode failed", impossible);
        }
    }

    public static RelaySiegeNetworkState decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("invalid snapshot size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a Relay Siege snapshot");
            int tick = nonNegative(in.readInt(), "tick");
            RelaySiegeGame.Player perspective = player(in.readUnsignedByte(), false);
            int flux = bounded(in.readInt(), 0, RelaySiegeGame.MAX_FLUX_MILLI, "flux");
            int fluxSpent = nonNegative(in.readInt(), "flux spent");
            int handCount = in.readUnsignedByte();
            if (handCount != 4) throw new IllegalArgumentException("snapshot hand must contain four cards");
            List<String> hand = new ArrayList<>(4);
            for (int i = 0; i < handCount; i++) {
                String cardId = readString(in);
                RelaySiegeCards.card(cardId);
                hand.add(cardId);
            }
            String nextCard = readString(in);
            RelaySiegeCards.card(nextCard);
            int sunLeft = hp(in.readInt(), RelaySiegeGame.RELAY_MAX_HP, "sun left relay");
            int sunRight = hp(in.readInt(), RelaySiegeGame.RELAY_MAX_HP, "sun right relay");
            int moonLeft = hp(in.readInt(), RelaySiegeGame.RELAY_MAX_HP, "moon left relay");
            int moonRight = hp(in.readInt(), RelaySiegeGame.RELAY_MAX_HP, "moon right relay");
            int sunCore = hp(in.readInt(), RelaySiegeGame.CORE_MAX_HP, "sun core");
            int moonCore = hp(in.readInt(), RelaySiegeGame.CORE_MAX_HP, "moon core");
            RelaySiegeGame.Player winner = player(in.readUnsignedByte(), true);
            RelaySiegeGame.EndReason endReason = endReason(in.readUnsignedByte());
            int entityCount = in.readUnsignedShort();
            if (entityCount > MAX_ENTITIES) throw new IllegalArgumentException("too many snapshot entities");
            List<RelaySiegeNetworkState.Entity> entities = new ArrayList<>(entityCount);
            long previousId = 0;
            for (int i = 0; i < entityCount; i++) {
                long id = in.readLong();
                if (id <= 0 || id <= previousId) throw new IllegalArgumentException("entity IDs must be positive and increasing");
                previousId = id;
                String cardId = readString(in);
                RelaySiegeCards.Card card = RelaySiegeCards.card(cardId);
                RelaySiegeGame.Player owner = player(in.readUnsignedByte(), false);
                RelaySiegeGame.Lane lane = lane(in.readUnsignedByte());
                int position = bounded(in.readInt(), 0, 100_000, "entity position");
                int entityHp = in.readInt();
                int maxHp = in.readInt();
                if (maxHp != card.hp || entityHp <= 0 || entityHp > maxHp)
                    throw new IllegalArgumentException("invalid entity hp");
                int flags = in.readUnsignedByte();
                if ((flags & ~3) != 0) throw new IllegalArgumentException("invalid entity flags");
                boolean airborne = (flags & 1) != 0;
                boolean building = (flags & 2) != 0;
                if (airborne != card.airborne || building != (card.kind == RelaySiegeCards.Kind.BUILDING))
                    throw new IllegalArgumentException("entity flags disagree with card catalog");
                int slowTicks = nonNegative(in.readInt(), "slow ticks");
                int overclockTicks = nonNegative(in.readInt(), "overclock ticks");
                entities.add(new RelaySiegeNetworkState.Entity(id, cardId, owner, lane, position,
                        entityHp, maxHp, airborne, building, slowTicks, overclockTicks));
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing snapshot bytes");
            if (endReason == RelaySiegeGame.EndReason.NONE && winner != RelaySiegeGame.Player.NONE)
                throw new IllegalArgumentException("unfinished snapshot has a winner");
            return new RelaySiegeNetworkState(tick, perspective, flux, fluxSpent, hand, nextCard,
                    sunLeft, sunRight, moonLeft, moonRight, sunCore, moonCore, winner, endReason, entities);
        } catch (EOFException e) {
            throw new IllegalArgumentException("truncated snapshot", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid snapshot", e);
        }
    }

    public static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static boolean hashesEqual(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left == null ? new byte[0] : left, right == null ? new byte[0] : right);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IllegalArgumentException("string is required");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("invalid string length");
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedByte();
        if (length <= 0 || length > MAX_STRING_BYTES) throw new IllegalArgumentException("invalid string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static RelaySiegeGame.Player player(int ordinal, boolean allowNone) {
        RelaySiegeGame.Player[] values = RelaySiegeGame.Player.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid player");
        RelaySiegeGame.Player player = values[ordinal];
        if (!allowNone && player == RelaySiegeGame.Player.NONE) throw new IllegalArgumentException("NONE player not allowed");
        return player;
    }

    private static RelaySiegeGame.Lane lane(int ordinal) {
        RelaySiegeGame.Lane[] values = RelaySiegeGame.Lane.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid lane");
        return values[ordinal];
    }

    private static RelaySiegeGame.EndReason endReason(int ordinal) {
        RelaySiegeGame.EndReason[] values = RelaySiegeGame.EndReason.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid end reason");
        return values[ordinal];
    }

    private static int hp(int value, int max, String field) { return bounded(value, 0, max, field); }

    private static int nonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    private static int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " out of range");
        return value;
    }
}
