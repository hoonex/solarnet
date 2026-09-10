package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class GameActivity extends Activity implements TcpGameClient.Listener {
    private final ScheduledExecutorService inputLoop=Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger inputSequence=new AtomicInteger(1);
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private NightshiftGameView gameView;private TcpGameClient client;private TextView hud,banner;
    private volatile boolean sprint,interact,flashlight=true;
    private int bannerGeneration;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);client=NightshiftSession.get();if(client==null){finish();return;}client.setListener(this);
        FrameLayout root=new FrameLayout(this);gameView=new NightshiftGameView(this);gameView.setLocalPlayerId(client.playerId());
        GameSnapshot existing=client.latestSnapshot();if(existing!=null)gameView.setSnapshot(existing);
        root.addView(gameView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));

        hud=new TextView(this);hud.setTextColor(Color.WHITE);hud.setTextSize(13);hud.setShadowLayer(4,0,1,Color.BLACK);hud.setPadding(dp(14),dp(8),dp(14),dp(8));
        root.addView(hud,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.TOP|Gravity.LEFT));

        banner=new TextView(this);banner.setTextColor(Color.WHITE);banner.setTextSize(18);banner.setGravity(Gravity.CENTER);banner.setShadowLayer(7,0,1,Color.BLACK);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(520),dp(64),Gravity.TOP|Gravity.CENTER_HORIZONTAL);bp.topMargin=dp(22);root.addView(banner,bp);

        Button run=button("RUN"),use=button("USE"),light=button("LIGHT");
        addBottom(root,run,Gravity.RIGHT,226);addBottom(root,use,Gravity.RIGHT,118);addBottom(root,light,Gravity.RIGHT,10);
        run.setOnTouchListener((v,e)->{sprint=e.getActionMasked()!=MotionEvent.ACTION_UP&&e.getActionMasked()!=MotionEvent.ACTION_CANCEL;return true;});
        use.setOnTouchListener((v,e)->{interact=e.getActionMasked()!=MotionEvent.ACTION_UP&&e.getActionMasked()!=MotionEvent.ACTION_CANCEL;return true;});
        light.setOnClickListener(v->{flashlight=!flashlight;light.setAlpha(flashlight?1f:.45f);});
        setContentView(root);inputLoop.scheduleAtFixedRate(this::sendInput,0,50,TimeUnit.MILLISECONDS);
    }

    private void sendInput(){
        if(client==null||gameView==null||!client.isConnected())return;
        GameInput input=new GameInput(inputSequence.getAndIncrement(),gameView.forward(),gameView.strafe(),gameView.yaw(),sprint,interact,flashlight);
        try{client.sendInput(input);gameView.applyLocalInput(input);}catch(IOException ignored){}
    }

    @Override public void onSnapshot(GameSnapshot snapshot){
        gameView.setSnapshot(snapshot);GameSnapshot.PlayerView me=null;for(GameSnapshot.PlayerView p:snapshot.players)if(p.id==client.playerId())me=p;
        GameSnapshot.PlayerView finalMe=me;runOnUiThread(()->{
            if(finalMe==null){hud.setText("Disconnected from room");return;}
            int active=0;for(boolean b:snapshot.breakers)if(b)active++;
            String power=snapshot.blackout?"  ·  POWER OUT":"";
            String fuse=finalMe.carryingFuse?"  ·  FUSE CARRIED":"";
            String card=snapshot.keycardRecovered?"  ·  KEYCARD ✓":"  ·  KEYCARD ?";
            String surge=snapshot.huntSurgeTicks>0?"  ·  HUNT "+Math.max(1,(snapshot.huntSurgeTicks+19)/20)+"s":"";
            hud.setText("ROOM "+client.roomCode()+"  ·  POWER "+active+"/"+snapshot.breakers.length+
                "  ·  THREAT "+snapshot.threatLevel+"/5"+card+fuse+power+surge+
                "\nSTAMINA "+Math.round(finalMe.stamina*100)+"%  ·  LIGHT "+Math.round(finalMe.flashlightBattery*100)+"%"+
                (finalMe.downed?"  ·  DOWNED":"")+(snapshot.exitUnlocked?"  ·  EXTRACTION OPEN":""));
            if(snapshot.phase==GameSnapshot.Phase.WON||snapshot.phase==GameSnapshot.Phase.LOST)hud.setText(hud.getText()+"  ·  "+snapshot.phase.name());
        });
    }

    @Override public void onEvent(String text){
        String message=formatEvent(text);
        if(message==null)return;
        runOnUiThread(()->showBanner(message));
    }

    private String formatEvent(String raw){
        if(raw==null)return null;
        String[] p=raw.split(":");
        String type=p.length>0?p[0]:"";
        return switch(type){
            case "FUSE_PICKED" -> "FUSE RECOVERED";
            case "KEYCARD_RECOVERED" -> "SECURITY KEYCARD RECOVERED";
            case "FUSE_INSERTED" -> "FUSE INSTALLED";
            case "BREAKER_ACTIVATED" -> "POWER CIRCUIT ONLINE";
            case "HUNT_SURGE" -> "THE HUNTER HEARD THAT";
            case "BLACKOUT_STARTED" -> "POWER FAILURE";
            case "BLACKOUT_ENDED" -> "EMERGENCY LIGHTS RESTORED";
            case "EXIT_UNLOCKED" -> "EXTRACTION DOOR UNLOCKED";
            case "PLAYER_DOWNED" -> "TEAMMATE DOWN";
            case "PLAYER_REVIVED" -> "TEAMMATE REVIVED";
            case "PLAYER_ESCAPED" -> "TEAMMATE EXTRACTED";
            case "MATCH_WON" -> "SHIFT SURVIVED";
            case "MATCH_LOST" -> "NO ONE MADE IT OUT";
            default -> null;
        };
    }

    private void showBanner(String text){
        int generation=++bannerGeneration;
        banner.setText(text);
        uiHandler.postDelayed(()->{if(generation==bannerGeneration)banner.setText("");},2400);
    }

    @Override public void onReconnecting(int attempt,int max){runOnUiThread(()->showBanner("SIGNAL LOST · RECONNECT "+attempt+"/"+max));}
    @Override public void onReconnected(){runOnUiThread(()->showBanner("LINK RESTORED · ROOM "+client.roomCode()));}
    @Override public void onError(String text){runOnUiThread(()->showBanner("NETWORK · "+text));}
    @Override public void onDisconnected(){runOnUiThread(()->showBanner("CONNECTION LOST"));}

    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAlpha(.82f);return b;}
    private void addBottom(FrameLayout root,View v,int gravity,int rightMargin){
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(96),dp(56),Gravity.BOTTOM|gravity);p.setMargins(dp(10),dp(10),dp(rightMargin),dp(12));root.addView(v,p);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){
        inputLoop.shutdownNow();uiHandler.removeCallbacksAndMessages(null);
        if(client!=null){client.close();NightshiftSession.clear(client);}super.onDestroy();
    }
}
