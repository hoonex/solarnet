package com.hoonex.nightshift.core;

/** Deliberately buffers one 10 Hz snapshot interval; never extrapolates past authority. */
public final class SnapshotInterpolator {
    public static final long SNAPSHOT_INTERVAL_NS=100_000_000L;
    private GameSnapshot previous,current;
    private long currentArrivalNs;

    public synchronized void push(GameSnapshot snapshot,long arrivalNs){
        if(snapshot==null)return;
        if(current!=null && snapshot.tick<=current.tick)return;
        previous=current;
        current=snapshot;
        currentArrivalNs=arrivalNs;
        if(previous==null)previous=current;
    }

    public synchronized PlayerPose samplePlayer(int playerId,long nowNs){
        if(current==null)return null;
        GameSnapshot.PlayerView b=find(current,playerId),a=find(previous,playerId);
        if(b==null)return null;
        if(a==null)a=b;
        double t=alpha(nowNs);
        return new PlayerPose(
            lerp(a.x,b.x,t),lerp(a.z,b.z,t),lerpAngle(a.yawRadians,b.yawRadians,t),
            b.downed,b.escaped,b.flashlightOn,b.lastInputSequence);
    }

    public synchronized MonsterPose sampleMonster(long nowNs){
        if(current==null)return null;
        GameSnapshot.MonsterView a=previous.monster,b=current.monster;
        double t=alpha(nowNs);
        return new MonsterPose(lerp(a.x,b.x,t),lerp(a.z,b.z,t),b.mode,b.targetPlayerId);
    }

    private double alpha(long nowNs){
        if(previous==current)return 1.0;
        double t=(double)(nowNs-currentArrivalNs)/SNAPSHOT_INTERVAL_NS;
        return Math.max(0.0,Math.min(1.0,t));
    }
    private static GameSnapshot.PlayerView find(GameSnapshot s,int id){
        if(s==null)return null;
        for(GameSnapshot.PlayerView p:s.players)if(p.id==id)return p;
        return null;
    }
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double lerpAngle(double a,double b,double t){
        double d=b-a;
        while(d>Math.PI)d-=Math.PI*2;
        while(d<-Math.PI)d+=Math.PI*2;
        return a+d*t;
    }

    public static final class PlayerPose {
        public final double x,z,yawRadians;
        public final boolean downed,escaped,flashlightOn;
        public final int lastInputSequence;
        PlayerPose(double x,double z,double yaw,boolean downed,boolean escaped,boolean light,int seq){
            this.x=x;this.z=z;this.yawRadians=yaw;this.downed=downed;this.escaped=escaped;this.flashlightOn=light;this.lastInputSequence=seq;
        }
    }
    public static final class MonsterPose {
        public final double x,z; public final GameSnapshot.MonsterMode mode; public final int targetPlayerId;
        MonsterPose(double x,double z,GameSnapshot.MonsterMode mode,int target){this.x=x;this.z=z;this.mode=mode;this.targetPlayerId=target;}
    }
}
