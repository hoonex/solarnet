package com.hoonex.nightshift;

final class NightshiftSession {
    private static volatile TcpGameClient active;
    private NightshiftSession() {}
    static TcpGameClient get() { return active; }
    static void set(TcpGameClient client) {
        TcpGameClient old = active;
        active = client;
        if (old != null && old != client) old.close();
    }
    static void clear(TcpGameClient client) {
        if (active == client) active = null;
    }
}
