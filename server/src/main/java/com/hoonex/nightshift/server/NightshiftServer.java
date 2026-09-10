package com.hoonex.nightshift.server;
import com.hoonex.nightshift.net.Protocol;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

public final class NightshiftServer implements AutoCloseable {
    private final ServerSocket serverSocket;private final RoomRegistry rooms=new RoomRegistry();
    private final ExecutorService clients=Executors.newCachedThreadPool();
    private final ScheduledExecutorService ticker=Executors.newSingleThreadScheduledExecutor();
    private final Thread acceptThread;private volatile boolean running=true;
    public NightshiftServer(int port)throws IOException{
        serverSocket=new ServerSocket(port);acceptThread=new Thread(this::acceptLoop,"nightshift-accept");acceptThread.setDaemon(true);acceptThread.start();
        ticker.scheduleAtFixedRate(this::tickRooms,50,50,TimeUnit.MILLISECONDS);
    }
    public int port(){return serverSocket.getLocalPort();}
    private void acceptLoop(){
        while(running){try{clients.submit(new ClientConnection(this,serverSocket.accept()));}catch(IOException e){if(running)try{Thread.sleep(10);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}}
    }
    void handle(ClientConnection connection,Protocol.Message m)throws IOException{
        switch(m.type){
            case CREATE_ROOM->{
                if(connection.room()!=null){connection.send(Protocol.error(m.sequence,"already in room"));return;}
                RoomRuntime room=rooms.create();RoomRuntime.JoinResult join=room.join(m.name,connection);
                if(join==null){connection.send(Protocol.error(m.sequence,"room create failed"));return;}
                connection.attach(room);connection.send(Protocol.welcome(m.sequence,room.code,join.playerId,join.token,join.owner));
            }
            case JOIN_ROOM->{
                if(connection.room()!=null){connection.send(Protocol.error(m.sequence,"already in room"));return;}
                RoomRuntime room=rooms.get(m.roomCode);if(room==null){connection.send(Protocol.error(m.sequence,"room not found"));return;}
                RoomRuntime.JoinResult join=room.join(m.name,connection);if(join==null){connection.send(Protocol.error(m.sequence,"room unavailable"));return;}
                connection.attach(room);connection.send(Protocol.welcome(m.sequence,room.code,join.playerId,join.token,join.owner));
            }
            case RESUME->{
                if(connection.room()!=null){connection.send(Protocol.error(m.sequence,"already in room"));return;}
                RoomRuntime room=rooms.get(m.roomCode);if(room==null){connection.send(Protocol.error(m.sequence,"resume room not found"));return;}
                RoomRuntime.JoinResult resumed=room.resume(m.playerId,m.sessionToken,connection);
                if(resumed==null){connection.send(Protocol.error(m.sequence,"resume rejected"));return;}
                connection.attach(room);connection.send(Protocol.welcome(m.sequence,room.code,resumed.playerId,resumed.token,resumed.owner));
            }
            case READY->withRoom(connection,m,room->{if(!room.setReady(m.playerId,m.sessionToken,m.flag))connection.sendErrorQuietly(m.sequence,"ready rejected");});
            case START->withRoom(connection,m,room->{if(!room.start(m.playerId,m.sessionToken))connection.sendErrorQuietly(m.sequence,"start rejected");});
            case INPUT->withRoom(connection,m,room->{if(!room.input(m.playerId,m.sessionToken,m.input))connection.sendErrorQuietly(m.sequence,"input rejected");});
            case LEAVE->withRoom(connection,m,room->room.leave(m.playerId,m.sessionToken));
            case PING->connection.send(Protocol.pong(m.sequence,m.sessionToken));
            case WELCOME,SNAPSHOT,EVENT,PONG,ERROR->connection.send(Protocol.error(m.sequence,"client message type rejected"));
        }
    }
    private void withRoom(ClientConnection c,Protocol.Message m,RoomAction action){
        RoomRuntime room=c.room();if(room==null||m.roomCode==null||!room.code.equals(m.roomCode)){c.sendErrorQuietly(m.sequence,"room mismatch");return;}action.run(room);
    }
    private void tickRooms(){try{for(RoomRuntime room:rooms.snapshotRooms())room.tickAndBroadcast();rooms.removeEmpty();}catch(Throwable ignored){}}
    @Override public void close(){
        if(!running)return;running=false;try{serverSocket.close();}catch(IOException ignored){}ticker.shutdownNow();clients.shutdownNow();
        try{acceptThread.join(500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
    }
    public static void main(String[] args)throws Exception{
        int port=args.length>0?Integer.parseInt(args[0]):46000;NightshiftServer server=new NightshiftServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));System.out.println("Nightshift authority listening on tcp://0.0.0.0:"+server.port());Thread.currentThread().join();
    }
    @FunctionalInterface private interface RoomAction{void run(RoomRuntime room);}
}
