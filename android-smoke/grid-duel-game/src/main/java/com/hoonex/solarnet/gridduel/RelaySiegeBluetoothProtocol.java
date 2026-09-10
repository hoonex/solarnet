package com.hoonex.solarnet.gridduel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned, bounded protocol carried inside one framed Bluetooth Classic payload. */
public final class RelaySiegeBluetoothProtocol {
    private static final int MAGIC = 0x52534E31; // RSN1
    public static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 128;
    private static final int MAX_STATE_BYTES = 512 * 1024;

    public enum Type {
        JOIN(1), START(2), READY(3), PLAY_REQUEST(4), PLAY_QUEUED(5),
        PLAY_REJECTED(6), STATE(7), PAUSE(8), ERROR(9);

        final int wire;
        Type(int wire) { this.wire = wire; }

        static Type fromWire(int wire) {
            for (Type type : values()) if (type.wire == wire) return type;
            throw new IllegalArgumentException("unknown Relay Siege frame type " + wire);
        }
    }

    public static final class Frame {
        public final Type type;
        public final String sessionId;
        public final String deckId;
        public final String resumeSessionId;
        public final long matchSeed;
        public final String sunDeckId;
        public final String moonDeckId;
        public final long clientSequence;
        public final long ackedClientSequence;
        public final int observedTick;
        public final int applyTick;
        public final String cardId;
        public final RelaySiegeGame.Lane lane;
        public final int position;
        public final String reason;
        public final RelaySiegeNetworkState state;
        public final byte[] stateHash;

        private Frame(Type type, String sessionId, String deckId, String resumeSessionId,
                      long matchSeed, String sunDeckId, String moonDeckId,
                      long clientSequence, long ackedClientSequence, int observedTick, int applyTick,
                      String cardId, RelaySiegeGame.Lane lane, int position, String reason,
                      RelaySiegeNetworkState state, byte[] stateHash) {
            this.type = type;
            this.sessionId = sessionId;
            this.deckId = deckId;
            this.resumeSessionId = resumeSessionId;
            this.matchSeed = matchSeed;
            this.sunDeckId = sunDeckId;
            this.moonDeckId = moonDeckId;
            this.clientSequence = clientSequence;
            this.ackedClientSequence = ackedClientSequence;
            this.observedTick = observedTick;
            this.applyTick = applyTick;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
            this.reason = reason;
            this.state = state;
            this.stateHash = stateHash == null ? null : stateHash.clone();
        }
    }

    private RelaySiegeBluetoothProtocol() { }

    public static byte[] encodeJoin(String clientDeckId, String resumeSessionId) {
        RelaySiegeCards.deck(clientDeckId);
        return encode(Type.JOIN, out -> {
            writeString(out, clientDeckId);
            writeOptionalString(out, resumeSessionId);
        });
    }

    public static byte[] encodeStart(String sessionId, long seed, String sunDeckId, String moonDeckId) {
        requireSession(sessionId);
        RelaySiegeCards.deck(sunDeckId);
        RelaySiegeCards.deck(moonDeckId);
        return encode(Type.START, out -> {
            writeString(out, sessionId);
            out.writeLong(seed);
            writeString(out, sunDeckId);
            writeString(out, moonDeckId);
        });
    }

    public static byte[] encodeReady(String sessionId) {
        requireSession(sessionId);
        return encode(Type.READY, out -> writeString(out, sessionId));
    }

    public static byte[] encodePlayRequest(String sessionId, long sequence, int observedTick,
                                           String cardId, RelaySiegeGame.Lane lane, int position) {
        requireSession(sessionId);
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        if (observedTick < 0) throw new IllegalArgumentException("observed tick must be non-negative");
        RelaySiegeCards.card(cardId);
        if (lane == null) throw new IllegalArgumentException("lane is required");
        return encode(Type.PLAY_REQUEST, out -> {
            writeString(out, sessionId);
            out.writeLong(sequence);
            out.writeInt(observedTick);
            writeString(out, cardId);
            out.writeByte(lane.ordinal());
            out.writeInt(position);
        });
    }

    public static byte[] encodePlayQueued(String sessionId, long sequence, int applyTick) {
        requireSession(sessionId);
        if (sequence <= 0 || applyTick < 0) throw new IllegalArgumentException("invalid queued play");
        return encode(Type.PLAY_QUEUED, out -> {
            writeString(out, sessionId);
            out.writeLong(sequence);
            out.writeInt(applyTick);
        });
    }

    public static byte[] encodePlayRejected(String sessionId, long sequence, int hostTick, String reason) {
        requireSession(sessionId);
        if (sequence <= 0 || hostTick < 0) throw new IllegalArgumentException("invalid rejected play");
        return encode(Type.PLAY_REJECTED, out -> {
            writeString(out, sessionId);
            out.writeLong(sequence);
            out.writeInt(hostTick);
            writeString(out, reason == null ? "REJECTED" : reason);
        });
    }

    public static byte[] encodeState(String sessionId, long ackedClientSequence, RelaySiegeNetworkState state) {
        requireSession(sessionId);
        if (ackedClientSequence < 0) throw new IllegalArgumentException("acked sequence must be non-negative");
        byte[] snapshot = RelaySiegeNetworkStateCodec.encode(state);
        byte[] hash = RelaySiegeNetworkStateCodec.sha256(snapshot);
        return encode(Type.STATE, out -> {
            writeString(out, sessionId);
            out.writeLong(ackedClientSequence);
            out.writeInt(snapshot.length);
            out.write(snapshot);
            out.writeByte(hash.length);
            out.write(hash);
        });
    }

    public static byte[] encodePause(String sessionId, String reason) {
        requireSession(sessionId);
        return encode(Type.PAUSE, out -> {
            writeString(out, sessionId);
            writeString(out, reason == null ? "PAUSED" : reason);
        });
    }

