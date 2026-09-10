package com.hoonex.solarnet.gridduel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

public final class RelaySiegeBluetoothSessionSmoke {
    public static void main(String[] args) {
        protocolRoundTripAndSnapshotPrivacy();
        authorityDeduplicatesAndBuffersInput();
        disconnectFreezesAndResumeKeepsMatch();
        staleSessionAndCorruptStateAreRejected();
        System.out.println("Relay Siege Bluetooth session smoke: PASS");
    }

    private static void protocolRoundTripAndSnapshotPrivacy() {
        RelaySiegeGame game = new RelaySiegeGame(
                RelaySiegeCards.deck("counterforge"), RelaySiegeCards.deck("spark_cycle"), 99L);
        RelaySiegeNetworkState state = RelaySiegeNetworkState.capture(game, RelaySiegeGame.Player.MOON);
        byte[] payload = RelaySiegeBluetoothProtocol.encodeState("session-a", 0, state);
        RelaySiegeBluetoothProtocol.Frame frame = RelaySiegeBluetoothProtocol.decode(payload);
        require(frame.type == RelaySiegeBluetoothProtocol.Type.STATE, "state type lost");
        require(frame.state.tick == 0, "state tick lost");
        require(frame.state.perspective == RelaySiegeGame.Player.MOON, "wrong perspective");
        require(frame.state.yourHand.equals(game.getHand(RelaySiegeGame.Player.MOON)), "client hand lost");

        // Counterforge's Bulwark is not in Spark Cycle and there are no units at tick zero. Its hidden
        // host hand must therefore not appear anywhere in the perspective-safe snapshot payload.
        String raw = new String(payload, StandardCharsets.ISO_8859_1);
        require(!raw.contains("bulwark"), "host hidden hand leaked into client snapshot");
    }

    private static void authorityDeduplicatesAndBuffersInput() {
        Harness harness = new Harness();
        harness.connectInitial();
        require(harness.host.isStarted(), "host did not start");
        require(harness.client.isStarted(), "client did not start");
        require(harness.client.getClientState().tick == 0, "initial state missing");

        for (int i = 0; i < 5; i++) {
            harness.host.hostAdvanceOneTick();
            harness.pumpHostToClient();
        }
        int beforeTick = harness.host.getHostGame().getTick();
        int beforeFlux = harness.host.getHostGame().getFluxMilli(RelaySiegeGame.Player.MOON);

        RelaySiegeBluetoothSession.LocalResult sent = harness.client.requestClientPlay(
                "runner", RelaySiegeGame.Lane.RIGHT, 69_000);
        require(sent.accepted, "client request rejected locally: " + sent.reason);
        require(harness.clientToHost.size() == 1, "play request not emitted");

        byte[] firstRequest = harness.clientToHost.peekFirst().clone();
        harness.pumpClientToHost();
        // Inject the exact same sequence again. Host must replay acknowledgement, never schedule twice.
        harness.host.receive(firstRequest);
        harness.pumpHostToClient();
        require(harness.host.getLastProcessedClientSequence() == 1, "duplicate changed client sequence");
        require(harness.host.getHostGame().getFluxMilli(RelaySiegeGame.Player.MOON) == beforeFlux,
                "buffered action spent Flux before apply tick");

        harness.host.hostAdvanceOneTick();
        harness.pumpHostToClient();
        require(harness.host.getHostGame().getTick() == beforeTick + 1, "first buffered tick wrong");
        require(harness.host.getHostGame().getFluxMilli(RelaySiegeGame.Player.MOON) >= beforeFlux,
                "Flux unexpectedly spent one tick early");

        harness.host.hostAdvanceOneTick();
        harness.pumpHostToClient();
        int playCount = 0;
        for (RelaySiegeGame.BattleEvent event : harness.host.getHostGame().getEvents()) {
            if (event.player == RelaySiegeGame.Player.MOON
                    && "PLAY".equals(event.type)
                    && event.detail.startsWith("runner@")) playCount++;
        }
        require(playCount == 1, "duplicate request applied " + playCount + " times");
        require(!harness.host.getHostGame().getHand(RelaySiegeGame.Player.MOON).contains("runner"),
                "hand did not cycle after authoritative play");
        require(!harness.client.clientHasPendingPlay(), "client pending action did not clear after apply snapshot");

        RelaySiegeBluetoothSession.LocalResult hostPlay = harness.host.requestHostPlay(
                "pulse_guard", RelaySiegeGame.Lane.LEFT, 31_000);
        require(hostPlay.accepted, "host buffered play rejected: " + hostPlay.reason);
        int hostFluxBefore = harness.host.getHostGame().getFluxMilli(RelaySiegeGame.Player.SUN);
        harness.host.hostAdvanceOneTick();
        harness.pumpHostToClient();
        require(harness.host.getHostGame().getFluxMilli(RelaySiegeGame.Player.SUN) >= hostFluxBefore,
                "host action applied before same input buffer elapsed");
        harness.host.hostAdvanceOneTick();
        harness.pumpHostToClient();
        require(!harness.host.getHostGame().getHand(RelaySiegeGame.Player.SUN).contains("pulse_guard"),
                "host hand did not cycle at buffered apply tick");
    }

