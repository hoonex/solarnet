package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import com.hoonex.nightshift.net.Protocol;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TcpGameClient implements AutoCloseable {
    interface Listener {
        default void onWelcome(String roomCode,int playerId,boolean owner){}
        default void onSnapshot(GameSnapshot snapshot){}
        default void onEvent(String text){}
        default void onError(String text){}
        default void onReconnecting(int attempt,int maxAttempts){}
        default void onReconnected(){}
        default void onDisconnected(){}
    }

    private static final int[] RECONNECT_BACKOFF_MS={200,500,1000,2000};
    private final AtomicInteger sequence=new AtomicInteger(1);
    private final AtomicBoolean reconnecting=new AtomicBoolean();
    private final Object ioLock=new Object();
    private volatile Listener listener;
    private volatile boolean permanentClosed,connected;
    private volatile String host,roomCode;
    private volatile int port,playerId,generation;
    private volatile long sessionToken;
    private volatile boolean owner;
    private volatile GameSnapshot latestSnapshot;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    TcpGameClient(Listener listener){this.listener=listener;}
    void setListener(Listener listener){this.listener=listener;}

    void connect(String host,int port)throws IOException{
        synchronized(ioLock){
            if(socket!=null||permanentClosed)throw new IOException("already connected");
            this.host=host;this.port=port;
            Connection c=open(host,port,3500);
            install(c);
        }
    }

    void createRoom(String name)throws IOException{send(Protocol.createRoom(sequence.getAndIncrement(),name));}
    void joinRoom(String room,String name)throws IOException{send(Protocol.joinRoom(sequence.getAndIncrement(),room,name));}
    void setReady(boolean ready)throws IOException{ensureJoined();send(Protocol.ready(sequence.getAndIncrement(),roomCode,playerId,sessionToken,ready));}
    void startMatch()throws IOException{ensureJoined();send(Protocol.start(sequence.getAndIncrement(),roomCode,playerId,sessionToken));}
    void sendInput(GameInput input)throws IOException{ensureJoined();send(Protocol.input(sequence.getAndIncrement(),roomCode,playerId,sessionToken,input));}
    void ping()throws IOException{send(Protocol.ping(sequence.getAndIncrement(),sessionToken));}

    String roomCode(){return roomCode;}
    int playerId(){return playerId;}
    boolean owner(){return owner;}
    boolean isConnected(){return connected&&!permanentClosed;}
    GameSnapshot latestSnapshot(){return latestSnapshot;}

    private void send(byte[] payload)throws IOException{
        synchronized(ioLock){
            if(permanentClosed||!connected||out==null)throw new IOException("not connected");
            writeFrame(out,payload);
        }
    }

    private Connection open(String host,int port,int timeoutMs)throws IOException{
        Socket s=new Socket();
        try{
            s.connect(new InetSocketAddress(host,port),timeoutMs);
            s.setTcpNoDelay(true);
            return new Connection(s,new DataInputStream(new BufferedInputStream(s.getInputStream())),
                new DataOutputStream(new BufferedOutputStream(s.getOutputStream())));
        }catch(IOException e){try{s.close();}catch(IOException ignored){}throw e;}
    }

    private void install(Connection c){
        socket=c.socket;in=c.in;out=c.out;connected=true;
        int installedGeneration=++generation;
        Thread reader=new Thread(()->readLoop(c,installedGeneration),"nightshift-net-reader-"+installedGeneration);
        reader.setDaemon(true);reader.start();
    }

    private void readLoop(Connection c,int readerGeneration){
        IOException failure=null;
        try{
            while(!permanentClosed){
                Protocol.Message m=Protocol.decode(readFrame(c.in));
                dispatch(m);
            }
        }catch(IOException e){failure=e;}
        finally{handleConnectionLoss(c,readerGeneration,failure);}
    }

    private void dispatch(Protocol.Message m){
        Listener current=listener;
        switch(m.type){
            case WELCOME->{
                roomCode=m.roomCode;playerId=m.playerId;sessionToken=m.sessionToken;owner=m.owner;
                if(current!=null)current.onWelcome(roomCode,playerId,owner);
            }
            case SNAPSHOT->{latestSnapshot=m.snapshot;if(current!=null)current.onSnapshot(m.snapshot);}
            case EVENT->{if(current!=null)current.onEvent(m.text);}
            case ERROR->{if(current!=null)current.onError(m.text);}
            case PONG->{}
            default->{}
        }
    }

    private void handleConnectionLoss(Connection c,int readerGeneration,IOException failure){
        boolean shouldReconnect=false;
        synchronized(ioLock){
            if(readerGeneration!=generation||socket!=c.socket)return;
            closeQuietly(c.socket);socket=null;in=null;out=null;connected=false;
            if(!permanentClosed&&roomCode!=null&&playerId!=0&&sessionToken!=0L)shouldReconnect=true;
        }
        if(shouldReconnect)startReconnectLoop();
        else if(!permanentClosed){
            Listener current=listener;
            if(failure!=null&&current!=null)current.onError("Network: "+safeMessage(failure));
            if(current!=null)current.onDisconnected();
        }
    }

    private void startReconnectLoop(){
        if(!reconnecting.compareAndSet(false,true))return;
        Thread t=new Thread(this::reconnectLoop,"nightshift-reconnect");
        t.setDaemon(true);t.start();
    }

    private void reconnectLoop(){
        IOException last=null;
        try{
            for(int i=0;i<RECONNECT_BACKOFF_MS.length&&!permanentClosed;i++){
                Listener current=listener;if(current!=null)current.onReconnecting(i+1,RECONNECT_BACKOFF_MS.length);
                try{Thread.sleep(RECONNECT_BACKOFF_MS[i]);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                Connection candidate=null;
                try{
                    candidate=open(host,port,2500);
                    candidate.socket.setSoTimeout(2500);
                    byte[] resume=Protocol.resume(sequence.getAndIncrement(),roomCode,playerId,sessionToken);
                    writeFrame(candidate.out,resume);
                    Protocol.Message reply=null;
                    long handshakeEnd=System.nanoTime()+2_500_000_000L;
                    while(System.nanoTime()<handshakeEnd){
                        Protocol.Message incoming=Protocol.decode(readFrame(candidate.in));
                        if(incoming.type==Protocol.Type.SNAPSHOT){latestSnapshot=incoming.snapshot;continue;}
                        if(incoming.type==Protocol.Type.EVENT)continue;
                        if(incoming.type==Protocol.Type.ERROR)throw new IOException(incoming.text);
                        if(incoming.type==Protocol.Type.WELCOME){reply=incoming;break;}
                    }
                    if(reply==null||reply.playerId!=playerId||reply.sessionToken!=sessionToken)throw new IOException("resume handshake rejected");
                    candidate.socket.setSoTimeout(0);
                    synchronized(ioLock){
                        if(permanentClosed){closeQuietly(candidate.socket);return;}
                        owner=reply.owner;install(candidate);candidate=null;
                    }
                    current=listener;
                    if(current!=null){current.onReconnected();GameSnapshot snap=latestSnapshot;if(snap!=null)current.onSnapshot(snap);}
                    return;
                }catch(IOException e){
                    last=e;if(candidate!=null)closeQuietly(candidate.socket);
                }
            }
        }finally{
            reconnecting.set(false);
            if(!permanentClosed&&!connected){
                Listener current=listener;
                if(last!=null&&current!=null)current.onError("Reconnect: "+safeMessage(last));
                if(current!=null)current.onDisconnected();
            }
        }
    }

    private static byte[] readFrame(DataInputStream in)throws IOException{
        final int len;
        try{len=in.readInt();}catch(EOFException e){throw new EOFException("connection closed");}
        if(len<=0||len>Protocol.MAX_FRAME_BYTES)throw new IOException("bad frame length");
        byte[] payload=new byte[len];in.readFully(payload);return payload;
    }
    private static void writeFrame(DataOutputStream out,byte[] payload)throws IOException{
        out.writeInt(payload.length);out.write(payload);out.flush();
    }

    private void ensureJoined()throws IOException{
        if(roomCode==null||playerId==0||sessionToken==0L)throw new IOException("not joined");
        if(!isConnected())throw new IOException("reconnecting");
    }

    @Override public void close(){
        synchronized(ioLock){
            if(permanentClosed)return;
            if(connected&&roomCode!=null&&playerId!=0&&sessionToken!=0L&&out!=null){
                try{writeFrame(out,Protocol.leave(sequence.getAndIncrement(),roomCode,playerId,sessionToken));}catch(IOException ignored){}
            }
            permanentClosed=true;connected=false;generation++;
            closeQuietly(socket);socket=null;in=null;out=null;
        }
    }

    private static String safeMessage(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(IOException ignored){}}
    private static final class Connection{
        final Socket socket;final DataInputStream in;final DataOutputStream out;
        Connection(Socket socket,DataInputStream in,DataOutputStream out){this.socket=socket;this.in=in;this.out=out;}
    }
}
