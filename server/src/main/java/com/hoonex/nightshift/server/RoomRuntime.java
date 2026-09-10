package com.hoonex.nightshift.server;

import com.hoonex.nightshift.core.GameEvent;
import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSimulation;
import com.hoonex.nightshift.core.GameSnapshot;
import com.hoonex.nightshift.net.Protocol;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RoomRuntime {
    static final int MAX_PLAYERS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    final String code;
    final GameSimulation game = new GameSimulation(1);
    private final LinkedHashMap<Integer, ClientConnection> connections = new LinkedHashMap<>();
    private final Map<Integer, Long> tokens = new LinkedHashMap<>();
    private int nextPlayerId = 1;
    private int ownerPlayerId;
    private int snapshotDivider;

    RoomRuntime(String code) { this.code = code; }

    synchronized JoinResult join(String name, ClientConnection connection) {
        if (game.snapshot().phase != GameSnapshot.Phase.LOBBY || connections.size() >= MAX_PLAYERS) return null;
        int id = nextPlayerId++;
        if (!game.addPlayer(id, name)) return null;
        long token;
        do { token = RANDOM.nextLong(); } while (token == 0L || tokens.containsValue(token));
        connections.put(id, connection);
        tokens.put(id, token);
        if (ownerPlayerId == 0) ownerPlayerId = id;
        return new JoinResult(id, token, id == ownerPlayerId);
    }

    synchronized boolean authenticate(int playerId, long token) {
        Long expected = tokens.get(playerId);
        return expected != null && expected == token;
    }

    synchronized boolean setReady(int playerId, long token, boolean ready) {
        return authenticate(playerId, token) && game.setReady(playerId, ready);
    }

    synchronized boolean start(int playerId, long token) {
        return authenticate(playerId, token) && playerId == ownerPlayerId && game.tryStart(playerId);
    }

    synchronized boolean input(int playerId, long token, GameInput input) {
        if (!authenticate(playerId, token)) return false;
        game.submitInput(playerId, input);
        return true;
    }

    synchronized void leave(int playerId, long token) {
        if (!authenticate(playerId, token)) return;
        game.removePlayer(playerId);
        connections.remove(playerId);
        tokens.remove(playerId);
        if (ownerPlayerId == playerId) ownerPlayerId = connections.isEmpty() ? 0 : connections.keySet().iterator().next();
    }

    synchronized void disconnect(ClientConnection connection) {
        Integer target = null;
        for (Map.Entry<Integer, ClientConnection> e : connections.entrySet()) {
            if (e.getValue() == connection) { target = e.getKey(); break; }
        }
        if (target != null) {
            game.removePlayer(target);
            connections.remove(target);
            tokens.remove(target);
            if (ownerPlayerId == target) ownerPlayerId = connections.isEmpty() ? 0 : connections.keySet().iterator().next();
        }
    }

    void tickAndBroadcast() {
        List<ClientConnection> targets;
        GameSnapshot snapshot = null;
        List<GameEvent> events;
        synchronized (this) {
            game.tick();
            events = game.drainEvents();
            snapshotDivider++;
            if (snapshotDivider >= 2) {
                snapshotDivider = 0;
                snapshot = game.snapshot();
            }
            targets = new ArrayList<>(connections.values());
        }
        if (snapshot != null) {
            try {
                byte[] payload = Protocol.snapshot(code, snapshot);
                for (ClientConnection c : targets) c.sendQuietly(payload);
            } catch (IOException ignored) {}
        }
        if (!events.isEmpty()) {
            for (GameEvent e : events) {
                try {
                    byte[] payload = Protocol.event(code, e.type.name() + ":" + e.playerId + ":" + e.value + ":" + e.tick);
                    for (ClientConnection c : targets) c.sendQuietly(payload);
                } catch (IOException ignored) {}
            }
        }
    }

    synchronized boolean isEmpty() { return connections.isEmpty(); }
    synchronized GameSnapshot snapshot() { return game.snapshot(); }

    static final class JoinResult {
        final int playerId; final long token; final boolean owner;
        JoinResult(int playerId, long token, boolean owner) { this.playerId = playerId; this.token = token; this.owner = owner; }
    }
}
