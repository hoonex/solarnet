package com.hoonex.nightshift.core;

/** Canonical player locomotion used by both authority and client prediction. */
public final class PlayerMotion {
    public static final double PLAYER_RADIUS = 0.34;
    public static final double WALK_SPEED = 2.75;
    public static final double SPRINT_SPEED = 4.55;
    public static final double DT = 1.0 / 20.0;

    private PlayerMotion() {}

    public static State from(GameSnapshot.PlayerView p) {
        return new State(new Vec2(p.x,p.z),p.yawRadians,p.stamina,p.flashlightBattery,p.flashlightOn);
    }

    public static State step(State state, GameInput input) {
        double yaw=input.lookYawRadians;
        boolean flashlight=input.flashlightOn && state.flashlightBattery>0.0;
        double forward=input.forward, strafe=input.strafe;
        double mag=Math.sqrt(forward*forward+strafe*strafe);
        if(mag>1.0){forward/=mag;strafe/=mag;mag=1.0;}
        boolean actualSprint=input.sprint && mag>0.1 && state.stamina>0.03;
        double speed=actualSprint?SPRINT_SPEED:WALK_SPEED;
        double sin=Math.sin(yaw),cos=Math.cos(yaw);
        Vec2 dir=new Vec2(sin*forward+cos*strafe,cos*forward-sin*strafe);
        Vec2 pos=FacilityMap.moveWithSlide(state.pos,dir.scale(speed*DT),PLAYER_RADIUS);
        double stamina=actualSprint?clamp01(state.stamina-0.34*DT):clamp01(state.stamina+0.21*DT);
        double battery=state.flashlightBattery;
        if(flashlight){
            battery=clamp01(battery-0.0042*DT);
            if(battery<=0.0) flashlight=false;
        }
        return new State(pos,yaw,stamina,battery,flashlight);
    }

    private static double clamp01(double v){return Math.max(0.0,Math.min(1.0,v));}

    public static final class State {
        public final Vec2 pos;
        public final double yawRadians,stamina,flashlightBattery;
        public final boolean flashlightOn;
        public State(Vec2 pos,double yawRadians,double stamina,double flashlightBattery,boolean flashlightOn){
            this.pos=pos;this.yawRadians=yawRadians;this.stamina=stamina;
            this.flashlightBattery=flashlightBattery;this.flashlightOn=flashlightOn;
        }
    }
}
