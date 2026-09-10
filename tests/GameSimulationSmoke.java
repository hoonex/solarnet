package com.hoonex.nightshift.tests;
import com.hoonex.nightshift.core.*;
import java.util.List;
public final class GameSimulationSmoke {
 public static void main(String[] args){lobbyAndMovement();objectiveUnlock();monsterAuthority();System.out.println("Nightshift game simulation smoke: PASS");}
 private static void lobbyAndMovement(){
  GameSimulation g=new GameSimulation(2);check(g.addPlayer(1,"A"),"p1");check(g.addPlayer(2,"B"),"p2");check(!g.tryStart(1),"pre-ready");
  check(g.setReady(1,true)&&g.setReady(2,true),"ready");check(!g.tryStart(2),"leader");check(g.tryStart(1),"start");
  GameSnapshot before=g.snapshot();g.submitInput(1,new GameInput(1,1,0,0,true,false,true));for(int i=0;i<10;i++)g.tick();GameSnapshot after=g.snapshot();
  GameSnapshot.PlayerView a0=player(before,1),a1=player(after,1);check(a1.z>a0.z+1,"move");check(a1.stamina<a0.stamina,"stamina");check(a1.flashlightOn&&a1.flashlightBattery<a0.flashlightBattery,"light");check(a1.lastInputSequence==1,"ack");
 }
 private static void objectiveUnlock(){
  GameSimulation g=new GameSimulation();check(g.addPlayer(1,"Runner"),"join");check(g.setReady(1,true)&&g.tryStart(1),"start");
  g.submitInput(1,new GameInput(1,0,0,0,false,true,false));g.tick();check(!g.snapshot().breakers[0],"distant");check(FacilityMap.collides(0,-7,.34),"collision");check(!FacilityMap.hasLineOfSight(new Vec2(0,-12),new Vec2(0,0)),"los");
 }
 private static void monsterAuthority(){
  GameSimulation g=new GameSimulation();g.addPlayer(1,"Noise");g.setReady(1,true);g.tryStart(1);g.submitInput(1,new GameInput(1,1,0,0,true,false,true));for(int i=0;i<250;i++)g.tick();
  GameSnapshot s=g.snapshot();check(s.monster.mode!=null,"monster");check(s.tick==250,"ticks");List<GameEvent> events=g.drainEvents();check(events!=null,"events");
 }
 private static GameSnapshot.PlayerView player(GameSnapshot s,int id){for(GameSnapshot.PlayerView p:s.players)if(p.id==id)return p;throw new AssertionError("missing");}
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
