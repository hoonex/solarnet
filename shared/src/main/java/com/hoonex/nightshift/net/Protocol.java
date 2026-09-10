package com.hoonex.nightshift.net;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class Protocol {
    public static final int MAGIC=0x4E534831;
    public static final int VERSION=2;
    public static final int MAX_FRAME_BYTES=32*1024;

    public enum Type {
        CREATE_ROOM(1),JOIN_ROOM(2),WELCOME(3),READY(4),START(5),INPUT(6),SNAPSHOT(7),
        EVENT(8),PING(9),PONG(10),ERROR(11),LEAVE(12),RESUME(13);
        final int code; Type(int code){this.code=code;}
        static Type fromCode(int code)throws IOException{for(Type t:values())if(t.code==code)return t;throw new IOException("unknown message type "+code);}
    }
    public static final class Message {
        public final Type type; public int sequence; public String roomCode; public int playerId; public long sessionToken;
        public String name; public boolean flag,owner; public String text; public GameInput input; public GameSnapshot snapshot;
        private Message(Type type){this.type=type;}
    }
    private Protocol(){}

    public static byte[] createRoom(int seq,String name)throws IOException{Message m=new Message(Type.CREATE_ROOM);m.sequence=seq;m.name=name;return encode(m);}
    public static byte[] joinRoom(int seq,String room,String name)throws IOException{Message m=new Message(Type.JOIN_ROOM);m.sequence=seq;m.roomCode=room;m.name=name;return encode(m);}
    public static byte[] welcome(int seq,String room,int id,long token,boolean owner)throws IOException{Message m=new Message(Type.WELCOME);m.sequence=seq;m.roomCode=room;m.playerId=id;m.sessionToken=token;m.owner=owner;return encode(m);}
    public static byte[] ready(int seq,String room,int id,long token,boolean ready)throws IOException{Message m=auth(Type.READY,seq,room,id,token);m.flag=ready;return encode(m);}
    public static byte[] start(int seq,String room,int id,long token)throws IOException{return encode(auth(Type.START,seq,room,id,token));}
    public static byte[] input(int seq,String room,int id,long token,GameInput input)throws IOException{Message m=auth(Type.INPUT,seq,room,id,token);m.input=input;return encode(m);}
    public static byte[] snapshot(String room,GameSnapshot snapshot)throws IOException{Message m=new Message(Type.SNAPSHOT);m.roomCode=room;m.snapshot=snapshot;return encode(m);}
    public static byte[] event(String room,String text)throws IOException{Message m=new Message(Type.EVENT);m.roomCode=room;m.text=text;return encode(m);}
    public static byte[] error(int seq,String text)throws IOException{Message m=new Message(Type.ERROR);m.sequence=seq;m.text=text;return encode(m);}
    public static byte[] ping(int seq,long token)throws IOException{Message m=new Message(Type.PING);m.sequence=seq;m.sessionToken=token;return encode(m);}
    public static byte[] pong(int seq,long token)throws IOException{Message m=new Message(Type.PONG);m.sequence=seq;m.sessionToken=token;return encode(m);}
    public static byte[] leave(int seq,String room,int id,long token)throws IOException{return encode(auth(Type.LEAVE,seq,room,id,token));}
    public static byte[] resume(int seq,String room,int id,long token)throws IOException{return encode(auth(Type.RESUME,seq,room,id,token));}

    private static Message auth(Type type,int seq,String room,int id,long token){Message m=new Message(type);m.sequence=seq;m.roomCode=room;m.playerId=id;m.sessionToken=token;return m;}

    public static byte[] encode(Message m)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeByte(VERSION);out.writeByte(m.type.code);out.writeInt(m.sequence);
        switch(m.type){
            case CREATE_ROOM->writeString(out,m.name);
            case JOIN_ROOM->{writeRoom(out,m.roomCode);writeString(out,m.name);}
            case WELCOME->{writeRoom(out,m.roomCode);out.writeInt(m.playerId);out.writeLong(m.sessionToken);out.writeBoolean(m.owner);}
            case READY->{writeAuth(out,m);out.writeBoolean(m.flag);}
            case START,LEAVE,RESUME->writeAuth(out,m);
            case INPUT->{writeAuth(out,m);writeInput(out,m.input);}
            case SNAPSHOT->{writeRoom(out,m.roomCode);writeSnapshot(out,m.snapshot);}
            case EVENT->{writeRoom(out,m.roomCode);writeString(out,m.text);}
            case PING,PONG->out.writeLong(m.sessionToken);
            case ERROR->writeString(out,m.text);
        }
        out.flush();byte[] result=bytes.toByteArray();if(result.length>MAX_FRAME_BYTES)throw new IOException("frame too large");return result;
    }

    public static Message decode(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length<10||bytes.length>MAX_FRAME_BYTES)throw new IOException("invalid frame size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC)throw new IOException("bad magic");
        int version=in.readUnsignedByte();if(version!=VERSION)throw new IOException("unsupported version "+version);
        Message m=new Message(Type.fromCode(in.readUnsignedByte()));m.sequence=in.readInt();
        switch(m.type){
            case CREATE_ROOM->m.name=readString(in);
            case JOIN_ROOM->{m.roomCode=readRoom(in);m.name=readString(in);}
            case WELCOME->{m.roomCode=readRoom(in);m.playerId=in.readInt();m.sessionToken=in.readLong();m.owner=in.readBoolean();}
            case READY->{readAuth(in,m);m.flag=in.readBoolean();}
            case START,LEAVE,RESUME->readAuth(in,m);
            case INPUT->{readAuth(in,m);m.input=readInput(in);}
            case SNAPSHOT->{m.roomCode=readRoom(in);m.snapshot=readSnapshot(in);}
            case EVENT->{m.roomCode=readRoom(in);m.text=readString(in);}
            case PING,PONG->m.sessionToken=in.readLong();
            case ERROR->m.text=readString(in);
        }
        if(in.available()!=0)throw new IOException("trailing bytes");return m;
    }

    private static void writeAuth(DataOutputStream out,Message m)throws IOException{writeRoom(out,m.roomCode);out.writeInt(m.playerId);out.writeLong(m.sessionToken);}
    private static void readAuth(DataInputStream in,Message m)throws IOException{m.roomCode=readRoom(in);m.playerId=in.readInt();m.sessionToken=in.readLong();}
    private static void writeInput(DataOutputStream out,GameInput i)throws IOException{
        if(i==null)i=GameInput.IDLE;out.writeInt(i.sequence);out.writeFloat((float)i.forward);out.writeFloat((float)i.strafe);out.writeFloat((float)i.lookYawRadians);
        out.writeBoolean(i.sprint);out.writeBoolean(i.interact);out.writeBoolean(i.flashlightOn);
    }
    private static GameInput readInput(DataInputStream in)throws IOException{return new GameInput(in.readInt(),in.readFloat(),in.readFloat(),in.readFloat(),in.readBoolean(),in.readBoolean(),in.readBoolean());}

    private static void writeSnapshot(DataOutputStream out,GameSnapshot s)throws IOException{
        if(s==null)throw new IOException("missing snapshot");
        out.writeLong(s.tick);out.writeByte(s.phase.ordinal());out.writeInt(s.leaderPlayerId);out.writeByte(s.players.size());
        for(GameSnapshot.PlayerView p:s.players){
            out.writeInt(p.id);writeString(out,p.name);out.writeFloat((float)p.x);out.writeFloat((float)p.z);out.writeFloat((float)p.yawRadians);
            out.writeFloat((float)p.stamina);out.writeFloat((float)p.flashlightBattery);out.writeFloat((float)p.tension);out.writeInt(p.lastInputSequence);
            int flags=(p.flashlightOn?1:0)|(p.downed?2:0)|(p.escaped?4:0)|(p.ready?8:0);out.writeByte(flags);
        }
        out.writeFloat((float)s.monster.x);out.writeFloat((float)s.monster.z);out.writeByte(s.monster.mode.ordinal());out.writeInt(s.monster.targetPlayerId);
        out.writeByte(s.breakers.length);for(boolean breaker:s.breakers)out.writeBoolean(breaker);out.writeBoolean(s.exitUnlocked);
    }

    private static GameSnapshot readSnapshot(DataInputStream in)throws IOException{
        long tick=in.readLong();int phaseIndex=in.readUnsignedByte();if(phaseIndex>=GameSnapshot.Phase.values().length)throw new IOException("bad phase");
        int leader=in.readInt(),count=in.readUnsignedByte();if(count>4)throw new IOException("too many players");
        ArrayList<GameSnapshot.PlayerView> players=new ArrayList<>();
        for(int i=0;i<count;i++){
            int id=in.readInt();String name=readString(in);double x=in.readFloat(),z=in.readFloat(),yaw=in.readFloat();
            double stamina=in.readFloat(),battery=in.readFloat(),tension=in.readFloat();int ack=in.readInt(),flags=in.readUnsignedByte();
            players.add(new GameSnapshot.PlayerView(id,name,x,z,yaw,stamina,battery,tension,(flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,ack));
        }
        double mx=in.readFloat(),mz=in.readFloat();int modeIndex=in.readUnsignedByte();if(modeIndex>=GameSnapshot.MonsterMode.values().length)throw new IOException("bad monster mode");
        int target=in.readInt(),breakerCount=in.readUnsignedByte();if(breakerCount>16)throw new IOException("bad breaker count");
        boolean[] breakers=new boolean[breakerCount];for(int i=0;i<breakerCount;i++)breakers[i]=in.readBoolean();
        return new GameSnapshot(tick,GameSnapshot.Phase.values()[phaseIndex],leader,players,
            new GameSnapshot.MonsterView(mx,mz,GameSnapshot.MonsterMode.values()[modeIndex],target),breakers,in.readBoolean());
    }

    private static void writeRoom(DataOutputStream out,String room)throws IOException{
        String normalized=room==null?"":room.trim().toUpperCase();if(normalized.length()>8)throw new IOException("room code too long");writeString(out,normalized);
    }
    private static String readRoom(DataInputStream in)throws IOException{return readString(in).toUpperCase();}
    private static void writeString(DataOutputStream out,String value)throws IOException{
        byte[] data=(value==null?"":value).getBytes(StandardCharsets.UTF_8);if(data.length>96)throw new IOException("string too long");out.writeByte(data.length);out.write(data);
    }
    private static String readString(DataInputStream in)throws IOException{
        final int len;try{len=in.readUnsignedByte();}catch(EOFException e){throw new IOException("truncated string",e);}
        byte[] data=new byte[len];try{in.readFully(data);}catch(EOFException e){throw new IOException("truncated string",e);}return new String(data,StandardCharsets.UTF_8);
    }
}
