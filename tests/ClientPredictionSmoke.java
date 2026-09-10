package com.hoonex.nightshift.tests;
import com.hoonex.nightshift.core.*;
public final class ClientPredictionSmoke{
 public static void main(String[] args){
  ClientPrediction p=new ClientPrediction();
  GameSnapshot.PlayerView start=new GameSnapshot.PlayerView(1,"A",-1.8,-15.3,0,1,1,0,true,false,false,true,0);
  p.seed(start); long now=1_000_000_000L;
  GameInput i1=new GameInput(1,1,0,0,false,false,true);p.apply(i1,now);
  ClientPrediction.Pose immediate=p.sample(now);
  check(immediate.z>-15.3,"local input moves immediately");
  GameSnapshot.PlayerView ack1=new GameSnapshot.PlayerView(1,"A",-1.8,-15.1625,0,1,1,0,true,false,false,true,1);
  p.reconcile(ack1,now+50_000_000L);
  check(p.pendingCount()==0,"acked input pruned");
  GameInput i2=new GameInput(2,1,0,0,false,false,true);p.apply(i2,now+60_000_000L);
  GameSnapshot.PlayerView staleAck=new GameSnapshot.PlayerView(1,"A",-1.8,-15.1625,0,1,1,0,true,false,false,true,1);
  p.reconcile(staleAck,now+70_000_000L);
  check(p.pendingCount()==1,"unacked input replayed");
  check(p.sample(now+300_000_000L).z>-15.1625,"replayed pose remains ahead of ack");
  System.out.println("Nightshift client prediction smoke: PASS");
 }
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
