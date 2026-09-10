package com.hoonex.nightshift.core;

public final class GameEvent {
    public enum Type {
        PLAYER_JOINED, PLAYER_LEFT, MATCH_STARTED, BREAKER_ACTIVATED, EXIT_UNLOCKED,
        PLAYER_DOWNED, PLAYER_REVIVED, PLAYER_ESCAPED, MATCH_WON, MATCH_LOST
    }

    public final long tick;
    public final Type type;
    public final int playerId;
    public final int value;

    public GameEvent(long tick, Type type, int playerId, int value) {
        this.tick = tick; this.type = type; this.playerId = playerId; this.value = value;
    }
}
