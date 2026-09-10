package com.hoonex.nightshift.core;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Input-sequence based local prediction. Server snapshots are authoritative;
 * unacknowledged inputs are replayed after each reconciliation.
 */
public final class ClientPrediction {
    private static final int MAX_PENDING=64;
    private static final long CORRECTION_NS=140_000_000L;
    private static final double HARD_SNAP_DISTANCE=2.5;

    private final ArrayDeque<GameInput> pending=new ArrayDeque<>();
    private PlayerMotion.State predicted;
    private Vec2 correction=new Vec2(0,0);
    private long correctionStartNs;
    private boolean blocked;

    public synchronized boolean initialized(){return predicted!=null;}
    public synchronized int pendingCount(){return pending.size();}

    public synchronized void seed(GameSnapshot.PlayerView authoritative){
        predicted=PlayerMotion.from(authoritative); blocked=authoritative.downed||authoritative.escaped;
        pending.clear(); correction=new Vec2(0,0); correctionStartNs=0;
    }

    public synchronized void apply(GameInput input,long nowNs){
        if(predicted==null||blocked)return;
        collapseCorrection(nowNs);
        pending.addLast(input);
        while(pending.size()>MAX_PENDING)pending.removeFirst();
        predicted=PlayerMotion.step(predicted,input);
    }

    public synchronized void reconcile(GameSnapshot.PlayerView authoritative,long nowNs){
        if(predicted==null){seed(authoritative);return;}
        blocked=authoritative.downed||authoritative.escaped;
        Pose before=sampleLocked(nowNs);
        for(Iterator<GameInput> it=pending.iterator();it.hasNext();){
            if(it.next().sequence<=authoritative.lastInputSequence)it.remove();
        }
        PlayerMotion.State rebuilt=PlayerMotion.from(authoritative);
        for(GameInput input:pending)rebuilt=PlayerMotion.step(rebuilt,input);
        predicted=rebuilt;
        double dx=before.x-rebuilt.pos.x,dz=before.z-rebuilt.pos.z;
        if(Math.sqrt(dx*dx+dz*dz)>HARD_SNAP_DISTANCE){
            correction=new Vec2(0,0); correctionStartNs=0;
        }else{
            correction=new Vec2(dx,dz); correctionStartNs=nowNs;
        }
    }

    public synchronized Pose sample(long nowNs){return sampleLocked(nowNs);}

    private Pose sampleLocked(long nowNs){
        if(predicted==null)return null;
        double factor=correctionFactor(nowNs);
        return new Pose(predicted.pos.x+correction.x*factor,predicted.pos.z+correction.z*factor,
            predicted.yawRadians,predicted.stamina,predicted.flashlightBattery,predicted.flashlightOn);
    }

    private void collapseCorrection(long nowNs){
        double f=correctionFactor(nowNs);
        correction=new Vec2(correction.x*f,correction.z*f);
        correctionStartNs=correction.lengthSquared()<1e-8?0:nowNs;
    }

    private double correctionFactor(long nowNs){
        if(correctionStartNs==0)return 0.0;
        double t=(double)(nowNs-correctionStartNs)/CORRECTION_NS;
        if(t>=1.0)return 0.0;
        if(t<=0.0)return 1.0;
        double inv=1.0-t;
        return inv*inv*(3.0-2.0*inv);
    }

    public static final class Pose {
        public final double x,z,yawRadians,stamina,flashlightBattery;
        public final boolean flashlightOn;
        Pose(double x,double z,double yaw,double stamina,double battery,boolean light){
            this.x=x;this.z=z;this.yawRadians=yaw;this.stamina=stamina;this.flashlightBattery=battery;this.flashlightOn=light;
        }
    }
}
