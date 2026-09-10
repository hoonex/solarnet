package com.hoonex.nightshift.core;

import java.util.List;

/**
 * In-process single-player runtime. It owns a normal authoritative GameSimulation
 * and advances it at the same 20 Hz cadence as the network server, but requires
 * no socket, room code, or external process.
 */
public final class SoloGameRuntime {
    public static final int PLAYER_ID = 1;

    private final GameSimulation game;
    private int nextInputSequence = 1;

    public SoloGameRuntime() {
        game = new GameSimulation(1);
        if (!game.addPlayer(PLAYER_ID, "You")) throw new IllegalStateException("solo player bootstrap");
        if (!game.setReady(PLAYER_ID, true)) throw new IllegalStateException("solo ready bootstrap");
        if (!game.tryStart(PLAYER_ID)) throw new IllegalStateException("solo start bootstrap");
        game.drainEvents();
    }

    public synchronized Frame step(double forward, double strafe, double yawRadians,
                                   boolean sprint, boolean interact, boolean flashlightOn) {
        GameInput input = new GameInput(nextInputSequence++, forward, strafe, yawRadians,
            sprint, interact, flashlightOn);
        game.submitInput(PLAYER_ID, input);
        game.tick();
        return new Frame(input, game.snapshot(), game.drainEvents());
    }

    public synchronized GameSnapshot snapshot() {
        return game.snapshot();
    }

    public static final class Frame {
        public final GameInput input;
        public final GameSnapshot snapshot;
        public final List<GameEvent> events;

        Frame(GameInput input, GameSnapshot snapshot, List<GameEvent> events) {
            this.input = input;
            this.snapshot = snapshot;
            this.events = List.copyOf(events);
        }
    }
}
