package com.hoonex.solarnet.gridduel;

import java.util.List;

/**
 * Transport-neutral two-player session used by the Android Bluetooth Classic UI.
 *
 * HOST is always SUN and is the only process that advances RelaySiegeGame. CLIENT is MOON and sends
 * sequenced deployment requests. The client renders full authoritative snapshots, so Android timer
 * drift or a delayed callback cannot create a second competing simulation.
 */
public final class RelaySiegeBluetoothSession {
    public enum Role { HOST, CLIENT }

    public interface Sender { void send(byte[] payload); }

    public interface Listener {
        void onStatus(String status);
        void onClientState(RelaySiegeNetworkState state);
        void onHostGameChanged(RelaySiegeGame game);
        void onPlayQueued(long sequence, int applyTick);
        void onPlayRejected(long sequence, String reason);
        void onProtocolError(String error);
    }

    public static final class LocalResult {
        public final boolean accepted;
        public final String reason;
        public final int applyTick;

        LocalResult(boolean accepted, String reason, int applyTick) {
            this.accepted = accepted;
            this.reason = reason;
            this.applyTick = applyTick;
        }
    }

    private static final class PendingPlay {
        final long sequence;
        final int applyTick;
        final String cardId;
        final RelaySiegeGame.Lane lane;
        final int position;
        final int observedTick;

        PendingPlay(long sequence, int applyTick, String cardId,
                    RelaySiegeGame.Lane lane, int position, int observedTick) {
            this.sequence = sequence;
            this.applyTick = applyTick;
            this.cardId = cardId;
            this.lane = lane;
            this.position = position;
            this.observedTick = observedTick;
        }
    }

    private static final int INPUT_BUFFER_TICKS = 2;
    private static final int MAX_CLIENT_TICK_LEAD = 2;

    private final Role role;
    private final String localDeckId;
    private final Sender sender;
    private final Listener listener;

    private String sessionId;
    private long matchSeed;
    private String sunDeckId;
    private String moonDeckId;
    private RelaySiegeGame hostGame;
    private RelaySiegeNetworkState clientState;
    private boolean connected;
    private boolean started;
    private boolean paused = true;
    private boolean waitingForClientReady;
    private boolean clientNeedsReadyAfterState;

    private PendingPlay pendingHostPlay;
    private PendingPlay pendingClientPlay;
    private PendingPlay clientOutstandingRequest;
    private long nextClientSequence = 1;
    private long lastProcessedClientSequence;
    private boolean lastClientResultQueued;
    private int lastClientApplyTick;
    private String lastClientRejectReason;

    private RelaySiegeBluetoothSession(Role role, String localDeckId, String sessionId,
                                       long seed, Sender sender, Listener listener) {
        this.role = role;
        this.localDeckId = requireDeck(localDeckId);
        this.sessionId = sessionId;
        this.matchSeed = seed;
        this.sender = sender == null ? payload -> { } : sender;
        this.listener = listener == null ? new NoopListener() : listener;
        if (role == Role.HOST) {
            if (sessionId == null || sessionId.isEmpty()) throw new IllegalArgumentException("host session ID is required");
            this.sunDeckId = localDeckId;
        }
    }

    public static RelaySiegeBluetoothSession host(String hostDeckId, String sessionId, long seed,
                                                   Sender sender, Listener listener) {
        return new RelaySiegeBluetoothSession(Role.HOST, hostDeckId, sessionId, seed, sender, listener);
    }

    public static RelaySiegeBluetoothSession client(String clientDeckId, Sender sender, Listener listener) {
        return new RelaySiegeBluetoothSession(Role.CLIENT, clientDeckId, null, 0L, sender, listener);
    }

    public Role getRole() { return role; }
    public String getSessionId() { return sessionId; }
    public RelaySiegeGame getHostGame() { return hostGame; }
    public RelaySiegeNetworkState getClientState() { return clientState; }
    public boolean isStarted() { return started; }
    public boolean isPaused() { return paused; }
    public boolean isConnected() { return connected; }
    public long getLastProcessedClientSequence() { return lastProcessedClientSequence; }
    public boolean clientHasPendingPlay() { return role == Role.CLIENT && clientOutstandingRequest != null; }

