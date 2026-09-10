package com.hoonex.nightshift.tests;

import com.hoonex.nightshift.core.*;

public final class HorrorDirectorSmoke {
    public static void main(String[] args) {
        objectiveContract();
        directorContract();
        System.out.println("Nightshift M2 objective/director smoke: PASS");
    }

    private static void objectiveContract() {
        ObjectiveProgress o=new ObjectiveProgress();
        check(!o.extractionReady(),"fresh extraction locked");
        check(o.tryPowerBreaker(FacilityMap.BREAKERS[0],false)==-1,"breaker requires fuse");

        int f0=o.tryTakeFuse(FacilityMap.FUSES[0]);
        check(f0==0,"first fuse pickup");
        check(o.tryTakeFuse(FacilityMap.FUSES[0])==-1,"fuse single-consume pickup");
        check(o.tryPowerBreaker(FacilityMap.BREAKERS[0],true)==0,"fuse powers breaker");
        check(o.poweredBreakers()==1,"powered count");

        int f1=o.tryTakeFuse(FacilityMap.FUSES[1]);
        int f2=o.tryTakeFuse(FacilityMap.FUSES[2]);
        check(f1==1&&f2==2,"remaining fuses");
        check(o.tryPowerBreaker(FacilityMap.BREAKERS[1],true)==1,"second breaker");
        check(o.tryPowerBreaker(FacilityMap.BREAKERS[2],true)==2,"third breaker");
        check(!o.extractionReady(),"keycard still required");
        check(o.tryRecoverKeycard(FacilityMap.KEYCARD),"keycard recovered");
        check(o.extractionReady(),"keycard plus all breakers opens extraction");
    }

    private static void directorContract() {
        check(!HorrorDirector.isBlackout(HorrorDirector.FIRST_BLACKOUT_TICK-1),"pre-blackout");
        check(HorrorDirector.isBlackout(HorrorDirector.FIRST_BLACKOUT_TICK),"blackout starts exactly");
        check(!HorrorDirector.isBlackout(HorrorDirector.FIRST_BLACKOUT_TICK+HorrorDirector.BLACKOUT_DURATION_TICKS),"blackout ends exactly");
        HorrorDirector.State calm=HorrorDirector.state(1,0,0);
        HorrorDirector.State surge=HorrorDirector.state(1,2,HorrorDirector.BREAKER_SURGE_TICKS);
        check(surge.threatLevel>calm.threatLevel,"objective progress raises threat");
        check(surge.monsterSpeedMultiplier>calm.monsterSpeedMultiplier,"surge accelerates hunter");
        check(surge.monsterHearingMultiplier>calm.monsterHearingMultiplier,"surge increases hearing");
    }

    private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