    public static byte[] encodeError(String reason) {
        return encode(Type.ERROR, out -> writeString(out, reason == null ? "PROTOCOL_ERROR" : reason));
    }

    public static Frame decode(byte[] payload) {
        if (payload == null || payload.length < 7) throw new IllegalArgumentException("truncated Relay Siege frame");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a Relay Siege Bluetooth frame");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IllegalArgumentException("unsupported Relay Siege protocol version " + version);
            Type type = Type.fromWire(in.readUnsignedByte());
            Frame frame;
            switch (type) {
                case JOIN: {
                    String deckId = readString(in);
                    RelaySiegeCards.deck(deckId);
                    String resume = readOptionalString(in);
                    frame = frame(type, null, deckId, resume, 0, null, null, 0, 0, 0, 0, null, null, 0, null, null, null);
                    break;
                }
                case START: {
                    String session = readString(in);
                    long seed = in.readLong();
                    String sunDeck = readString(in);
                    String moonDeck = readString(in);
                    RelaySiegeCards.deck(sunDeck);
                    RelaySiegeCards.deck(moonDeck);
                    frame = frame(type, session, null, null, seed, sunDeck, moonDeck, 0, 0, 0, 0, null, null, 0, null, null, null);
                    break;
                }
                case READY: {
                    frame = frame(type, readString(in), null, null, 0, null, null, 0, 0, 0, 0, null, null, 0, null, null, null);
                    break;
                }
                case PLAY_REQUEST: {
                    String session = readString(in);
                    long seq = in.readLong();
                    int observed = in.readInt();
                    String card = readString(in);
                    RelaySiegeCards.card(card);
                    RelaySiegeGame.Lane lane = lane(in.readUnsignedByte());
                    int position = in.readInt();
                    if (seq <= 0 || observed < 0 || position < 0 || position > 100_000)
                        throw new IllegalArgumentException("invalid play request values");
                    frame = frame(type, session, null, null, 0, null, null, seq, 0, observed, 0, card, lane, position, null, null, null);
                    break;
                }
                case PLAY_QUEUED: {
                    String session = readString(in);
                    long seq = in.readLong();
                    int applyTick = in.readInt();
                    if (seq <= 0 || applyTick < 0) throw new IllegalArgumentException("invalid queued play values");
                    frame = frame(type, session, null, null, 0, null, null, seq, 0, 0, applyTick, null, null, 0, null, null, null);
                    break;
                }
                case PLAY_REJECTED: {
                    String session = readString(in);
                    long seq = in.readLong();
                    int hostTick = in.readInt();
                    String reason = readString(in);
                    if (seq <= 0 || hostTick < 0) throw new IllegalArgumentException("invalid rejected play values");
                    frame = frame(type, session, null, null, 0, null, null, seq, 0, hostTick, 0, null, null, 0, reason, null, null);
                    break;
                }
                case STATE: {
                    String session = readString(in);
                    long ack = in.readLong();
                    int length = in.readInt();
                    if (ack < 0 || length <= 0 || length > MAX_STATE_BYTES) throw new IllegalArgumentException("invalid state frame values");
                    byte[] snapshot = new byte[length];
                    in.readFully(snapshot);
                    int hashLength = in.readUnsignedByte();
                    if (hashLength != 32) throw new IllegalArgumentException("invalid snapshot hash length");
                    byte[] hash = new byte[hashLength];
                    in.readFully(hash);
                    byte[] actual = RelaySiegeNetworkStateCodec.sha256(snapshot);
                    if (!RelaySiegeNetworkStateCodec.hashesEqual(hash, actual))
                        throw new IllegalArgumentException("snapshot hash mismatch");
                    RelaySiegeNetworkState state = RelaySiegeNetworkStateCodec.decode(snapshot);
                    frame = frame(type, session, null, null, 0, null, null, 0, ack, 0, 0, null, null, 0, null, state, hash);
                    break;
                }
                case PAUSE: {
                    frame = frame(type, readString(in), null, null, 0, null, null, 0, 0, 0, 0, null, null, 0, readString(in), null, null);
                    break;
                }
                case ERROR: {
                    frame = frame(type, null, null, null, 0, null, null, 0, 0, 0, 0, null, null, 0, readString(in), null, null);
                    break;
                }
                default:
                    throw new IllegalArgumentException("unsupported frame type");
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing Relay Siege frame bytes");
            return frame;
        } catch (EOFException e) {
            throw new IllegalArgumentException("truncated Relay Siege frame", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid Relay Siege frame", e);
        }
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private static byte[] encode(Type type, Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(type.wire);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory protocol encode failed", impossible);
        }
    }

    private static Frame frame(Type type, String sessionId, String deckId, String resumeSessionId,
                               long matchSeed, String sunDeckId, String moonDeckId,
                               long clientSequence, long ackedClientSequence,
                               int observedTick, int applyTick, String cardId,
                               RelaySiegeGame.Lane lane, int position, String reason,
                               RelaySiegeNetworkState state, byte[] stateHash) {
        return new Frame(type, sessionId, deckId, resumeSessionId, matchSeed, sunDeckId, moonDeckId,
                clientSequence, ackedClientSequence, observedTick, applyTick,
                cardId, lane, position, reason, state, stateHash);
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) throw new IllegalArgumentException("session ID is required");
    }

    private static void writeOptionalString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null && !value.isEmpty());
        if (value != null && !value.isEmpty()) writeString(out, value);
    }

    private static String readOptionalString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
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

    private static RelaySiegeGame.Lane lane(int ordinal) {
        RelaySiegeGame.Lane[] values = RelaySiegeGame.Lane.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid lane");
        return values[ordinal];
    }
}
