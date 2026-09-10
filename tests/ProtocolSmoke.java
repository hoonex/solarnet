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
  GameSimulation game=new GameSimulation();game.addPlayer(1,"A");game.setReady(1,true);game.tryStart(1);
  game.submitInput(1,new GameInput(77,1,0,0,false,false,true));game.tick();
  Protocol.Message snap=Protocol.decode(Protocol.snapshot("QWERTY",game.snapshot()));
  check(snap.snapshot.players.get(0).lastInputSequence==77,"snapshot input ack");
  check(Arrays.equals(snap.snapshot.breakers,game.snapshot().breakers),"objectives");
  byte[] corrupted=Protocol.createRoom(1,"x");corrupted[0]=0;boolean bad=false;try{Protocol.decode(corrupted);}catch(Exception e){bad=true;}check(bad,"bad magic");
  System.out.println("Nightshift protocol v2 smoke: PASS");
 }
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
