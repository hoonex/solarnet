package com.hoonex.nightshift.tests;

import com.hoonex.nightshift.core.GameEvent;
import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSimulation;
import com.hoonex.nightshift.core.GameSnapshot;
import com.hoonex.nightshift.core.Vec2;
import com.hoonex.nightshift.core.FacilityMap;

import java.util.List;

public final class GameSimulationSmoke {
    public static void main(String[] args) {
        lobbyAndMovement();
        objectiveUnlock();
        monsterAuthority();
        System.out.println("Nightshift game simulation smoke: PASS");
    }

    private static void lobbyAndMovement() {
        GameSimulation g = new GameSimulation(2);
        check(g.addPlayer(1, "A"), "p1 join");
        check(g.addPlayer(2, "B"), "p2 join");
        check(!g.tryStart(1), "cannot start before ready");
        check(g.setReady(1, true) && g.setReady(2, true), "ready");
        check(!g.tryStart(2), "only leader starts");
        check(g.tryStart(1), "leader starts");
        GameSnapshot before = g.snapshot();
        g.submitInput(1, new GameInput(1, 1, 0, 0, true, false, true));
        for (int i=0;i<10;i++) g.tick();
        GameSnapshot after = g.snapshot();
        GameSnapshot.PlayerView a0 = player(before, 1), a1 = player(after, 1);
        check(a1.z > a0.z + 1.0, "authoritative movement advances");
        check(a1.stamina < a0.stamina, "sprint drains stamina");
        check(a1.flashlightOn && a1.flashlightBattery < a0.flashlightBattery, "flashlight is server state");
    }

    private static void objectiveUnlock() {
        GameSimulation g = new GameSimulation(1);
        check(g.addPlayer(1, "Runner"), "join");
        check(g.setReady(1, true) && g.tryStart(1), "start");
        g.submitInput(1, new GameInput(1, 0, 0, 0, false, true, false));
        g.tick();
        check(!g.snapshot().breakers[0], "cannot activate distant breaker");
        check(FacilityMap.collides(0, -7, 0.34), "central wall collision");
        check(!FacilityMap.hasLineOfSight(new Vec2(0,-12), new Vec2(0,0)), "wall blocks LOS");
    }

    private static void monsterAuthority() {
        GameSimulation g = new GameSimulation(1);
        g.addPlayer(1, "Noise"); g.setReady(1,true); g.tryStart(1);
        g.submitInput(1, new GameInput(1, 1, 0, 0, true, false, true));
        for (int i=0;i<250;i++) g.tick();
        GameSnapshot s = g.snapshot();
        check(s.monster.mode != null, "monster mode present");
        check(s.tick == 250, "fixed tick count");
        List<GameEvent> events = g.drainEvents();
        check(events != null, "events drain");
    }

    private static GameSnapshot.PlayerView player(GameSnapshot s, int id) {
        for (GameSnapshot.PlayerView p : s.players) if (p.id == id) return p;
        throw new AssertionError("player missing " + id);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
