package com.hoonex.nightshift.core;

/**
 * Server-owned objective state. A player can carry one fuse; fuses are consumed
 * by breakers. Extraction additionally requires the shared security keycard.
 */
public final class ObjectiveProgress {
    public static final double FUSE_PICKUP_RADIUS = 1.05;
    public static final double KEYCARD_PICKUP_RADIUS = 1.05;
    public static final double BREAKER_USE_RADIUS = 1.25;

    private final boolean[] fusesTaken = new boolean[FacilityMap.FUSES.length];
    private final boolean[] breakers = new boolean[FacilityMap.BREAKERS.length];
    private boolean keycardRecovered;

    public int tryTakeFuse(Vec2 position) {
        for (int i=0;i<fusesTaken.length;i++) {
            if (!fusesTaken[i] && position.distance(FacilityMap.FUSES[i]) <= FUSE_PICKUP_RADIUS) {
                fusesTaken[i]=true;
                return i;
            }
        }
        return -1;
    }

    public void returnFuse(int index) {
        if (index>=0 && index<fusesTaken.length) fusesTaken[index]=false;
    }

    public boolean tryRecoverKeycard(Vec2 position) {
        if (keycardRecovered || position.distance(FacilityMap.KEYCARD) > KEYCARD_PICKUP_RADIUS) return false;
        keycardRecovered=true;
        return true;
    }

    public int tryPowerBreaker(Vec2 position, boolean hasFuse) {
        if (!hasFuse) return -1;
        for (int i=0;i<breakers.length;i++) {
            if (!breakers[i] && position.distance(FacilityMap.BREAKERS[i]) <= BREAKER_USE_RADIUS) {
                breakers[i]=true;
                return i;
            }
        }
        return -1;
    }

    public int poweredBreakers() {
        int n=0;
        for (boolean b:breakers) if (b) n++;
        return n;
    }

    public boolean extractionReady() {
        return keycardRecovered && poweredBreakers()==breakers.length;
    }

    public boolean keycardRecovered() { return keycardRecovered; }
    public boolean[] fusesTaken() { return fusesTaken.clone(); }
    public boolean[] breakers() { return breakers.clone(); }
}