    private static void disconnectFreezesAndResumeKeepsMatch() {
        Harness harness = new Harness();
        harness.connectInitial();
        for (int i = 0; i < 9; i++) {
            harness.host.hostAdvanceOneTick();
            harness.pumpHostToClient();
        }
        int frozenTick = harness.host.getHostGame().getTick();
        harness.host.onDisconnected();
        harness.client.onDisconnected();
        for (int i = 0; i < 20; i++) harness.host.hostAdvanceOneTick();
        require(harness.host.getHostGame().getTick() == frozenTick,
                "host advanced while Bluetooth peer was disconnected");

        harness.host.onConnected();
        harness.client.onConnected();
        harness.pumpClientToHost(); // JOIN with resume session
        harness.pumpHostToClient(); // START + current STATE; client emits READY
        harness.pumpClientToHost(); // READY
        harness.pumpHostToClient(); // authoritative state after READY
        require(harness.host.isStarted() && !harness.host.isPaused(), "host did not resume");
        require(harness.client.isStarted() && !harness.client.isPaused(), "client did not resume");
        require(harness.client.getClientState().tick == frozenTick, "resume did not preserve host tick");

        harness.host.hostAdvanceOneTick();
        harness.pumpHostToClient();
        require(harness.client.getClientState().tick == frozenTick + 1, "post-resume state did not advance");
    }

    private static void staleSessionAndCorruptStateAreRejected() {
        Harness harness = new Harness();
        harness.connectInitial();
        int errorsBefore = harness.hostListener.protocolErrors;
        harness.host.receive(RelaySiegeBluetoothProtocol.encodePlayRequest(
                "old-session", 1, 0, "runner", RelaySiegeGame.Lane.LEFT, 69_000));
        require(harness.hostListener.protocolErrors == errorsBefore + 1, "stale session was not fenced");
        require(harness.host.getLastProcessedClientSequence() == 0, "stale session mutated sequence state");

        RelaySiegeNetworkState state = RelaySiegeNetworkState.capture(
                harness.host.getHostGame(), RelaySiegeGame.Player.MOON);
        byte[] encoded = RelaySiegeBluetoothProtocol.encodeState(harness.host.getSessionId(), 0, state);
        encoded[encoded.length - 1] ^= 0x01;
        boolean failed = false;
        try { RelaySiegeBluetoothProtocol.decode(encoded); }
        catch (IllegalArgumentException expected) { failed = true; }
        require(failed, "corrupted authoritative state hash was accepted");

        harness.host.receive(RelaySiegeBluetoothProtocol.encodePlayRequest(
                harness.host.getSessionId(), 1, 0, "bulwark", RelaySiegeGame.Lane.RIGHT, 69_000));
        harness.pumpHostToClient();
        require(harness.host.getLastProcessedClientSequence() == 1, "valid sequence was not consumed");
        require(harness.clientListener.lastRejectedSequence == 1, "client did not receive host rejection");
        require("CARD_NOT_IN_HAND".equals(harness.clientListener.lastRejectReason),
                "wrong authoritative rejection: " + harness.clientListener.lastRejectReason);
    }

    private static final class Harness {
        final Deque<byte[]> hostToClient = new ArrayDeque<>();
        final Deque<byte[]> clientToHost = new ArrayDeque<>();
        final TestListener hostListener = new TestListener();
        final TestListener clientListener = new TestListener();
        final RelaySiegeBluetoothSession host = RelaySiegeBluetoothSession.host(
                "counterforge", "session-a", 20260910L, hostToClient::addLast, hostListener);
        final RelaySiegeBluetoothSession client = RelaySiegeBluetoothSession.client(
                "spark_cycle", clientToHost::addLast, clientListener);

        void connectInitial() {
            host.onConnected();
            client.onConnected();
            pumpClientToHost(); // JOIN -> START + STATE
            pumpHostToClient(); // START + STATE -> READY
            pumpClientToHost(); // READY -> state
            pumpHostToClient();
        }

        void pumpHostToClient() {
            int guard = 1000;
            while (!hostToClient.isEmpty()) {
                if (--guard == 0) throw new AssertionError("host->client pump loop");
                client.receive(hostToClient.removeFirst());
            }
        }

        void pumpClientToHost() {
            int guard = 1000;
            while (!clientToHost.isEmpty()) {
                if (--guard == 0) throw new AssertionError("client->host pump loop");
                host.receive(clientToHost.removeFirst());
            }
        }
    }

    private static final class TestListener implements RelaySiegeBluetoothSession.Listener {
        int protocolErrors;
        long lastRejectedSequence;
        String lastRejectReason;

        @Override public void onStatus(String status) { }
        @Override public void onClientState(RelaySiegeNetworkState state) { }
        @Override public void onHostGameChanged(RelaySiegeGame game) { }
        @Override public void onPlayQueued(long sequence, int applyTick) { }
        @Override public void onPlayRejected(long sequence, String reason) {
            lastRejectedSequence = sequence;
            lastRejectReason = reason;
        }
        @Override public void onProtocolError(String error) { protocolErrors++; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
