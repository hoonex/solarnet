package com.hoonex.nightshift.tests;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import com.hoonex.nightshift.net.Protocol;
import com.hoonex.nightshift.server.NightshiftServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public final class NightshiftServerSmoke {
    public static void main(String[] args) throws Exception {
        try (NightshiftServer server = new NightshiftServer(0);
             TestClient a = new TestClient(server.port());
             TestClient b = new TestClient(server.port())) {

            a.send(Protocol.createRoom(1, "A"));
            Protocol.Message wa = a.await(Protocol.Type.WELCOME, 2000);
            check(wa.owner, "creator is owner");

            b.send(Protocol.joinRoom(1, wa.roomCode, "B"));
            Protocol.Message wb = b.await(Protocol.Type.WELCOME, 2000);
            check(!wb.owner && wb.playerId != wa.playerId, "second player joins");

            b.send(Protocol.start(2, wa.roomCode, wb.playerId, wb.sessionToken));
            check(b.await(Protocol.Type.ERROR, 2000).text.contains("start"), "non-owner start rejected");

            a.send(Protocol.ready(2, wa.roomCode, wa.playerId, wa.sessionToken, true));
            b.send(Protocol.ready(3, wa.roomCode, wb.playerId, wb.sessionToken, true));
            Thread.sleep(80);
            a.send(Protocol.start(3, wa.roomCode, wa.playerId, wa.sessionToken));
            Thread.sleep(80);
            a.send(Protocol.input(4, wa.roomCode, wa.playerId, wa.sessionToken,
                new GameInput(1, 1, 0, 0, true, false, true)));

            GameSnapshot snapA = a.awaitPlayingSnapshot(3000);
            GameSnapshot snapB = b.awaitPlayingSnapshot(3000);
            check(snapA.players.size() == 2 && snapB.players.size() == 2, "authoritative room replicated to both clients");
            check(snapA.tick > 0 && snapB.tick > 0, "server tick advances");

            a.send(Protocol.ping(5, wa.sessionToken));
            check(a.await(Protocol.Type.PONG, 2000).sequence == 5, "ping/pong");
        }
        System.out.println("Nightshift TCP server smoke: PASS");
    }

    private static final class TestClient implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        TestClient(int port) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(500);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
        void send(byte[] payload) throws Exception { out.writeInt(payload.length); out.write(payload); out.flush(); }
        Protocol.Message read() throws Exception {
            int len = in.readInt(); byte[] payload = in.readNBytes(len); return Protocol.decode(payload);
        }
        Protocol.Message await(Protocol.Type type, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                try { Protocol.Message m = read(); if (m.type == type) return m; } catch (java.net.SocketTimeoutException ignored) {}
            }
            throw new AssertionError("timeout waiting for " + type);
        }
        GameSnapshot awaitPlayingSnapshot(long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                try {
                    Protocol.Message m = read();
                    if (m.type == Protocol.Type.SNAPSHOT && m.snapshot.phase == GameSnapshot.Phase.PLAYING) return m.snapshot;
                } catch (java.net.SocketTimeoutException ignored) {}
            }
            throw new AssertionError("timeout waiting for playing snapshot");
        }
        @Override public void close() throws Exception { socket.close(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