    /** Called when the Android RFCOMM bridge reports a connection after its socket is usable. */
    public void onConnected() {
        connected = true;
        if (role == Role.CLIENT) {
            sender.send(RelaySiegeBluetoothProtocol.encodeJoin(localDeckId, sessionId));
            listener.onStatus(sessionId == null ? "JOINING" : "RESUMING");
        } else {
            listener.onStatus(hostGame == null ? "WAITING_FOR_JOIN" : "WAITING_FOR_RESUME");
        }
    }

    /** Host freezes logical game time on disconnect; no offline free damage is possible. */
    public void onDisconnected() {
        connected = false;
        paused = true;
        started = false;
        if (role == Role.HOST) waitingForClientReady = hostGame != null;
        listener.onStatus("DISCONNECTED");
    }

    public void receive(byte[] payload) {
        final RelaySiegeBluetoothProtocol.Frame frame;
        try {
            frame = RelaySiegeBluetoothProtocol.decode(payload);
        } catch (RuntimeException e) {
            listener.onProtocolError(safeMessage(e));
            return;
        }
        try {
            if (role == Role.HOST) receiveAsHost(frame);
            else receiveAsClient(frame);
        } catch (RuntimeException e) {
            listener.onProtocolError(safeMessage(e));
        }
    }

    /**
     * Host-only 100 ms logical clock. The Android layer schedules this; if the UI thread is delayed,
     * gameplay time slows rather than letting client/host simulations diverge.
     */
    public void hostAdvanceOneTick() {
        requireRole(Role.HOST);
        if (!connected || !started || paused || hostGame == null || hostGame.isFinished()) return;
        hostGame.advanceTicks(1);
        int tick = hostGame.getTick();
        if (pendingHostPlay != null && tick >= pendingHostPlay.applyTick) pendingHostPlay = null;
        if (pendingClientPlay != null && tick >= pendingClientPlay.applyTick) pendingClientPlay = null;
        sendClientState();
        listener.onHostGameChanged(hostGame);
        if (hostGame.isFinished()) {
            paused = true;
            listener.onStatus("MATCH_FINISHED");
        }
    }

    /** Host local input uses the same fixed two-tick scheduling delay as remote input. */
    public LocalResult requestHostPlay(String cardId, RelaySiegeGame.Lane lane, int position) {
        requireRole(Role.HOST);
        if (!started || paused || hostGame == null) return new LocalResult(false, "MATCH_NOT_RUNNING", -1);
        if (pendingHostPlay != null && hostGame.getTick() < pendingHostPlay.applyTick)
            return new LocalResult(false, "ACTION_PENDING", pendingHostPlay.applyTick);
        String invalid = validatePlay(hostGame, RelaySiegeGame.Player.SUN, cardId, lane, position);
        if (invalid != null) return new LocalResult(false, invalid, -1);
        int applyTick = hostGame.getTick() + INPUT_BUFFER_TICKS;
        hostGame.schedulePlay(applyTick, RelaySiegeGame.Player.SUN, cardId, lane, position);
        pendingHostPlay = new PendingPlay(0, applyTick, cardId, lane, position, hostGame.getTick());
        return new LocalResult(true, "QUEUED", applyTick);
    }

    /** Client keeps one outstanding deployment until the authoritative state reaches its apply tick. */
    public LocalResult requestClientPlay(String cardId, RelaySiegeGame.Lane lane, int position) {
        requireRole(Role.CLIENT);
        if (!connected || !started || paused || sessionId == null || clientState == null)
            return new LocalResult(false, "MATCH_NOT_RUNNING", -1);
        if (clientOutstandingRequest != null)
            return new LocalResult(false, "ACTION_PENDING", clientOutstandingRequest.applyTick);
        String invalid = validateSnapshotPlay(clientState, cardId, lane, position);
        if (invalid != null) return new LocalResult(false, invalid, -1);
        long sequence = nextClientSequence++;
        clientOutstandingRequest = new PendingPlay(sequence, Integer.MAX_VALUE, cardId, lane, position, clientState.tick);
        sender.send(RelaySiegeBluetoothProtocol.encodePlayRequest(
                sessionId, sequence, clientState.tick, cardId, lane, position));
        return new LocalResult(true, "SENT", -1);
    }

