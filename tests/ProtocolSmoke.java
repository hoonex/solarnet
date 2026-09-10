package com.hoonex.nightshift.tests;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSimulation;
import com.hoonex.nightshift.net.Protocol;

import java.util.Arrays;

public final class ProtocolSmoke {
    public static void main(String[] args) throws Exception {
        Protocol.Message join = Protocol.decode(Protocol.joinRoom(7, "ab12cd", "Player One"));
        check(join.type == Protocol.Type.JOIN_ROOM && join.sequence == 7, "join header");
        check("AB12CD".equals(join.roomCode) && "Player One".equals(join.name), "join payload");

        GameInput input = new GameInput(42, 0.75, -0.25, 1.2, true, true, true);
        Protocol.Message decodedInput = Protocol.decode(Protocol.input(9, "QWERTY", 3, 991L, input));
        check(decodedInput.playerId == 3 && decodedInput.sessionToken == 991L, "auth payload");
        check(decodedInput.input.sequence == 42 && decodedInput.input.sprint, "input payload");

        GameSimulation game = new GameSimulation();
        game.addPlayer(1,"A"); game.setReady(1,true); game.tryStart(1); game.tick();
        Protocol.Message snap = Protocol.decode(Protocol.snapshot("QWERTY", game.snapshot()));
        check(snap.snapshot.tick == 1 && snap.snapshot.players.size() == 1, "snapshot roundtrip");
        check(Arrays.equals(snap.snapshot.breakers, game.snapshot().breakers), "objective roundtrip");

        boolean badMagic = false;
        byte[] corrupted = Protocol.createRoom(1,"x"); corrupted[0] = 0;
        try { Protocol.decode(corrupted); } catch (Exception expected) { badMagic = true; }
        check(badMagic, "bad magic rejected");
        System.out.println("Nightshift protocol smoke: PASS");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
