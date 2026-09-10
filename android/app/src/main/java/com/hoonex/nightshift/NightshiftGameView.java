package com.hoonex.nightshift;

import com.hoonex.nightshift.core.GameInput;
import com.hoonex.nightshift.core.GameSnapshot;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public final class NightshiftGameView extends GLSurfaceView {
    private final NightshiftRenderer renderer;
    private int movePointer=-1,lookPointer=-1;
    private float moveStartX,moveStartY,lookLastX,lookLastY;
    private volatile double forward,strafe,yaw,pitch;

    public NightshiftGameView(Context context){
        super(context);setEGLContextClientVersion(2);renderer=new NightshiftRenderer();setRenderer(renderer);setRenderMode(RENDERMODE_CONTINUOUSLY);
    }
    void setSnapshot(GameSnapshot snapshot){renderer.setSnapshot(snapshot);}
    void setLocalPlayerId(int id){renderer.setLocalPlayerId(id);}
    void applyLocalInput(GameInput input){renderer.applyLocalInput(input);}
    double forward(){return forward;}double strafe(){return strafe;}double yaw(){return yaw;}

    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int id=e.getPointerId(index);float x=e.getX(index),y=e.getY(index);
            if(x<getWidth()*.48f&&movePointer==-1){movePointer=id;moveStartX=x;moveStartY=y;}
            else if(lookPointer==-1){lookPointer=id;lookLastX=x;lookLastY=y;}
        }else if(action==MotionEvent.ACTION_MOVE){
            for(int i=0;i<e.getPointerCount();i++){
                int id=e.getPointerId(i);float x=e.getX(i),y=e.getY(i);
                if(id==movePointer){
                    float r=Math.max(90f,getWidth()*.11f);strafe=clamp((x-moveStartX)/r,-1,1);forward=clamp((moveStartY-y)/r,-1,1);
                }else if(id==lookPointer){
                    yaw+=(x-lookLastX)*.0065;pitch=clamp(pitch-(y-lookLastY)*.0048,-.75,.75);
                    lookLastX=x;lookLastY=y;renderer.setPitch((float)pitch);renderer.setLocalYaw((float)yaw);
                }
            }
        }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_CANCEL){
            int id=e.getPointerId(index);
            if(id==movePointer){movePointer=-1;forward=0;strafe=0;}
            if(id==lookPointer)lookPointer=-1;
            if(action==MotionEvent.ACTION_CANCEL){movePointer=-1;lookPointer=-1;forward=0;strafe=0;}
        }
        return true;
    }
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
