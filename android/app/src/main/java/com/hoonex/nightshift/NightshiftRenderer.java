package com.hoonex.nightshift;

import com.hoonex.nightshift.core.*;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class NightshiftRenderer implements GLSurfaceView.Renderer {
    private static final float[] CUBE={
        -1,-1,1,1,-1,1,1,1,1,-1,-1,1,1,1,1,-1,1,1,
        1,-1,-1,-1,-1,-1,-1,1,-1,1,-1,-1,-1,1,-1,1,1,-1,
        -1,-1,-1,-1,-1,1,-1,1,1,-1,-1,-1,-1,1,1,-1,1,-1,
        1,-1,1,1,-1,-1,1,1,-1,1,-1,1,1,1,-1,1,1,1,
        -1,1,1,1,1,1,1,1,-1,-1,1,1,1,1,-1,-1,1,-1,
        -1,-1,-1,1,-1,-1,1,-1,1,-1,-1,-1,1,-1,1,-1,-1,1
    };
    private final FloatBuffer cubeBuffer;
    private final float[] projection=new float[16],view=new float[16],model=new float[16],pv=new float[16],mvp=new float[16];
    private final SnapshotInterpolator interpolation=new SnapshotInterpolator();
    private final ClientPrediction prediction=new ClientPrediction();
    private volatile GameSnapshot snapshot;
    private volatile int localPlayerId;
    private volatile float pitch,localYaw;
    private int program,positionHandle,mvpHandle,colorHandle;

    NightshiftRenderer(){
        cubeBuffer=ByteBuffer.allocateDirect(CUBE.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        cubeBuffer.put(CUBE).position(0);
    }
    void setLocalPlayerId(int id){localPlayerId=id;}
    void setPitch(float pitch){this.pitch=pitch;}
    void setLocalYaw(float yaw){this.localYaw=yaw;}
    void applyLocalInput(GameInput input){prediction.apply(input,System.nanoTime());}

    void setSnapshot(GameSnapshot s){
        if(s==null)return;
        long now=System.nanoTime();snapshot=s;interpolation.push(s,now);
        for(GameSnapshot.PlayerView p:s.players)if(p.id==localPlayerId){
            prediction.reconcile(p,now);
            break;
        }
    }

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        GLES20.glClearColor(.008f,.012f,.016f,1);GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        String vs="uniform mat4 uMVP; attribute vec3 aPos; void main(){ gl_Position=uMVP*vec4(aPos,1.0); }";
        String fs="precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }";
        program=link(vs,fs);positionHandle=GLES20.glGetAttribLocation(program,"aPos");mvpHandle=GLES20.glGetUniformLocation(program,"uMVP");colorHandle=GLES20.glGetUniformLocation(program,"uColor");
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height){
        GLES20.glViewport(0,0,width,height);Matrix.perspectiveM(projection,0,67f,width/(float)Math.max(1,height),.08f,70f);
    }
    @Override public void onDrawFrame(GL10 gl){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        long now=System.nanoTime();GameSnapshot s=snapshot;
        float px=0,pz=-15,yaw=localYaw;
        ClientPrediction.Pose local=prediction.sample(now);
        if(local!=null){px=(float)local.x;pz=(float)local.z;}
        else if(s!=null)for(GameSnapshot.PlayerView p:s.players)if(p.id==localPlayerId){px=(float)p.x;pz=(float)p.z;break;}
        float cp=(float)Math.cos(pitch),sp=(float)Math.sin(pitch),sy=(float)Math.sin(yaw),cy=(float)Math.cos(yaw),eyeY=1.55f;
        Matrix.setLookAtM(view,0,px,eyeY,pz,px+sy*cp,eyeY+sp,pz+cy*cp,0,1,0);
        Matrix.multiplyMM(pv,0,projection,0,view,0);
        GLES20.glUseProgram(program);GLES20.glEnableVertexAttribArray(positionHandle);cubeBuffer.position(0);
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,0,cubeBuffer);

        drawBox(0,-.12f,0,12,.10f,18,.06f,.075f,.08f,1);
        for(FacilityMap.Wall wall:FacilityMap.WALLS){
            float cx=(float)((wall.minX+wall.maxX)*.5),cz=(float)((wall.minZ+wall.maxZ)*.5);
            float sx=(float)((wall.maxX-wall.minX)*.5),sz=(float)((wall.maxZ-wall.minZ)*.5);
            drawBox(cx,1.45f,cz,sx,1.45f,sz,.12f,.14f,.15f,1);
        }
        for(int i=0;i<FacilityMap.BREAKERS.length;i++){
            boolean on=s!=null&&i<s.breakers.length&&s.breakers[i];
            drawBox((float)FacilityMap.BREAKERS[i].x,.7f,(float)FacilityMap.BREAKERS[i].z,.35f,.7f,.35f,on?.15f:.32f,on?.48f:.18f,on?.20f:.08f,1);
        }
        drawBox((float)FacilityMap.EXIT.x,1.5f,(float)FacilityMap.EXIT.z,1.8f,1.5f,.18f,.12f,.30f,.34f,1);

        if(s!=null){
            for(GameSnapshot.PlayerView p:s.players){
                if(p.id==localPlayerId||p.escaped)continue;
                SnapshotInterpolator.PlayerPose pose=interpolation.samplePlayer(p.id,now);
                float x=(float)(pose==null?p.x:pose.x),z=(float)(pose==null?p.z:pose.z);
                drawBox(x,.85f,z,.28f,.85f,.28f,p.downed?.30f:.16f,p.downed?.08f:.38f,.48f,1);
            }
            SnapshotInterpolator.MonsterPose m=interpolation.sampleMonster(now);
            float mx=(float)(m==null?s.monster.x:m.x),mz=(float)(m==null?s.monster.z:m.z);
            drawBox(mx,1.05f,mz,.42f,1.05f,.42f,.48f,.04f,.04f,1);
        }
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private void drawBox(float x,float y,float z,float sx,float sy,float sz,float r,float g,float b,float a){
        Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,y,z);Matrix.scaleM(model,0,sx,sy,sz);Matrix.multiplyMM(mvp,0,pv,0,model,0);
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0);GLES20.glUniform4f(colorHandle,r,g,b,a);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,36);
    }
    private static int link(String vs,String fs){
        int v=compile(GLES20.GL_VERTEX_SHADER,vs),f=compile(GLES20.GL_FRAGMENT_SHADER,fs),p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL link: "+GLES20.glGetProgramInfoLog(p));GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p;
    }
    private static int compile(int type,String src){
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(s));return s;
    }
}
