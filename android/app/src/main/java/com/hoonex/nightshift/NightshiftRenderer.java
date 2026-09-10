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
    private static final float[][] LIGHTS={
        {-8f,-14f},{0f,-14f},{8f,-11f},{-7f,-5f},{2f,-1f},
        {8f,4f},{-7f,9f},{1f,11f},{8f,14f},{0f,15f}
    };

    private final FloatBuffer cubeBuffer;
    private final float[] projection=new float[16],view=new float[16],model=new float[16],pv=new float[16],mvp=new float[16];
    private final SnapshotInterpolator interpolation=new SnapshotInterpolator();
    private final ClientPrediction prediction=new ClientPrediction();
    private volatile GameSnapshot snapshot;
    private volatile int localPlayerId;
    private volatile float pitch,localYaw;
    private int program,positionHandle,mvpHandle,modelHandle,colorHandle,eyeHandle,flashDirHandle,flashOnHandle,ambientHandle,emissiveHandle;

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
        GLES20.glClearColor(.003f,.005f,.008f,1);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        String vs=
            "uniform mat4 uMVP; uniform mat4 uModel; attribute vec3 aPos; varying vec3 vWorld;"+
            "void main(){ vec4 w=uModel*vec4(aPos,1.0); vWorld=w.xyz; gl_Position=uMVP*vec4(aPos,1.0); }";
        String fs=
            "precision mediump float; uniform vec4 uColor; uniform vec3 uEye; uniform vec2 uFlashDir;"+
            "uniform float uFlashOn; uniform float uAmbient; uniform float uEmissive; varying vec3 vWorld;"+
            "void main(){ vec3 d=vWorld-uEye; float dist=length(d); vec2 flat=normalize(d.xz+vec2(0.0001));"+
            "float cone=max(dot(flat,uFlashDir),0.0); float beam=smoothstep(0.76,0.965,cone)"+
            "*(1.0-smoothstep(2.0,15.0,dist))*uFlashOn; float fog=1.0-smoothstep(6.0,23.0,dist);"+
            "float light=max(uEmissive,uAmbient+beam*0.95); vec3 lit=uColor.rgb*min(1.35,light+beam*0.45);"+
            "vec3 fogColor=vec3(0.004,0.007,0.010); vec3 rgb=mix(fogColor,lit,max(0.07,fog));"+
            "gl_FragColor=vec4(rgb,uColor.a); }";
        program=link(vs,fs);
        positionHandle=GLES20.glGetAttribLocation(program,"aPos");
        mvpHandle=GLES20.glGetUniformLocation(program,"uMVP");
        modelHandle=GLES20.glGetUniformLocation(program,"uModel");
        colorHandle=GLES20.glGetUniformLocation(program,"uColor");
        eyeHandle=GLES20.glGetUniformLocation(program,"uEye");
        flashDirHandle=GLES20.glGetUniformLocation(program,"uFlashDir");
        flashOnHandle=GLES20.glGetUniformLocation(program,"uFlashOn");
        ambientHandle=GLES20.glGetUniformLocation(program,"uAmbient");
        emissiveHandle=GLES20.glGetUniformLocation(program,"uEmissive");
    }

    @Override public void onSurfaceChanged(GL10 gl,int width,int height){
        GLES20.glViewport(0,0,width,height);
        Matrix.perspectiveM(projection,0,67f,width/(float)Math.max(1,height),.08f,70f);
    }

    @Override public void onDrawFrame(GL10 gl){
        long now=System.nanoTime();GameSnapshot s=snapshot;
        float px=0,pz=-15,yaw=localYaw;
        boolean localFlash=true;
        float localTension=0f;
        ClientPrediction.Pose local=prediction.sample(now);
        if(local!=null){
            px=(float)local.x;pz=(float)local.z;localFlash=local.flashlightOn;
        } else if(s!=null) {
            for(GameSnapshot.PlayerView p:s.players)if(p.id==localPlayerId){
                px=(float)p.x;pz=(float)p.z;localFlash=p.flashlightOn;localTension=(float)p.tension;break;
            }
        }
        if(s!=null){
            for(GameSnapshot.PlayerView p:s.players)if(p.id==localPlayerId){localTension=(float)p.tension;break;}
        }

        boolean blackout=s!=null&&s.blackout;
        float ambient=blackout?(localFlash?.10f:.035f):(localFlash?.24f:.10f);
        ambient*=1f-Math.min(.18f,localTension*.14f);
        GLES20.glClearColor(blackout?.0015f:.003f,blackout?.002f:.005f,blackout?.0035f:.008f,1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);

        float cp=(float)Math.cos(pitch),sp=(float)Math.sin(pitch),sy=(float)Math.sin(yaw),cy=(float)Math.cos(yaw),eyeY=1.55f;
        Matrix.setLookAtM(view,0,px,eyeY,pz,px+sy*cp,eyeY+sp,pz+cy*cp,0,1,0);
        Matrix.multiplyMM(pv,0,projection,0,view,0);

        GLES20.glUseProgram(program);
        GLES20.glUniform3f(eyeHandle,px,eyeY,pz);
        GLES20.glUniform2f(flashDirHandle,sy,cy);
        GLES20.glUniform1f(flashOnHandle,localFlash?1f:0f);
        GLES20.glUniform1f(ambientHandle,ambient);
        GLES20.glEnableVertexAttribArray(positionHandle);
        cubeBuffer.position(0);
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,0,cubeBuffer);

        drawBox(0,-.12f,0,12,.10f,18,.085f,.09f,.095f,0f);
        drawBox(0,3.0f,0,12,.08f,18,.035f,.04f,.045f,0f);
        for(FacilityMap.Wall wall:FacilityMap.WALLS){
            float cx=(float)((wall.minX+wall.maxX)*.5),cz=(float)((wall.minZ+wall.maxZ)*.5);
            float sx=(float)((wall.maxX-wall.minX)*.5),sz=(float)((wall.maxZ-wall.minZ)*.5);
            drawBox(cx,1.45f,cz,sx,1.45f,sz,.14f,.145f,.15f,0f);
        }

        for(int i=0;i<LIGHTS.length;i++){
            float pulse=(float)(0.72+0.18*Math.sin((now/1_000_000_000.0)*4.0+i*1.7));
            if(blackout)drawBox(LIGHTS[i][0],2.82f,LIGHTS[i][1],.16f,.05f,.62f,.55f,.025f,.018f,.72f*pulse);
            else drawBox(LIGHTS[i][0],2.82f,LIGHTS[i][1],.18f,.05f,.70f,.48f,.58f,.61f,.84f*pulse);
        }

        for(int i=0;i<FacilityMap.FUSES.length;i++){
            boolean taken=s!=null&&i<s.fusesTaken.length&&s.fusesTaken[i];
            if(!taken)drawBox((float)FacilityMap.FUSES[i].x,.22f,(float)FacilityMap.FUSES[i].z,.18f,.22f,.12f,.95f,.58f,.08f,.65f);
        }
        if(s==null||!s.keycardRecovered){
            drawBox((float)FacilityMap.KEYCARD.x,.20f,(float)FacilityMap.KEYCARD.z,.27f,.04f,.18f,.10f,.48f,.72f,.72f);
        }

        for(int i=0;i<FacilityMap.BREAKERS.length;i++){
            boolean on=s!=null&&i<s.breakers.length&&s.breakers[i];
            drawBox((float)FacilityMap.BREAKERS[i].x,.72f,(float)FacilityMap.BREAKERS[i].z,.35f,.72f,.35f,
                on?.10f:.36f,on?.62f:.13f,on?.22f:.06f,on?.55f:.06f);
        }
        boolean open=s!=null&&s.exitUnlocked;
        drawBox((float)FacilityMap.EXIT.x,1.5f,(float)FacilityMap.EXIT.z,1.8f,1.5f,.18f,
            open?.06f:.28f,open?.48f:.035f,open?.30f:.035f,open?.65f:.08f);

        if(s!=null){
            for(GameSnapshot.PlayerView p:s.players){
                if(p.id==localPlayerId||p.escaped)continue;
                SnapshotInterpolator.PlayerPose pose=interpolation.samplePlayer(p.id,now);
                float x=(float)(pose==null?p.x:pose.x),z=(float)(pose==null?p.z:pose.z);
                drawBox(x,.86f,z,.28f,.86f,.28f,p.downed?.34f:.12f,p.downed?.05f:.36f,.50f,p.carryingFuse?.15f:0f);
                if(p.carryingFuse)drawBox(x,1.48f,z,.10f,.10f,.10f,.95f,.55f,.06f,.55f);
            }
            SnapshotInterpolator.MonsterPose m=interpolation.sampleMonster(now);
            float mx=(float)(m==null?s.monster.x:m.x),mz=(float)(m==null?s.monster.z:m.z);
            boolean chase=s.monster.mode==GameSnapshot.MonsterMode.CHASE;
            float red=chase?.72f:.38f;
            drawBox(mx,1.03f,mz,.40f,1.03f,.40f,red,.018f,.02f,chase?.20f:.02f);
            drawBox(mx,2.15f,mz,.30f,.23f,.30f,red*.85f,.012f,.015f,chase?.24f:.03f);
        }
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private void drawBox(float x,float y,float z,float sx,float sy,float sz,float r,float g,float b,float emissive){
        Matrix.setIdentityM(model,0);
        Matrix.translateM(model,0,x,y,z);
        Matrix.scaleM(model,0,sx,sy,sz);
        Matrix.multiplyMM(mvp,0,pv,0,model,0);
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0);
        GLES20.glUniformMatrix4fv(modelHandle,1,false,model,0);
        GLES20.glUniform4f(colorHandle,r,g,b,1f);
        GLES20.glUniform1f(emissiveHandle,emissive);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,36);
    }

    private static int link(String vs,String fs){
        int v=compile(GLES20.GL_VERTEX_SHADER,vs),f=compile(GLES20.GL_FRAGMENT_SHADER,fs),p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL link: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p;
    }
    private static int compile(int type,String src){
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(s));return s;
    }
}
