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
    public final boolean exitUnlocked;

    public GameSnapshot(long tick, Phase phase, int leaderPlayerId, List<PlayerView> players,
                        MonsterView monster, boolean[] breakers, boolean exitUnlocked) {
        this.tick=tick; this.phase=phase; this.leaderPlayerId=leaderPlayerId;
        this.players=Collections.unmodifiableList(players); this.monster=monster;
        this.breakers=breakers.clone(); this.exitUnlocked=exitUnlocked;
    }

    public static final class PlayerView {
        public final int id;
        public final String name;
        public final double x,z,yawRadians;
        public final double stamina,flashlightBattery,tension;
        public final boolean flashlightOn,downed,escaped,ready;
        public final int lastInputSequence;

        public PlayerView(int id,String name,double x,double z,double yawRadians,
                          double stamina,double flashlightBattery,double tension,
                          boolean flashlightOn,boolean downed,boolean escaped,boolean ready,
                          int lastInputSequence) {
            this.id=id; this.name=name; this.x=x; this.z=z; this.yawRadians=yawRadians;
            this.stamina=stamina; this.flashlightBattery=flashlightBattery; this.tension=tension;
            this.flashlightOn=flashlightOn; this.downed=downed; this.escaped=escaped; this.ready=ready;
            this.lastInputSequence=lastInputSequence;
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
