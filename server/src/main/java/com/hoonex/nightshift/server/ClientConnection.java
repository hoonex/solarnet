package com.hoonex.nightshift.server;
import com.hoonex.nightshift.net.Protocol;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;

final class ClientConnection implements Runnable {
    private final NightshiftServer server;private final Socket socket;private final DataInputStream in;private final DataOutputStream out;
    private volatile boolean closed;private volatile RoomRuntime room;
    ClientConnection(NightshiftServer server,Socket socket)throws IOException{
        this.server=server;this.socket=socket;socket.setTcpNoDelay(true);
        in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }
    @Override public void run(){
        try{
            while(!closed){
                int len;try{len=in.readInt();}catch(EOFException e){break;}
                if(len<=0||len>Protocol.MAX_FRAME_BYTES)throw new IOException("invalid frame length");
                byte[] payload=new byte[len];in.readFully(payload);server.handle(this,Protocol.decode(payload));
            }
        }catch(SocketException ignored){}catch(IOException e){sendErrorQuietly(0,"connection error");}finally{close();}
    }
    synchronized void send(byte[] payload)throws IOException{
        if(closed)throw new IOException("closed");out.writeInt(payload.length);out.write(payload);out.flush();
    }
    void sendQuietly(byte[] payload){try{send(payload);}catch(IOException e){close();}}
    void sendErrorQuietly(int seq,String text){try{send(Protocol.error(seq,text));}catch(IOException ignored){}}
    void attach(RoomRuntime room){this.room=room;}RoomRuntime room(){return room;}
    synchronized void close(){
        if(closed)return;closed=true;RoomRuntime r=room;if(r!=null)r.disconnect(this);try{socket.close();}catch(IOException ignored){}
    }
}
