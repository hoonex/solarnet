package com.hoonex.nightshift.tests;
import com.hoonex.nightshift.core.*;
import com.hoonex.nightshift.net.Protocol;
import com.hoonex.nightshift.server.NightshiftServer;
import java.io.*;
import java.net.Socket;

public final class NightshiftServerSmoke {
 public static void main(String[] args)throws Exception{
  try(NightshiftServer server=new NightshiftServer(0);TestClient a=new TestClient(server.port());TestClient b=new TestClient(server.port())){
   a.send(Protocol.createRoom(1,"A"));Protocol.Message wa=a.await(Protocol.Type.WELCOME,2000);check(wa.owner,"creator owner");
   b.send(Protocol.joinRoom(1,wa.roomCode,"B"));Protocol.Message wb=b.await(Protocol.Type.WELCOME,2000);check(!wb.owner&&wb.playerId!=wa.playerId,"second joins");
   a.send(Protocol.ready(2,wa.roomCode,wa.playerId,wa.sessionToken,true));b.send(Protocol.ready(2,wa.roomCode,wb.playerId,wb.sessionToken,true));
   Thread.sleep(80);a.send(Protocol.start(3,wa.roomCode,wa.playerId,wa.sessionToken));Thread.sleep(80);
   a.send(Protocol.input(4,wa.roomCode,wa.playerId,wa.sessionToken,new GameInput(17,1,0,0,true,false,true)));
   GameSnapshot s=a.awaitAck(wa.playerId,17,3000);check(s.players.size()==2,"two players");check(find(s,wa.playerId).lastInputSequence==17,"input ack replicated");
   b.drop();
   Thread.sleep(180);
   try(TestClient resumed=new TestClient(server.port())){
    resumed.send(Protocol.resume(5,wa.roomCode,wb.playerId,wb.sessionToken));
    Protocol.Message wr=resumed.await(Protocol.Type.WELCOME,2000);
    check(wr.playerId==wb.playerId&&wr.sessionToken==wb.sessionToken,"same slot and token resume");
    resumed.send(Protocol.input(6,wa.roomCode,wb.playerId,wb.sessionToken,new GameInput(23,0,1,.3,false,false,true)));
    GameSnapshot rs=resumed.awaitAck(wb.playerId,23,3000);check(find(rs,wb.playerId).lastInputSequence==23,"resumed input accepted");
   }
   a.send(Protocol.ping(7,wa.sessionToken));check(a.await(Protocol.Type.PONG,2000).sequence==7,"ping");
  }
  System.out.println("Nightshift TCP resume smoke: PASS");
 }
 private static GameSnapshot.PlayerView find(GameSnapshot s,int id){for(GameSnapshot.PlayerView p:s.players)if(p.id==id)return p;throw new AssertionError("missing player");}
 private static final class TestClient implements AutoCloseable{
  Socket socket;DataInputStream in;DataOutputStream out;
  TestClient(int port)throws Exception{socket=new Socket("127.0.0.1",port);socket.setSoTimeout(500);in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
  void send(byte[] p)throws Exception{out.writeInt(p.length);out.write(p);out.flush();}
  Protocol.Message read()throws Exception{int len=in.readInt();byte[] p=new byte[len];in.readFully(p);return Protocol.decode(p);}
  Protocol.Message await(Protocol.Type type,long ms)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){try{Protocol.Message m=read();if(m.type==type)return m;}catch(java.net.SocketTimeoutException ignored){}}throw new AssertionError("timeout "+type);}
  GameSnapshot awaitPlayingSnapshot(long ms)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){try{Protocol.Message m=read();if(m.type==Protocol.Type.SNAPSHOT&&m.snapshot.phase==GameSnapshot.Phase.PLAYING)return m.snapshot;}catch(java.net.SocketTimeoutException ignored){}}throw new AssertionError("snapshot timeout");}
  GameSnapshot awaitAck(int playerId,int ack,long ms)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){try{Protocol.Message m=read();if(m.type==Protocol.Type.SNAPSHOT&&m.snapshot.phase==GameSnapshot.Phase.PLAYING&&find(m.snapshot,playerId).lastInputSequence>=ack)return m.snapshot;}catch(java.net.SocketTimeoutException ignored){}}throw new AssertionError("ack timeout");}
  void drop()throws Exception{socket.close();}
  @Override public void close()throws Exception{if(!socket.isClosed())socket.close();}
 }
 private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
}