    private void receiveAsHost(RelaySiegeBluetoothProtocol.Frame frame) {
        switch (frame.type) {
            case JOIN:
                handleJoin(frame);
                return;
            case READY:
                requireSession(frame.sessionId);
                if (hostGame == null) throw new IllegalStateException("READY before START");
                waitingForClientReady = false;
                paused = false;
                started = true;
                sendClientState();
                listener.onStatus("RUNNING");
                return;
            case PLAY_REQUEST:
                handleClientPlayRequest(frame);
                return;
            default:
                sender.send(RelaySiegeBluetoothProtocol.encodeError("HOST_REJECTED_" + frame.type.name()));
        }
    }

    private void handleJoin(RelaySiegeBluetoothProtocol.Frame frame) {
        String clientDeck = requireDeck(frame.deckId);
        if (hostGame == null) {
            if (frame.resumeSessionId != null)
                throw new IllegalArgumentException("cannot resume a match that has not started");
            moonDeckId = clientDeck;
            hostGame = new RelaySiegeGame(
                    RelaySiegeCards.deck(sunDeckId), RelaySiegeCards.deck(moonDeckId), matchSeed);
            lastProcessedClientSequence = 0;
            pendingHostPlay = null;
            pendingClientPlay = null;
        } else {
            if (frame.resumeSessionId == null || !sessionId.equals(frame.resumeSessionId)) {
                sender.send(RelaySiegeBluetoothProtocol.encodeError("SESSION_ALREADY_ACTIVE"));
                return;
            }
            if (!moonDeckId.equals(clientDeck)) {
                sender.send(RelaySiegeBluetoothProtocol.encodeError("RESUME_DECK_MISMATCH"));
                return;
            }
        }

        waitingForClientReady = true;
        paused = true;
        started = false;
        sender.send(RelaySiegeBluetoothProtocol.encodeStart(sessionId, matchSeed, sunDeckId, moonDeckId));
        sendClientState();
        listener.onHostGameChanged(hostGame);
        listener.onStatus(frame.resumeSessionId == null ? "START_SENT" : "RESUME_STATE_SENT");
    }

    private void handleClientPlayRequest(RelaySiegeBluetoothProtocol.Frame frame) {
        requireSession(frame.sessionId);
        if (!started || paused || hostGame == null) {
            sender.send(RelaySiegeBluetoothProtocol.encodePlayRejected(
                    sessionId, frame.clientSequence, hostGame == null ? 0 : hostGame.getTick(), "MATCH_NOT_RUNNING"));
            return;
        }

        if (frame.clientSequence <= lastProcessedClientSequence) {
            replayLastClientResult(frame.clientSequence);
            sendClientState();
            return;
        }
        if (frame.clientSequence != lastProcessedClientSequence + 1) {
            sender.send(RelaySiegeBluetoothProtocol.encodePlayRejected(
                    sessionId, frame.clientSequence, hostGame.getTick(), "SEQUENCE_GAP"));
            return;
        }

        String reject = null;
        if (frame.observedTick > hostGame.getTick() + MAX_CLIENT_TICK_LEAD) reject = "CLIENT_TICK_AHEAD";
        else if (pendingClientPlay != null && hostGame.getTick() < pendingClientPlay.applyTick) reject = "ACTION_PENDING";
        else reject = validatePlay(hostGame, RelaySiegeGame.Player.MOON, frame.cardId, frame.lane, frame.position);

        lastProcessedClientSequence = frame.clientSequence;
        if (reject != null) {
            lastClientResultQueued = false;
            lastClientRejectReason = reject;
            lastClientApplyTick = -1;
            sender.send(RelaySiegeBluetoothProtocol.encodePlayRejected(
                    sessionId, frame.clientSequence, hostGame.getTick(), reject));
            sendClientState();
            return;
        }

        int applyTick = hostGame.getTick() + INPUT_BUFFER_TICKS;
        hostGame.schedulePlay(applyTick, RelaySiegeGame.Player.MOON, frame.cardId, frame.lane, frame.position);
        pendingClientPlay = new PendingPlay(frame.clientSequence, applyTick,
                frame.cardId, frame.lane, frame.position, frame.observedTick);
        lastClientResultQueued = true;
        lastClientApplyTick = applyTick;
        lastClientRejectReason = null;
        sender.send(RelaySiegeBluetoothProtocol.encodePlayQueued(sessionId, frame.clientSequence, applyTick));
    }

