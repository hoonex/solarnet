package com.hoonex.nightshift.tests;

import com.hoonex.nightshift.core.*;

public final class SoloGameRuntimeSmoke {
    public static void main(String[] args) {
        SoloGameRuntime solo = new SoloGameRuntime();
        GameSnapshot start = solo.snapshot();
        check(start.phase == GameSnapshot.Phase.PLAYING, "solo boots directly into gameplay");
        check(start.players.size() == 1, "one local player");
        check(start.players.get(0).id == SoloGameRuntime.PLAYER_ID, "stable local id");

        double z0 = start.players.get(0).z;
        SoloGameRuntime.Frame frame = null;
        for (int i = 0; i < 8; i++) frame = solo.step(1, 0, 0, false, false, true);
        check(frame != null, "frame produced");
        check(frame.snapshot.players.get(0).z > z0 + 0.5, "local controls advance authoritative player");
        check(frame.input.sequence == 8, "local input sequence advances");
        check(frame.snapshot.players.get(0).lastInputSequence == 8, "authority acknowledges local input");
        check(frame.snapshot.phase == GameSnapshot.Phase.PLAYING, "runtime remains active");

        System.out.println("Nightshift standalone solo runtime smoke: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
