package com.hoonex.solarnet.gridduel;

import java.util.ArrayDeque;
import java.util.Deque;

/** Reproduces link loss after host accepts a request but before PLAY_QUEUED reaches the client. */
public final class RelaySiegeBluetoothInterruptedAckSmoke {
    public static void main(String[] args) {
        Deque<byte[]> hostToClient = new ArrayDeque<>();
        Deque<byte[]> clientToHost = new ArrayDeque<>();
        RelaySiegeBluetoothSession host = RelaySiegeBluetoothSession.host(
                "counterforge", "resume-session", 77L, hostToClient::addLast, null);
        RelaySiegeBluetoothSession client = RelaySiegeBluetoothSession.client(
                "spark_cycle", clientToHost::addLast, null);

        host.onConnected();
        client.onConnected();
        pump(clientToHost, host);
        pump(hostToClient, client);
        pump(clientToHost, host);
        pump(hostToClient, client);
        require(host.isStarted() && client.isStarted(), "initial match did not start");

        RelaySiegeBluetoothSession.LocalResult sent = client.requestClientPlay(
                "runner", RelaySiegeGame.Lane.RIGHT, 69_000);
        require(sent.accepted, "client request was not sent");
        pump(clientToHost, host); // Host queues seq=1 and emits PLAY_QUEUED.
        require(host.getLastProcessedClientSequence() == 1, "host did not process request");
        require(!hostToClient.isEmpty(), "host emitted no acknowledgement");

        // Radio drops before the queued acknowledgement reaches the client.
        hostToClient.clear();
        host.onDisconnected();
        client.onDisconnected();
        require(client.clientHasPendingPlay(), "client forgot unresolved play on disconnect");

        host.onConnected();
        client.onConnected();
        pump(clientToHost, host); // resume JOIN
        pump(hostToClient, client); // START + STATE + replayed last play result
        pump(clientToHost, host); // READY
        pump(hostToClient, client);
        require(host.isStarted() && client.isStarted(), "resume handshake did not finish");

        host.hostAdvanceOneTick();
        pump(hostToClient, client);
        host.hostAdvanceOneTick();
        pump(hostToClient, client);
        require(!client.clientHasPendingPlay(),
                "client pending play stayed stuck after interrupted acknowledgement recovery");
        require(!host.getHostGame().getHand(RelaySiegeGame.Player.MOON).contains("runner"),
                "accepted pre-drop play was not applied exactly once after resume");
        System.out.println("Relay Siege interrupted ACK recovery smoke: PASS");
    }

    private static void pump(Deque<byte[]> queue, RelaySiegeBluetoothSession target) {
        int guard = 1000;
        while (!queue.isEmpty()) {
            if (--guard == 0) throw new AssertionError("pump loop");
            target.receive(queue.removeFirst());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
