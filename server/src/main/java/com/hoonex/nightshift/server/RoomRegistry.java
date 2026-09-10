package com.hoonex.nightshift.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

final class RoomRegistry {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, RoomRuntime> rooms = new ConcurrentHashMap<>();

    RoomRuntime create() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = nextCode();
            RoomRuntime room = new RoomRuntime(code);
            if (rooms.putIfAbsent(code, room) == null) return room;
        }
        throw new IllegalStateException("unable to allocate room code");
    }

    RoomRuntime get(String code) {
        if (code == null) return null;
        return rooms.get(code.trim().toUpperCase());
    }

    Collection<RoomRuntime> snapshotRooms() { return new ArrayList<>(rooms.values()); }

    void removeEmpty() {
        for (RoomRuntime room : rooms.values()) if (room.isEmpty()) rooms.remove(room.code, room);
    }

    private String nextCode() {
        char[] chars = new char[6];
        for (int i = 0; i < chars.length; i++) chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(chars);
    }
}
