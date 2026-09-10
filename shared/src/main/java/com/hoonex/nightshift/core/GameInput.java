package com.hoonex.nightshift.core;

public final class GameInput {
    public static final GameInput IDLE = new GameInput(0, 0, 0, 0, false, false, false);

    public final int sequence;
    public final double forward;
    public final double strafe;
    public final double lookYawRadians;
    public final boolean sprint;
    public final boolean interact;
    public final boolean flashlightOn;

    public GameInput(int sequence, double forward, double strafe, double lookYawRadians,
                     boolean sprint, boolean interact, boolean flashlightOn) {
        this.sequence = sequence;
        this.forward = clamp(forward, -1.0, 1.0);
        this.strafe = clamp(strafe, -1.0, 1.0);
        this.lookYawRadians = normalizeAngle(lookYawRadians);
        this.sprint = sprint;
        this.interact = interact;
        this.flashlightOn = flashlightOn;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double normalizeAngle(double a) {
        while (a > Math.PI) a -= Math.PI * 2.0;
        while (a < -Math.PI) a += Math.PI * 2.0;
        return a;
    }
}