    private void replayLastClientResult(long sequence) {
        if (sequence != lastProcessedClientSequence) return;
        if (lastClientResultQueued) {
            sender.send(RelaySiegeBluetoothProtocol.encodePlayQueued(sessionId, sequence, lastClientApplyTick));
        } else if (lastClientRejectReason != null) {
            sender.send(RelaySiegeBluetoothProtocol.encodePlayRejected(
                    sessionId, sequence, hostGame == null ? 0 : hostGame.getTick(), lastClientRejectReason));
        }
    }

    private void receiveAsClient(RelaySiegeBluetoothProtocol.Frame frame) {
        switch (frame.type) {
            case START:
                if (!localDeckId.equals(frame.moonDeckId)) throw new IllegalArgumentException("START deck mismatch");
                sessionId = frame.sessionId;
                matchSeed = frame.matchSeed;
                sunDeckId = frame.sunDeckId;
                moonDeckId = frame.moonDeckId;
                started = false;
                paused = true;
                clientNeedsReadyAfterState = true;
                listener.onStatus("START_RECEIVED");
                return;
            case STATE:
                requireSession(frame.sessionId);
                if (frame.state.perspective != RelaySiegeGame.Player.MOON)
                    throw new IllegalArgumentException("client snapshot has wrong perspective");
                if (clientState != null && frame.state.tick < clientState.tick) return;
                clientState = frame.state;
                if (clientOutstandingRequest != null) {
                    if (frame.ackedClientSequence >= clientOutstandingRequest.sequence
                            && clientOutstandingRequest.applyTick != Integer.MAX_VALUE
                            && frame.state.tick >= clientOutstandingRequest.applyTick) {
                        clientOutstandingRequest = null;
                    }
                }
                listener.onClientState(clientState);
                if (clientNeedsReadyAfterState) {
                    clientNeedsReadyAfterState = false;
                    sender.send(RelaySiegeBluetoothProtocol.encodeReady(sessionId));
                    started = true;
                    paused = false;
                    listener.onStatus("RUNNING");
                    resendOutstandingIfNeeded(frame.ackedClientSequence);
                }
                if (clientState.isFinished()) {
                    paused = true;
                    listener.onStatus("MATCH_FINISHED");
                }
                return;
            case PLAY_QUEUED:
                requireSession(frame.sessionId);
                if (clientOutstandingRequest != null && frame.clientSequence == clientOutstandingRequest.sequence) {
                    clientOutstandingRequest = new PendingPlay(
                            clientOutstandingRequest.sequence, frame.applyTick,
                            clientOutstandingRequest.cardId, clientOutstandingRequest.lane,
                            clientOutstandingRequest.position, clientOutstandingRequest.observedTick);
                }
                listener.onPlayQueued(frame.clientSequence, frame.applyTick);
                return;
            case PLAY_REJECTED:
                requireSession(frame.sessionId);
                if (clientOutstandingRequest != null && frame.clientSequence == clientOutstandingRequest.sequence)
                    clientOutstandingRequest = null;
                listener.onPlayRejected(frame.clientSequence, frame.reason);
                return;
            case PAUSE:
                requireSession(frame.sessionId);
                paused = true;
                started = false;
                listener.onStatus("PAUSED:" + frame.reason);
                return;
            case ERROR:
                listener.onProtocolError(frame.reason);
                return;
            default:
                throw new IllegalArgumentException("CLIENT_REJECTED_" + frame.type.name());
        }
    }

    private void resendOutstandingIfNeeded(long ackedSequence) {
        if (clientOutstandingRequest == null) return;
        if (ackedSequence >= clientOutstandingRequest.sequence) return;
        sender.send(RelaySiegeBluetoothProtocol.encodePlayRequest(
                sessionId,
                clientOutstandingRequest.sequence,
                clientOutstandingRequest.observedTick,
                clientOutstandingRequest.cardId,
                clientOutstandingRequest.lane,
                clientOutstandingRequest.position));
    }

    private void sendClientState() {
        if (role != Role.HOST || !connected || hostGame == null || sessionId == null) return;
        RelaySiegeNetworkState state = RelaySiegeNetworkState.capture(hostGame, RelaySiegeGame.Player.MOON);
        sender.send(RelaySiegeBluetoothProtocol.encodeState(sessionId, lastProcessedClientSequence, state));
    }

