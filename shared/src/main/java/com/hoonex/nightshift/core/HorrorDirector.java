package com.hoonex.nightshift.core;

/**
 * Deterministic server-side pacing policy. The authority owns blackout timing
 * and hunter pressure so every client observes the same horror beat.
 */
public final class HorrorDirector {
    public static final int FIRST_BLACKOUT_TICK = 360;   // 18 s
    public static final int BLACKOUT_PERIOD_TICKS = 520; // 26 s
    public static final int BLACKOUT_DURATION_TICKS = 70;// 3.5 s
    public static final int BREAKER_SURGE_TICKS = 120;   // 6 s

    private HorrorDirector() {}

    public static boolean isBlackout(long tick) {
        if (tick < FIRST_BLACKOUT_TICK) return false;
        long local=(tick-FIRST_BLACKOUT_TICK)%BLACKOUT_PERIOD_TICKS;
        return local < BLACKOUT_DURATION_TICKS;
    }

    public static State state(long tick, int poweredBreakers, int surgeTicks) {
        int powered=Math.max(0,Math.min(FacilityMap.BREAKERS.length,poweredBreakers));
        boolean blackout=isBlackout(tick);
        boolean surge=surgeTicks>0;
        int threat=Math.min(5,powered+(blackout?1:0)+(surge?1:0));
        double speed=1.0+powered*0.10+(surge?0.18:0)+(blackout?0.05:0);
        double vision=blackout?0.72:1.0;
        double hearing=1.0+powered*0.08+(surge?0.22:0)+(blackout?0.18:0);
        return new State(blackout,threat,speed,vision,hearing);
    }

    public static final class State {
        public final boolean blackout;
        public final int threatLevel;
        public final double monsterSpeedMultiplier;
        public final double monsterVisionMultiplier;
        public final double monsterHearingMultiplier;
        State(boolean blackout,int threat,double speed,double vision,double hearing){
            this.blackout=blackout;this.threatLevel=threat;
            this.monsterSpeedMultiplier=speed;this.monsterVisionMultiplier=vision;this.monsterHearingMultiplier=hearing;
        }
    }
}
