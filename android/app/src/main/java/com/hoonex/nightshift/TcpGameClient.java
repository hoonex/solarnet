package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import com.hoonex.nightshift.net.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

final class TcpGameClient implements AutoCloseable {
    interface Listener {
        default void onWelcome(String roomCode, int playerId, boolean owner) {}
        default void onSnapshot(GameSnapshot snapshot) {}
        default void onEvent(String text) {}
        default void onError(String text) {}
        default void onDisconnected() {}
    }

    private final AtomicInteger sequence = new AtomicInteger(1);
    private volatile Listener listener;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readerThread;
    private volatile boolean closed;
    private volatile String roomCode;
    private volatile int playerId;
    private volatile long sessionToken;
    private volatile boolean owner;
    private volatile GameSnapshot latestSnapshot;

    TcpGameClient(Listener listener) { this.listener = listener; }
    void setListener(Listener listener) { this.listener = listener; }

    void connect(String host, int port) throws IOException {
        if (socket != null) throw new IOException("already connected");
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3500);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        readerThread = new Thread(this::readLoop, "nightshift-net-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    void createRoom(String name) throws IOException { send(Protocol.createRoom(sequence.getAndIncrement(), name)); }
    void joinRoom(String room, String name) throws IOException { send(Protocol.joinRoom(sequence.getAndIncrement(), room, name)); }
    void setReady(boolean ready) throws IOException {
        ensureJoined(); send(Protocol.ready(sequence.getAndIncrement(), roomCode, playerId, sessionToken, ready));
    }
    void startMatch() throws IOException {
        ensureJoined(); send(Protocol.start(sequence.getAndIncrement(), roomCode, playerId, sessionToken));
    }
    void sendInput(GameInput input) throws IOException {
        ensureJoined(); send(Protocol.input(sequence.getAndIncrement(), roomCode, playerId, sessionToken, input));
    }
    void ping() throws IOException { send(Protocol.ping(sequence.getAndIncrement(), sessionToken)); }

    String roomCode() { return roomCode; }
    int playerId() { return playerId; }
    boolean owner() { return owner; }
    GameSnapshot latestSnapshot() { return latestSnapshot; }

    private synchronized void send(byte[] payload) throws IOException {
        if (closed || out == null) throw new IOException("not connected");
        out.writeInt(payload.length); out.write(payload); out.flush();
    }

    private void readLoop() {
        try {
            while (!closed) {
                int length;
                try { length = in.readInt(); } catch (EOFException e) { break; }
                if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) throw new IOException("bad frame length");
                byte[] payload = new byte[length];
                in.readFully(payload);
                Protocol.Message m = Protocol.decode(payload);
                Listener current = listener;
                switch (m.type) {
                    case WELCOME -> {
                        roomCode = m.roomCode; playerId = m.playerId; sessionToken = m.sessionToken; owner = m.owner;
                        if (current != null) current.onWelcome(roomCode, playerId, owner);
                    }
                    case SNAPSHOT -> {
                        latestSnapshot = m.snapshot;
                        if (current != null) current.onSnapshot(m.snapshot);
                    }
                    case EVENT -> { if (current != null) current.onEvent(m.text); }
                    case ERROR -> { if (current != null) current.onError(m.text); }
                    case PONG -> { }
                    default -> { }
                }
            }
        } catch (IOException e) {
            Listener current = listener;
            if (!closed && current != null) current.onError("Network: " + e.getMessage());
        } finally {
            boolean notify = !closed;
            close();
            Listener current = listener;
            if (notify && current != null) current.onDisconnected();
        }
    }

    private void ensureJoined() throws IOException {
        if (roomCode == null || playerId == 0) throw new IOException("not joined");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
