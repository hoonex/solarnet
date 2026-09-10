package com.hoonex.nightshift.tests;
import com.hoonex.nightshift.core.*;
import com.hoonex.nightshift.net.Protocol;
import java.util.Arrays;

public final class ProtocolSmoke {
 public static void main(String[] args)throws Exception{
  Protocol.Message join=Protocol.decode(Protocol.joinRoom(7,"ab12cd","Player One"));
  check(join.type==Protocol.Type.JOIN_ROOM&&join.sequence==7,"join header");
  check("AB12CD".equals(join.roomCode)&&"Player One".equals(join.name),"join payload");
  GameInput input=new GameInput(42,.75,-.25,1.2,true,true,true);
  Protocol.Message decoded=Protocol.decode(Protocol.input(9,"QWERTY",3,991L,input));
  check(decoded.input.sequence==42&&decoded.playerId==3,"input");
  Protocol.Message resume=Protocol.decode(Protocol.resume(10,"QWERTY",3,991L));
  check(resume.type==Protocol.Type.RESUME&&resume.playerId==3&&resume.sessionToken==991L,"resume");

  GameSnapshot sample=new GameSnapshot(44,GameSnapshot.Phase.PLAYING,1,
   java.util.List.of(new GameSnapshot.PlayerView(1,"A",-1,-2,0,1,.8,.5,true,false,false,true,true,77)),
   new GameSnapshot.MonsterView(2,3,GameSnapshot.MonsterMode.CHASE,1),
   new boolean[]{true,false,true},new boolean[]{true,true,false},true,false,true,4,93);
  Protocol.Message snap=Protocol.decode(Protocol.snapshot("QWERTY",sample));
  check(snap.snapshot.players.get(0).lastInputSequence==77,"snapshot input ack");
  check(snap.snapshot.players.get(0).carryingFuse,"carried fuse");
  check(Arrays.equals(snap.snapshot.breakers,sample.breakers),"breakers");
  check(Arrays.equals(snap.snapshot.fusesTaken,sample.fusesTaken),"fuses");
  check(snap.snapshot.keycardRecovered&&!snap.snapshot.exitUnlocked&&snap.snapshot.blackout,"world flags");
  check(snap.snapshot.threatLevel==4&&snap.snapshot.huntSurgeTicks==93,"director state");

  byte[] corrupted=Protocol.createRoom(1,"x");corrupted[0]=0;boolean bad=false;try{Protocol.decode(corrupted);}catch(Exception e){bad=true;}check(bad,"bad magic");
  System.out.println("Nightshift protocol v3 smoke: PASS");
 }
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
