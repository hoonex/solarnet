package com.hoonex.nightshift.tests;
import com.hoonex.nightshift.core.*;
import java.util.List;
public final class SnapshotInterpolatorSmoke{
 public static void main(String[] args){
  SnapshotInterpolator q=new SnapshotInterpolator();
  long t=2_000_000_000L;
  q.push(snap(10,0,0,Math.toRadians(170),0,0),t);
  q.push(snap(12,10,0,Math.toRadians(-170),4,0),t+100_000_000L);
  SnapshotInterpolator.PlayerPose half=q.samplePlayer(1,t+150_000_000L);
  check(Math.abs(half.x-5)<.01,"half interpolation");
  check(Math.abs(Math.abs(half.yawRadians)-Math.PI)<.05,"shortest yaw path");
  SnapshotInterpolator.PlayerPose end=q.samplePlayer(1,t+400_000_000L);
  check(Math.abs(end.x-10)<.01,"no extrapolation");
  SnapshotInterpolator.MonsterPose monster=q.sampleMonster(t+150_000_000L);
  check(Math.abs(monster.x-2)<.01,"monster interpolation");
  System.out.println("Nightshift snapshot interpolation smoke: PASS");
 }
 private static GameSnapshot snap(long tick,double x,double z,double yaw,double mx,double mz){
  return new GameSnapshot(tick,GameSnapshot.Phase.PLAYING,1,
   List.of(new GameSnapshot.PlayerView(1,"A",x,z,yaw,1,1,0,true,false,false,true,(int)tick)),
   new GameSnapshot.MonsterView(mx,mz,GameSnapshot.MonsterMode.ROAM,0),new boolean[]{false,false,false},false);
 }
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
