package com.hoonex.nightshift.core;

import java.util.Collections;
import java.util.List;

public final class GameSnapshot {
    public enum Phase { LOBBY, PLAYING, WON, LOST }
    public enum MonsterMode { ROAM, INVESTIGATE, CHASE, SEARCH }

    public final long tick;
    public final Phase phase;
    public final int leaderPlayerId;
    public final List<PlayerView> players;
    public final MonsterView monster;
    public final boolean[] breakers;
    public final boolean[] fusesTaken;
    public final boolean keycardRecovered;
    public final boolean exitUnlocked;
    public final boolean blackout;
    public final int threatLevel;
    public final int huntSurgeTicks;

    public GameSnapshot(long tick, Phase phase, int leaderPlayerId, List<PlayerView> players,
                        MonsterView monster, boolean[] breakers, boolean exitUnlocked) {
        this(tick,phase,leaderPlayerId,players,monster,breakers,
            new boolean[FacilityMap.FUSES.length],false,exitUnlocked,false,0,0);
    }

    public GameSnapshot(long tick, Phase phase, int leaderPlayerId, List<PlayerView> players,
                        MonsterView monster, boolean[] breakers, boolean[] fusesTaken,
                        boolean keycardRecovered, boolean exitUnlocked, boolean blackout,
                        int threatLevel, int huntSurgeTicks) {
        this.tick=tick; this.phase=phase; this.leaderPlayerId=leaderPlayerId;
        this.players=Collections.unmodifiableList(players); this.monster=monster;
        this.breakers=breakers.clone(); this.fusesTaken=fusesTaken.clone();
        this.keycardRecovered=keycardRecovered; this.exitUnlocked=exitUnlocked;
        this.blackout=blackout; this.threatLevel=Math.max(0,Math.min(5,threatLevel));
        this.huntSurgeTicks=Math.max(0,huntSurgeTicks);
    }

    public static final class PlayerView {
        public final int id;
        public final String name;
        public final double x,z,yawRadians;
        public final double stamina,flashlightBattery,tension;
        public final boolean flashlightOn,downed,escaped,ready,carryingFuse;
        public final int lastInputSequence;

        public PlayerView(int id,String name,double x,double z,double yawRadians,
                          double stamina,double flashlightBattery,double tension,
                          boolean flashlightOn,boolean downed,boolean escaped,boolean ready,
                          int lastInputSequence) {
            this(id,name,x,z,yawRadians,stamina,flashlightBattery,tension,
                flashlightOn,downed,escaped,ready,false,lastInputSequence);
        }

        public PlayerView(int id,String name,double x,double z,double yawRadians,
                          double stamina,double flashlightBattery,double tension,
                          boolean flashlightOn,boolean downed,boolean escaped,boolean ready,
                          boolean carryingFuse,int lastInputSequence) {
            this.id=id; this.name=name; this.x=x; this.z=z; this.yawRadians=yawRadians;
            this.stamina=stamina; this.flashlightBattery=flashlightBattery; this.tension=tension;
            this.flashlightOn=flashlightOn; this.downed=downed; this.escaped=escaped; this.ready=ready;
            this.carryingFuse=carryingFuse; this.lastInputSequence=lastInputSequence;
        }
    }

    public static final class MonsterView {
        public final double x,z;
        public final MonsterMode mode;
        public final int targetPlayerId;
        public MonsterView(double x,double z,MonsterMode mode,int targetPlayerId){
            this.x=x;this.z=z;this.mode=mode;this.targetPlayerId=targetPlayerId;
        }
    }
}
