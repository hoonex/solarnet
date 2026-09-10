package com.hoonex.nightshift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class GameSimulation {
    public static final int TICK_RATE=20;
    public static final double DT=1.0/TICK_RATE;
    public static final int MAX_PLAYERS=4;

    private static final double MONSTER_RADIUS=0.42;
    private static final double MONSTER_ROAM_SPEED=2.20;
    private static final double MONSTER_CHASE_SPEED=3.85;
    private static final int REVIVE_TICKS=40;

    private final LinkedHashMap<Integer,Player> players=new LinkedHashMap<>();
    private final ArrayList<GameEvent> events=new ArrayList<>();
    private final ObjectiveProgress objectives=new ObjectiveProgress();
    private final int minimumPlayers;

    private long tick;
    private long playTicks;
    private GameSnapshot.Phase phase=GameSnapshot.Phase.LOBBY;
    private int leaderPlayerId;
    private boolean exitUnlocked;
    private boolean blackout;
    private int huntSurgeTicks;

    private Vec2 monsterPos=FacilityMap.MONSTER_SPAWN;
    private GameSnapshot.MonsterMode monsterMode=GameSnapshot.MonsterMode.ROAM;
    private int monsterTargetPlayerId;
    private Vec2 monsterLastKnown=FacilityMap.MONSTER_SPAWN;
    private int monsterStateTicks,monsterAttackCooldown,patrolIndex;

    public GameSimulation(){this(1);}
    public GameSimulation(int minimumPlayers){
        if(minimumPlayers<1||minimumPlayers>MAX_PLAYERS)throw new IllegalArgumentException("minimumPlayers");
        this.minimumPlayers=minimumPlayers;
    }

    public synchronized boolean addPlayer(int id,String name){
        if(phase!=GameSnapshot.Phase.LOBBY||players.size()>=MAX_PLAYERS||players.containsKey(id))return false;
        int slot=players.size();
        Player p=new Player(id,sanitizeName(name),FacilityMap.SPAWNS[slot]);
        players.put(id,p);
        if(leaderPlayerId==0)leaderPlayerId=id;
        events.add(new GameEvent(tick,GameEvent.Type.PLAYER_JOINED,id,0));
        return true;
    }

    public synchronized void removePlayer(int id){
        Player removed=players.remove(id);
        if(removed==null)return;
        if(removed.carriedFuseIndex>=0)objectives.returnFuse(removed.carriedFuseIndex);
        events.add(new GameEvent(tick,GameEvent.Type.PLAYER_LEFT,id,0));
        if(leaderPlayerId==id)leaderPlayerId=players.isEmpty()?0:players.keySet().iterator().next();
        if(phase==GameSnapshot.Phase.PLAYING)evaluateTerminalState();
    }

    public synchronized void suspendPlayer(int id){
        Player p=players.get(id);
        if(p==null)return;
        p.input=new GameInput(p.lastInputSequence,0,0,p.yawRadians,false,false,false);
        p.flashlightOn=false;
    }

    public synchronized boolean setReady(int id,boolean ready){
        Player p=players.get(id);
        if(p==null||phase!=GameSnapshot.Phase.LOBBY)return false;
        p.ready=ready;return true;
    }

    public synchronized boolean tryStart(int requesterId){
        if(phase!=GameSnapshot.Phase.LOBBY||requesterId!=leaderPlayerId||players.size()<minimumPlayers)return false;
        for(Player p:players.values())if(!p.ready)return false;
        phase=GameSnapshot.Phase.PLAYING;
        events.add(new GameEvent(tick,GameEvent.Type.MATCH_STARTED,requesterId,players.size()));
        return true;
    }

    public synchronized void submitInput(int playerId,GameInput input){
        Player p=players.get(playerId);
        if(p==null||input==null||input.sequence<=p.lastInputSequence)return;
        p.lastInputSequence=input.sequence;p.input=input;
    }

    public synchronized void tick(){
        tick++;
        if(phase!=GameSnapshot.Phase.PLAYING)return;
        boolean beforeBlackout=blackout;
        playTicks++;
        HorrorDirector.State director=HorrorDirector.state(playTicks,objectives.poweredBreakers(),huntSurgeTicks);
        blackout=director.blackout;
        if(blackout!=beforeBlackout){
            events.add(new GameEvent(tick,blackout?GameEvent.Type.BLACKOUT_STARTED:GameEvent.Type.BLACKOUT_ENDED,0,0));
        }
        for(Player p:players.values())updatePlayer(p);
        updateInteractions();
        director=HorrorDirector.state(playTicks,objectives.poweredBreakers(),huntSurgeTicks);
        updateMonster(director);
        if(monsterAttackCooldown>0)monsterAttackCooldown--;
        if(huntSurgeTicks>0)huntSurgeTicks--;
        evaluateTerminalState();
    }

    private void updatePlayer(Player p){
        if(p.downed||p.escaped)return;
        PlayerMotion.State next=PlayerMotion.step(
            new PlayerMotion.State(p.pos,p.yawRadians,p.stamina,p.flashlightBattery,p.flashlightOn),p.input);
        p.pos=next.pos;p.yawRadians=next.yawRadians;p.stamina=next.stamina;
        p.flashlightBattery=next.flashlightBattery;p.flashlightOn=next.flashlightOn;
    }

    private void updateInteractions(){
        for(Player p:players.values()){
            if(p.downed||p.escaped||!p.input.interact)continue;

            if(objectives.tryRecoverKeycard(p.pos)){
                events.add(new GameEvent(tick,GameEvent.Type.KEYCARD_RECOVERED,p.id,0));
            }

            if(p.carriedFuseIndex<0){
                int fuse=objectives.tryTakeFuse(p.pos);
                if(fuse>=0){
                    p.carriedFuseIndex=fuse;
                    events.add(new GameEvent(tick,GameEvent.Type.FUSE_PICKED,p.id,fuse));
                }
            }

            int breaker=objectives.tryPowerBreaker(p.pos,p.carriedFuseIndex>=0);
            if(breaker>=0){
                int fuse=p.carriedFuseIndex;
                p.carriedFuseIndex=-1;
                events.add(new GameEvent(tick,GameEvent.Type.FUSE_INSERTED,p.id,fuse));
                events.add(new GameEvent(tick,GameEvent.Type.BREAKER_ACTIVATED,p.id,breaker));
                huntSurgeTicks=Math.max(huntSurgeTicks,HorrorDirector.BREAKER_SURGE_TICKS);
                events.add(new GameEvent(tick,GameEvent.Type.HUNT_SURGE,p.id,breaker));
            }

            if(objectives.extractionReady()&&!exitUnlocked){
                exitUnlocked=true;
                events.add(new GameEvent(tick,GameEvent.Type.EXIT_UNLOCKED,p.id,0));
            }
            if(exitUnlocked&&p.pos.distance(FacilityMap.EXIT)<=1.45){
                p.escaped=true;p.flashlightOn=false;
                events.add(new GameEvent(tick,GameEvent.Type.PLAYER_ESCAPED,p.id,0));
            }
        }

        for(Player target:players.values()){
            if(!target.downed||target.escaped){target.reviveProgress=0;continue;}
            boolean helped=false;
            for(Player helper:players.values()){
                if(helper.id==target.id||helper.downed||helper.escaped||!helper.input.interact)continue;
                if(helper.pos.distance(target.pos)<=1.55){helped=true;break;}
            }
            target.reviveProgress=helped?target.reviveProgress+1:Math.max(0,target.reviveProgress-2);
            if(target.reviveProgress>=REVIVE_TICKS){
                target.downed=false;target.reviveProgress=0;target.stamina=Math.max(target.stamina,0.45);
                events.add(new GameEvent(tick,GameEvent.Type.PLAYER_REVIVED,target.id,0));
            }
        }
    }

    private void updateMonster(HorrorDirector.State director){
        Player visibleBest=null,heardBest=null;
        double visibleDistance=Double.MAX_VALUE,heardDistance=Double.MAX_VALUE;
        for(Player p:players.values()){
            if(p.downed||p.escaped)continue;
            double d=monsterPos.distance(p.pos);
            double vision=(p.flashlightOn?12.0:8.5)*director.monsterVisionMultiplier;
            if(d<=vision&&FacilityMap.hasLineOfSight(monsterPos,p.pos)&&d<visibleDistance){
                visibleBest=p;visibleDistance=d;
            }
            double motion=Math.sqrt(p.input.forward*p.input.forward+p.input.strafe*p.input.strafe);
            boolean noisy=p.input.sprint&&motion>0.15;
            double hearing=director.monsterHearingMultiplier;
            if((noisy&&d<=14.0*hearing)||(p.input.interact&&d<=8.0*hearing)){
                if(d<heardDistance){heardBest=p;heardDistance=d;}
            }
        }
        if(visibleBest!=null){
            monsterMode=GameSnapshot.MonsterMode.CHASE;monsterTargetPlayerId=visibleBest.id;
            monsterLastKnown=visibleBest.pos;monsterStateTicks=0;
        }else if(heardBest!=null&&monsterMode!=GameSnapshot.MonsterMode.CHASE){
            monsterMode=GameSnapshot.MonsterMode.INVESTIGATE;monsterTargetPlayerId=heardBest.id;
            monsterLastKnown=heardBest.pos;monsterStateTicks=0;
        }else monsterStateTicks++;

        Vec2 target;double speed;
        if(monsterMode==GameSnapshot.MonsterMode.CHASE){
            Player tp=players.get(monsterTargetPlayerId);
            if(tp!=null&&!tp.downed&&!tp.escaped)monsterLastKnown=tp.pos;
            target=monsterLastKnown;speed=MONSTER_CHASE_SPEED;
            if(visibleBest==null&&monsterStateTicks>70){
                monsterMode=GameSnapshot.MonsterMode.SEARCH;monsterTargetPlayerId=0;monsterStateTicks=0;
            }
        }else if(monsterMode==GameSnapshot.MonsterMode.INVESTIGATE){
            target=monsterLastKnown;speed=2.85;
            if(monsterPos.distance(target)<0.7||monsterStateTicks>100){
                monsterMode=GameSnapshot.MonsterMode.SEARCH;monsterTargetPlayerId=0;monsterStateTicks=0;
            }
        }else if(monsterMode==GameSnapshot.MonsterMode.SEARCH){
            double angle=monsterStateTicks*0.17+1.1;
            target=monsterLastKnown.add(new Vec2(Math.cos(angle)*2.5,Math.sin(angle)*2.5));speed=2.15;
            if(monsterStateTicks>110){monsterMode=GameSnapshot.MonsterMode.ROAM;monsterStateTicks=0;}
        }else{
            target=FacilityMap.PATROL[patrolIndex%FacilityMap.PATROL.length];speed=MONSTER_ROAM_SPEED;
            if(monsterPos.distance(target)<0.8)patrolIndex++;
        }
        speed*=director.monsterSpeedMultiplier;
        Vec2 dir=target.subtract(monsterPos).normalized();
        monsterPos=FacilityMap.moveWithSlide(monsterPos,dir.scale(speed*DT),MONSTER_RADIUS);

        if(monsterAttackCooldown==0){
            Player victim=null;double best=Double.MAX_VALUE;
            for(Player p:players.values()){
                if(p.downed||p.escaped)continue;
                double d=monsterPos.distance(p.pos);if(d<best){best=d;victim=p;}
            }
            if(victim!=null&&best<=0.95){
                victim.downed=true;victim.flashlightOn=false;victim.reviveProgress=0;monsterAttackCooldown=32;
                monsterMode=GameSnapshot.MonsterMode.SEARCH;monsterTargetPlayerId=0;monsterLastKnown=victim.pos;monsterStateTicks=0;
                events.add(new GameEvent(tick,GameEvent.Type.PLAYER_DOWNED,victim.id,0));
            }
        }
    }

    private void evaluateTerminalState(){
        if(phase!=GameSnapshot.Phase.PLAYING||players.isEmpty())return;
        int active=0,escaped=0,downed=0;
        for(Player p:players.values()){
            if(p.escaped)escaped++;else if(p.downed)downed++;else active++;
        }
        if(active==0){
            if(escaped>0){phase=GameSnapshot.Phase.WON;events.add(new GameEvent(tick,GameEvent.Type.MATCH_WON,0,escaped));}
            else if(downed>0){phase=GameSnapshot.Phase.LOST;events.add(new GameEvent(tick,GameEvent.Type.MATCH_LOST,0,downed));}
        }
    }

    public synchronized GameSnapshot snapshot(){
        ArrayList<GameSnapshot.PlayerView> views=new ArrayList<>();
        HorrorDirector.State director=HorrorDirector.state(playTicks,objectives.poweredBreakers(),huntSurgeTicks);
        for(Player p:players.values()){
            double tension=p.downed||p.escaped?0.0:clamp01(1.0-monsterPos.distance(p.pos)/13.0);
            views.add(new GameSnapshot.PlayerView(p.id,p.name,p.pos.x,p.pos.z,p.yawRadians,
                p.stamina,p.flashlightBattery,tension,p.flashlightOn,p.downed,p.escaped,p.ready,
                p.carriedFuseIndex>=0,p.lastInputSequence));
        }
        return new GameSnapshot(tick,phase,leaderPlayerId,views,
            new GameSnapshot.MonsterView(monsterPos.x,monsterPos.z,monsterMode,monsterTargetPlayerId),
            objectives.breakers(),objectives.fusesTaken(),objectives.keycardRecovered(),exitUnlocked,
            director.blackout,director.threatLevel,huntSurgeTicks);
    }

    public synchronized List<GameEvent> drainEvents(){ArrayList<GameEvent> out=new ArrayList<>(events);events.clear();return out;}
    private static String sanitizeName(String name){
        if(name==null)return "Player";String s=name.trim();if(s.isEmpty())return "Player";return s.length()>16?s.substring(0,16):s;
    }
    private static double clamp01(double v){return Math.max(0.0,Math.min(1.0,v));}

    private static final class Player{
        final int id;final String name;Vec2 pos;double yawRadians,stamina=1.0,flashlightBattery=1.0;
        boolean flashlightOn,downed,escaped,ready;int reviveProgress,lastInputSequence,carriedFuseIndex=-1;GameInput input=GameInput.IDLE;
        Player(int id,String name,Vec2 pos){this.id=id;this.name=name;this.pos=pos;}
    }
}
