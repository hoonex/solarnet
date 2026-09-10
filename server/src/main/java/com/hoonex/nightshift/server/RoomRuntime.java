package com.hoonex.nightshift.server;

import com.hoonex.nightshift.core.*;
import com.hoonex.nightshift.net.Protocol;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

final class RoomRuntime {
    static final int MAX_PLAYERS=4;
    static final int RECONNECT_LEASE_TICKS=200;
    private static final SecureRandom RANDOM=new SecureRandom();

    final String code;
    final GameSimulation game=new GameSimulation(1);
    private final LinkedHashMap<Integer,ClientConnection> connections=new LinkedHashMap<>();
    private final Map<Integer,Long> tokens=new LinkedHashMap<>();
    private final Map<Integer,Long> leaseDeadlines=new LinkedHashMap<>();
    private int nextPlayerId=1,ownerPlayerId,snapshotDivider;

    RoomRuntime(String code){this.code=code;}

    synchronized JoinResult join(String name,ClientConnection connection){
        if(game.snapshot().phase!=GameSnapshot.Phase.LOBBY||tokens.size()>=MAX_PLAYERS)return null;
        int id=nextPlayerId++;
        if(!game.addPlayer(id,name))return null;
        long token;do{token=RANDOM.nextLong();}while(token==0L||tokens.containsValue(token));
        connections.put(id,connection);tokens.put(id,token);
        if(ownerPlayerId==0)ownerPlayerId=id;
        return new JoinResult(id,token,id==ownerPlayerId);
    }

    synchronized JoinResult resume(int playerId,long token,ClientConnection connection){
        expireLeasesLocked();
        if(!authenticate(playerId,token))return null;
        if(connections.get(playerId)!=null)return null;
        Long deadline=leaseDeadlines.get(playerId);
        if(deadline==null||game.snapshot().tick>deadline)return null;
        connections.put(playerId,connection);leaseDeadlines.remove(playerId);
        return new JoinResult(playerId,token,playerId==ownerPlayerId);
    }

    synchronized boolean authenticate(int playerId,long token){
        Long expected=tokens.get(playerId);return expected!=null&&expected.longValue()==token;
    }
    synchronized boolean setReady(int id,long token,boolean ready){return authenticate(id,token)&&connections.get(id)!=null&&game.setReady(id,ready);}
    synchronized boolean start(int id,long token){return authenticate(id,token)&&connections.get(id)!=null&&id==ownerPlayerId&&game.tryStart(id);}
    synchronized boolean input(int id,long token,GameInput input){
        if(!authenticate(id,token)||connections.get(id)==null)return false;game.submitInput(id,input);return true;
    }
    synchronized void leave(int id,long token){
        if(!authenticate(id,token))return;removePlayerLocked(id);
    }

    synchronized void disconnect(ClientConnection connection){
        Integer target=null;
        for(Map.Entry<Integer,ClientConnection> e:connections.entrySet())if(e.getValue()==connection){target=e.getKey();break;}
        if(target==null)return;
        connections.put(target,null);
        leaseDeadlines.put(target,game.snapshot().tick+RECONNECT_LEASE_TICKS);
        game.suspendPlayer(target);
    }

    void tickAndBroadcast(){
        List<ClientConnection> targets;GameSnapshot snapshot=null;List<GameEvent> events;
        synchronized(this){
            expireLeasesLocked();
            game.tick();events=game.drainEvents();snapshotDivider++;
            if(snapshotDivider>=2){snapshotDivider=0;snapshot=game.snapshot();}
            targets=new ArrayList<>();
            for(ClientConnection c:connections.values())if(c!=null)targets.add(c);
        }
        if(snapshot!=null){
            try{byte[] payload=Protocol.snapshot(code,snapshot);for(ClientConnection c:targets)c.sendQuietly(payload);}catch(IOException ignored){}
        }
        for(GameEvent e:events){
            try{byte[] payload=Protocol.event(code,e.type.name()+":"+e.playerId+":"+e.value+":"+e.tick);for(ClientConnection c:targets)c.sendQuietly(payload);}catch(IOException ignored){}
        }
    }

    synchronized boolean isEmpty(){expireLeasesLocked();return tokens.isEmpty();}
    synchronized GameSnapshot snapshot(){return game.snapshot();}

    private void expireLeasesLocked(){
        long now=game.snapshot().tick;
        ArrayList<Integer> expired=new ArrayList<>();
        for(Map.Entry<Integer,Long> e:leaseDeadlines.entrySet())if(now>e.getValue())expired.add(e.getKey());
        for(int id:expired)removePlayerLocked(id);
    }
    private void removePlayerLocked(int id){
        game.removePlayer(id);connections.remove(id);tokens.remove(id);leaseDeadlines.remove(id);
        if(ownerPlayerId==id)ownerPlayerId=connections.isEmpty()?0:connections.keySet().iterator().next();
    }

    static final class JoinResult{
        final int playerId;final long token;final boolean owner;
        JoinResult(int playerId,long token,boolean owner){this.playerId=playerId;this.token=token;this.owner=owner;}
    }
}