    private static String validatePlay(RelaySiegeGame game, RelaySiegeGame.Player player,
                                       String cardId, RelaySiegeGame.Lane lane, int position) {
        if (game.isFinished()) return "MATCH_FINISHED";
        if (lane == null) return "LANE_REQUIRED";
        final RelaySiegeCards.Card card;
        try { card = RelaySiegeCards.card(cardId); }
        catch (RuntimeException e) { return "UNKNOWN_CARD"; }
        List<String> hand = game.getHand(player);
        if (!hand.contains(cardId)) return "CARD_NOT_IN_HAND";
        if (game.getFluxMilli(player) < card.fluxCost * 1_000) return "NOT_ENOUGH_FLUX";
        if (!validPosition(game, player, card, lane, position)) return "INVALID_DEPLOYMENT_POSITION";
        return null;
    }

    private static String validateSnapshotPlay(RelaySiegeNetworkState state, String cardId,
                                               RelaySiegeGame.Lane lane, int position) {
        if (state.isFinished()) return "MATCH_FINISHED";
        if (lane == null) return "LANE_REQUIRED";
        final RelaySiegeCards.Card card;
        try { card = RelaySiegeCards.card(cardId); }
        catch (RuntimeException e) { return "UNKNOWN_CARD"; }
        if (!state.yourHand.contains(cardId)) return "CARD_NOT_IN_HAND";
        if (state.yourFluxMilli < card.fluxCost * 1_000) return "NOT_ENOUGH_FLUX";
        if (!validSnapshotPosition(state, state.perspective, card, lane, position))
            return "INVALID_DEPLOYMENT_POSITION";
        return null;
    }

    private static boolean validPosition(RelaySiegeGame game, RelaySiegeGame.Player player,
                                         RelaySiegeCards.Card card, RelaySiegeGame.Lane lane, int position) {
        if (position < 0 || position > 100_000) return false;
        if (card.kind == RelaySiegeCards.Kind.SPELL) return true;
        if (player == RelaySiegeGame.Player.SUN) {
            int max = game.getRelayHp(RelaySiegeGame.Player.MOON, lane) > 0
                    ? RelaySiegeGame.BASE_SUN_DEPLOY_MAX : RelaySiegeGame.ADVANCED_SUN_DEPLOY_MAX;
            return position >= 4_000 && position <= max;
        }
        int min = game.getRelayHp(RelaySiegeGame.Player.SUN, lane) > 0
                ? RelaySiegeGame.BASE_MOON_DEPLOY_MIN : RelaySiegeGame.ADVANCED_MOON_DEPLOY_MIN;
        return position >= min && position <= 96_000;
    }

    private static boolean validSnapshotPosition(RelaySiegeNetworkState state, RelaySiegeGame.Player player,
                                                 RelaySiegeCards.Card card, RelaySiegeGame.Lane lane, int position) {
        if (position < 0 || position > 100_000) return false;
        if (card.kind == RelaySiegeCards.Kind.SPELL) return true;
        if (player == RelaySiegeGame.Player.SUN) {
            int max = state.relayHp(RelaySiegeGame.Player.MOON, lane) > 0
                    ? RelaySiegeGame.BASE_SUN_DEPLOY_MAX : RelaySiegeGame.ADVANCED_SUN_DEPLOY_MAX;
            return position >= 4_000 && position <= max;
        }
        int min = state.relayHp(RelaySiegeGame.Player.SUN, lane) > 0
                ? RelaySiegeGame.BASE_MOON_DEPLOY_MIN : RelaySiegeGame.ADVANCED_MOON_DEPLOY_MIN;
        return position >= min && position <= 96_000;
    }

    private void requireSession(String incoming) {
        if (sessionId == null || incoming == null || !sessionId.equals(incoming))
            throw new IllegalArgumentException("stale or foreign Relay Siege session");
    }

    private void requireRole(Role required) {
        if (role != required) throw new IllegalStateException("operation requires " + required);
    }

    private static String requireDeck(String deckId) {
        RelaySiegeCards.deck(deckId);
        return deckId;
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static final class NoopListener implements Listener {
        @Override public void onStatus(String status) { }
        @Override public void onClientState(RelaySiegeNetworkState state) { }
        @Override public void onHostGameChanged(RelaySiegeGame game) { }
        @Override public void onPlayQueued(long sequence, int applyTick) { }
        @Override public void onPlayRejected(long sequence, String reason) { }
        @Override public void onProtocolError(String error) { }
    }
}
