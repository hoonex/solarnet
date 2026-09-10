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

/** First-person industrial-horror renderer. Geometry remains lightweight enough for the prototype client. */
final class NightshiftRenderer implements GLSurfaceView.Renderer {
    private static final float[] CUBE={
        -1,-1,1,1,-1,1,1,1,1,-1,-1,1,1,1,1,-1,1,1,
        1,-1,-1,-1,-1,-1,-1,1,-1,1,-1,-1,-1,1,-1,1,1,-1,
        -1,-1,-1,-1,-1,1,-1,1,1,-1,-1,-1,-1,1,1,-1,1,-1,
        1,-1,1,1,-1,-1,1,1,-1,1,-1,1,1,1,-1,1,1,1,
        -1,1,1,1,1,1,1,1,-1,-1,1,1,1,1,-1,-1,1,-1,
        -1,-1,-1,1,-1,-1,1,-1,1,-1,-1,-1,1,-1,1,-1,-1,1
    };
    private static final float[][] FACE_NORMALS={
        {0,0,1},{0,0,-1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}
    };
    private static final float[][] LIGHTS={
        {-8f,-14f},{0f,-14f},{8f,-11f},{-7f,-5f},{2f,-1f},
        {8f,4f},{-7f,9f},{1f,11f},{8f,14f},{0f,15f}
    };
    private static final float[][] COLUMNS={
        {-10.7f,-14.0f},{10.7f,-14.0f},{-10.7f,-5.5f},{10.7f,-5.5f},
        {-10.7f,4.0f},{10.7f,4.0f},{-10.7f,12.5f},{10.7f,12.5f}
    };
    private static final float[][] CRATES={
        {-10.7f,-10.8f,.65f},{10.6f,-9.0f,.72f},{-10.5f,6.2f,.62f},
        {10.5f,6.8f,.58f},{-5.5f,16.7f,.52f},{5.8f,16.6f,.50f}
    };

    private final FloatBuffer cubeBuffer;
    private final FloatBuffer normalBuffer;
    private final float[] projection=new float[16],view=new float[16],model=new float[16],pv=new float[16],mvp=new float[16];
    private final SnapshotInterpolator interpolation=new SnapshotInterpolator();
    private final ClientPrediction prediction=new ClientPrediction();
    private volatile GameSnapshot snapshot;
    private volatile int localPlayerId;
    private volatile float pitch,localYaw;
    private volatile float movementStrength;
    private volatile boolean sprinting;
    private float aspect=16f/9f;
    private float hunterYaw;
    private float previousHunterX=Float.NaN,previousHunterZ=Float.NaN;
    private int program,positionHandle,normalHandle,mvpHandle,modelHandle,colorHandle,eyeHandle,flashDirHandle,flashOnHandle,ambientHandle,emissiveHandle;

    NightshiftRenderer(){
        cubeBuffer=ByteBuffer.allocateDirect(CUBE.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        cubeBuffer.put(CUBE).position(0);
        float[] normals=new float[36*3];
        int o=0;
        for(int face=0;face<6;face++)for(int v=0;v<6;v++){
            normals[o++]=FACE_NORMALS[face][0];normals[o++]=FACE_NORMALS[face][1];normals[o++]=FACE_NORMALS[face][2];
        }
        normalBuffer=ByteBuffer.allocateDirect(normals.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        normalBuffer.put(normals).position(0);
    }
    void setLocalPlayerId(int id){localPlayerId=id;}
    void setPitch(float pitch){this.pitch=pitch;}
    void setLocalYaw(float yaw){this.localYaw=yaw;}
    void setMotionState(double movement,boolean sprinting){this.movementStrength=(float)Math.max(0,Math.min(1,movement));this.sprinting=sprinting;}
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
        GLES20.glClearColor(.002f,.003f,.004f,1);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        String vs=
            "uniform mat4 uMVP; uniform mat4 uModel; attribute vec3 aPos; attribute vec3 aNormal;"+
            "varying vec3 vWorld; varying vec3 vNormal;"+
            "void main(){vec4 w=uModel*vec4(aPos,1.0);vWorld=w.xyz;vNormal=normalize(mat3(uModel)*aNormal);gl_Position=uMVP*vec4(aPos,1.0);}";
        String fs=
            "precision mediump float; uniform vec4 uColor; uniform vec3 uEye; uniform vec2 uFlashDir;"+
            "uniform float uFlashOn; uniform float uAmbient; uniform float uEmissive; varying vec3 vWorld; varying vec3 vNormal;"+
            "void main(){vec3 delta=vWorld-uEye;float dist=length(delta);vec2 flat=normalize(delta.xz+vec2(0.0001));"+
            "float cone=max(dot(flat,uFlashDir),0.0);float beam=smoothstep(0.72,0.965,cone)*(1.0-smoothstep(2.0,16.5,dist))*uFlashOn;"+
            "vec3 toEye=normalize(uEye-vWorld);float face=.24+.76*max(dot(normalize(vNormal),toEye),0.0);"+
            "float light=uAmbient+beam*(.48+.78*face)+uEmissive;vec3 lit=uColor.rgb*min(1.55,light+uEmissive*.72);"+
            "float fog=smoothstep(8.0,27.0,dist);vec3 fogColor=vec3(0.003,0.006,0.008);"+
            "vec3 rgb=mix(lit,fogColor,fog);gl_FragColor=vec4(rgb,uColor.a);}";
        program=link(vs,fs);
        positionHandle=GLES20.glGetAttribLocation(program,"aPos");
        normalHandle=GLES20.glGetAttribLocation(program,"aNormal");
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
        aspect=width/(float)Math.max(1,height);
    }

    @Override public void onDrawFrame(GL10 gl){
        long now=System.nanoTime();double seconds=now/1_000_000_000.0;GameSnapshot s=snapshot;
        float px=0,pz=-15,yaw=localYaw;
        boolean localFlash=true,localDowned=false;
        float localTension=0f,localBattery=1f;
        ClientPrediction.Pose local=prediction.sample(now);
        if(local!=null){px=(float)local.x;pz=(float)local.z;localFlash=local.flashlightOn;}
        if(s!=null){
            for(GameSnapshot.PlayerView p:s.players)if(p.id==localPlayerId){
                if(local==null){px=(float)p.x;pz=(float)p.z;localFlash=p.flashlightOn;}
                localTension=(float)p.tension;localBattery=(float)p.flashlightBattery;localDowned=p.downed;break;
            }
        }

        boolean blackout=s!=null&&s.blackout;
        float ambient=blackout?(localFlash?.075f:.018f):(localFlash?.185f:.065f);
        ambient*=1f-Math.min(.22f,localTension*.18f);
        float flashStrength=localFlash?1f:0f;
        if(localBattery<.18f&&localFlash){
            double flicker=.62+.38*Math.sin(seconds*31.0)+.18*Math.sin(seconds*71.0);
            flashStrength=(float)Math.max(.22,Math.min(1.0,flicker));
        }

        float speedPhase=(float)(seconds*(sprinting?12.2:8.1));
        float bob=localDowned?-.82f:(float)Math.sin(speedPhase)*.040f*movementStrength;
        float side=(float)Math.cos(speedPhase*.5f)*.020f*movementStrength;
        float shake=(s!=null&&s.huntSurgeTicks>0)?(float)Math.sin(seconds*35.0)*.010f:0f;
        float sy=(float)Math.sin(yaw),cy=(float)Math.cos(yaw);
        float camX=px+cy*side+shake,camZ=pz-sy*side;
        float eyeY=1.55f+bob;
        float cp=(float)Math.cos(pitch),sp=(float)Math.sin(pitch);
        float fov=67f+(sprinting?2.6f:0f)*movementStrength+localTension*1.8f;
        Matrix.perspectiveM(projection,0,fov,aspect,.075f,72f);
        Matrix.setLookAtM(view,0,camX,eyeY,camZ,camX+sy*cp,eyeY+sp,camZ+cy*cp,0,1,0);
        Matrix.multiplyMM(pv,0,projection,0,view,0);

        GLES20.glClearColor(blackout?.001f:.002f,blackout?.0015f:.003f,blackout?.003f:.0045f,1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniform3f(eyeHandle,camX,eyeY,camZ);
        GLES20.glUniform2f(flashDirHandle,sy,cy);
        GLES20.glUniform1f(flashOnHandle,flashStrength);
        GLES20.glUniform1f(ambientHandle,ambient);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(normalHandle);
        cubeBuffer.position(0);normalBuffer.position(0);
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,0,cubeBuffer);
        GLES20.glVertexAttribPointer(normalHandle,3,GLES20.GL_FLOAT,false,0,normalBuffer);

        drawFacility(now,blackout);
        drawObjectives(s,now);
        if(s!=null){
            for(GameSnapshot.PlayerView p:s.players){
                if(p.id==localPlayerId||p.escaped)continue;
                SnapshotInterpolator.PlayerPose pose=interpolation.samplePlayer(p.id,now);
                float x=(float)(pose==null?p.x:pose.x),z=(float)(pose==null?p.z:pose.z);
                drawPlayer(x,z,(float)p.yawRadians,p.downed,p.carryingFuse);
            }
            SnapshotInterpolator.MonsterPose m=interpolation.sampleMonster(now);
            float mx=(float)(m==null?s.monster.x:m.x),mz=(float)(m==null?s.monster.z:m.z);
            drawHunter(mx,mz,s.monster.mode==GameSnapshot.MonsterMode.CHASE,seconds);
        }
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(normalHandle);
    }

    private void drawFacility(long now,boolean blackout){
        drawBox(0,-.10f,0,12,.10f,18,.080f,.086f,.088f,0f);
        drawBox(0,3.04f,0,12,.08f,18,.026f,.031f,.034f,0f);
        drawBox(0,1.45f,-18.05f,12.15f,1.45f,.10f,.115f,.120f,.122f,0f);
        drawBox(0,1.45f,18.05f,12.15f,1.45f,.10f,.105f,.110f,.112f,0f);
        drawBox(-12.05f,1.45f,0,.10f,1.45f,18.15f,.105f,.110f,.112f,0f);
        drawBox(12.05f,1.45f,0,.10f,1.45f,18.15f,.105f,.110f,.112f,0f);

        for(FacilityMap.Wall wall:FacilityMap.WALLS){
            float cx=(float)((wall.minX+wall.maxX)*.5),cz=(float)((wall.minZ+wall.maxZ)*.5);
            float sx=(float)((wall.maxX-wall.minX)*.5),sz=(float)((wall.maxZ-wall.minZ)*.5);
            drawBox(cx,1.43f,cz,sx,1.43f,sz,.135f,.140f,.142f,0f);
            drawBox(cx,.08f,cz,sx+.03f,.07f,sz+.03f,.055f,.060f,.061f,0f);
        }

        for(int x=-10;x<=10;x+=2)drawBox(x,-.002f,0,.012f,.006f,17.8f,.025f,.028f,.029f,0f);
        for(int z=-16;z<=16;z+=2)drawBox(0,-.001f,z,11.8f,.005f,.012f,.025f,.028f,.029f,0f);
        for(float[] c:COLUMNS){
            drawBox(c[0],1.45f,c[1],.18f,1.45f,.18f,.17f,.175f,.17f,0f);
            drawBox(c[0],.12f,c[1],.28f,.08f,.28f,.055f,.058f,.056f,0f);
        }

        drawBox(-10.85f,2.68f,0,.10f,.10f,17.5f,.18f,.19f,.19f,0f);
        drawBox(10.85f,2.68f,0,.10f,.10f,17.5f,.14f,.15f,.155f,0f);
        drawBox(0,2.70f,-16.8f,10.5f,.12f,.12f,.13f,.14f,.145f,0f);
        drawBox(0,2.68f,15.9f,10.4f,.08f,.09f,.20f,.11f,.06f,0f);
        drawBox(0,2.48f,-7.0f,2.15f,.22f,.36f,.095f,.105f,.108f,0f);
        drawBox(-7.6f,2.52f,.5f,2.45f,.18f,.28f,.085f,.092f,.095f,0f);
        drawBox(6.7f,2.54f,-1.2f,2.75f,.19f,.26f,.085f,.092f,.095f,0f);

        for(int i=0;i<LIGHTS.length;i++){
            double seconds=now/1_000_000_000.0;
            float pulse=(float)(.76+.17*Math.sin(seconds*3.7+i*1.7));
            drawBox(LIGHTS[i][0],2.84f,LIGHTS[i][1],.34f,.055f,.78f,.055f,.060f,.060f,0f);
            if(blackout)drawBox(LIGHTS[i][0],2.77f,LIGHTS[i][1],.20f,.035f,.63f,.52f,.018f,.014f,.58f*pulse);
            else drawBox(LIGHTS[i][0],2.77f,LIGHTS[i][1],.24f,.035f,.69f,.43f,.52f,.51f,.72f*pulse);
        }

        drawLockerBank(2.18f,-7.7f,0f);
        drawLockerBank(-5.18f,.45f,180f);
        drawLockerBank(4.18f,-1.15f,180f);
        drawLockerBank(-3.68f,6.55f,0f);
        for(float[] c:CRATES)drawCrates(c[0],c[1],c[2]);

        for(int i=0;i<8;i++){
            float x=-1.75f+i*.50f;
            boolean yellow=(i&1)==0;
            drawBoxRot(x,.018f,15.55f,.18f,.015f,.62f,22f,yellow?.48f:.055f,yellow?.38f:.055f,yellow?.055f:.055f,yellow?.10f:0f);
        }
    }

    private void drawLockerBank(float x,float z,float rotation){
        for(int i=0;i<3;i++){
            float offset=(i-1)*.62f;
            double r=Math.toRadians(rotation);
            float wx=x+(float)Math.cos(r)*offset,wz=z-(float)Math.sin(r)*offset;
            drawBoxPose(wx,1.02f,wz,.28f,1.00f,.22f,0,rotation,0,.095f,.115f,.120f,0f);
            drawBoxPose(wx,1.52f,wz,.22f,.012f,.225f,0,rotation,0,.16f,.18f,.18f,.03f);
            drawBoxPose(wx,.82f,wz,.025f,.10f,.235f,0,rotation,0,.30f,.32f,.30f,.06f);
        }
    }

    private void drawCrates(float x,float z,float size){
        drawBoxRot(x,size,z,size,size,size,0,.16f,.13f,.095f,0f);
        drawBoxRot(x+.33f*size,size*2.55f,z-.22f*size,size*.72f,size*.55f,size*.72f,12f,.12f,.105f,.080f,0f);
        drawBox(x,size*1.02f,z,size*1.03f,.045f,size*1.03f,.26f,.22f,.15f,.015f);
    }

    private void drawObjectives(GameSnapshot s,long now){
        double seconds=now/1_000_000_000.0;
        float pulse=(float)(.55+.18*Math.sin(seconds*4.0));
        for(int i=0;i<FacilityMap.FUSES.length;i++){
            boolean taken=s!=null&&i<s.fusesTaken.length&&s.fusesTaken[i];
            if(!taken){
                float x=(float)FacilityMap.FUSES[i].x,z=(float)FacilityMap.FUSES[i].z;
                drawBox(x,.12f,z,.34f,.10f,.28f,.09f,.10f,.105f,0f);
                drawBox(x,.31f,z,.16f,.18f,.10f,.82f,.42f,.045f,pulse);
                drawBox(x,.47f,z,.08f,.04f,.18f,.92f,.64f,.10f,.72f);
            }
        }
        if(s==null||!s.keycardRecovered){
            float x=(float)FacilityMap.KEYCARD.x,z=(float)FacilityMap.KEYCARD.z;
            drawBox(x,.58f,z,.38f,.58f,.22f,.07f,.085f,.095f,0f);
            drawBox(x,.87f,z-.20f,.24f,.18f,.035f,.08f,.31f,.43f,.48f+pulse*.25f);
            drawBox(x,.94f,z-.24f,.20f,.025f,.035f,.18f,.72f,.79f,.72f);
        }

        for(int i=0;i<FacilityMap.BREAKERS.length;i++){
            boolean on=s!=null&&i<s.breakers.length&&s.breakers[i];
            float x=(float)FacilityMap.BREAKERS[i].x,z=(float)FacilityMap.BREAKERS[i].z;
            drawBox(x,.72f,z,.42f,.72f,.28f,.11f,.115f,.12f,0f);
            drawBox(x,.92f,z-.29f,.28f,.28f,.035f,on?.07f:.28f,on?.48f:.06f,on?.16f:.035f,on?.55f:.03f);
            drawBoxPose(x,.56f,z-.32f,.05f,.22f,.05f,on?-28f:28f,0,0,.62f,.64f,.58f,on?.12f:0f);
        }
        drawExit(s!=null&&s.exitUnlocked);
    }

    private void drawExit(boolean open){
        float z=(float)FacilityMap.EXIT.z+.10f;
        drawBox(-2.05f,1.52f,z,.18f,1.52f,.28f,.22f,.20f,.18f,0f);
        drawBox(2.05f,1.52f,z,.18f,1.52f,.28f,.22f,.20f,.18f,0f);
        drawBox(0,2.90f,z,2.22f,.14f,.28f,.22f,.20f,.18f,0f);
        float offset=open?1.63f:.90f;
        drawBox(-offset,1.48f,z-.02f,.84f,1.38f,.13f,open?.075f:.18f,open?.17f:.035f,open?.12f:.028f,open?.16f:.02f);
        drawBox(offset,1.48f,z-.02f,.84f,1.38f,.13f,open?.075f:.18f,open?.17f:.035f,open?.12f:.028f,open?.16f:.02f);
        drawBox(0,2.73f,z-.30f,.64f,.08f,.03f,open?.08f:.55f,open?.62f:.04f,open?.30f:.025f,.58f);
    }

    private void drawPlayer(float x,float z,float yaw,boolean downed,boolean carryingFuse){
        float deg=(float)Math.toDegrees(yaw);
        float baseY=downed?.26f:.92f;
        float bodyR=downed?.28f:.11f,bodyG=downed?.045f:.32f,bodyB=.42f;
        drawBoxPose(x,baseY,z,.25f,downed?.22f:.52f,.16f,downed?72f:0,deg,0,bodyR,bodyG,bodyB,0f);
        if(!downed){
            drawBoxPose(x,1.62f,z,.18f,.20f,.18f,0,deg,0,.18f,.20f,.19f,0f);
            drawHumanPart(x,z,-.16f,.39f,.04f,.10f,.38f,.10f,deg,0,0,bodyR*.8f,bodyG*.8f,bodyB*.8f);
            drawHumanPart(x,z,.16f,.39f,.04f,.10f,.38f,.10f,deg,0,0,bodyR*.8f,bodyG*.8f,bodyB*.8f);
        }
        if(carryingFuse)drawBoxPose(x,1.30f,z-.22f,.11f,.11f,.09f,0,deg,0,.92f,.49f,.05f,.50f);
    }

    private void drawHumanPart(float bx,float bz,float ox,float y,float oz,float sx,float sy,float sz,float yawDeg,float rx,float rz,float r,float g,float b){
        double a=Math.toRadians(yawDeg);float wx=bx+ox*(float)Math.cos(a)+oz*(float)Math.sin(a);float wz=bz-ox*(float)Math.sin(a)+oz*(float)Math.cos(a);
        drawBoxPose(wx,y,wz,sx,sy,sz,rx,yawDeg,rz,r,g,b,0f);
    }

    private void drawHunter(float mx,float mz,boolean chase,double seconds){
        if(!Float.isNaN(previousHunterX)){
            float dx=mx-previousHunterX,dz=mz-previousHunterZ;
            if(dx*dx+dz*dz>.00005f)hunterYaw=(float)Math.atan2(dx,dz);
        }
        previousHunterX=mx;previousHunterZ=mz;
        float yawDeg=(float)Math.toDegrees(hunterYaw);
        float gait=(float)Math.sin(seconds*(chase?10.8:6.2));
        float red=chase?.50f:.16f;
        float skin=.035f;

        hunterPart(mx,mz,0,1.25f,0,.34f,.68f,.19f,yawDeg,0,0,skin+.035f,skin,skin,0f);
        hunterPart(mx,mz,0,.63f,0,.27f,.23f,.18f,yawDeg,0,0,.055f,.035f,.035f,0f);
        hunterPart(mx,mz,0,1.66f,.01f,.40f,.08f,.23f,yawDeg,0,0,red,.018f,.018f,chase?.07f:.01f);
        hunterPart(mx,mz,-.48f,1.08f,.01f,.09f,.72f,.09f,yawDeg,gait*12f,9f,.055f,.035f,.035f,0f);
        hunterPart(mx,mz,.48f,1.03f,-.03f,.09f,.77f,.09f,yawDeg,-gait*12f,-13f,.055f,.035f,.035f,0f);
        hunterPart(mx,mz,-.19f,.34f,0,.11f,.43f,.12f,yawDeg,-gait*17f,2f,.045f,.028f,.028f,0f);
        hunterPart(mx,mz,.19f,.34f,0,.11f,.43f,.12f,yawDeg,gait*17f,-2f,.045f,.028f,.028f,0f);
        float headTilt=(float)Math.sin(seconds*2.1)*4f;
        hunterPart(mx,mz,0,2.05f,.03f,.25f,.31f,.24f,yawDeg,headTilt,0,.055f,.035f,.035f,0f);
        hunterPart(mx,mz,0,1.83f,.18f,.20f,.07f,.16f,yawDeg,8f,0,.10f,.018f,.018f,chase?.12f:.03f);
        hunterPart(mx,mz,-.095f,2.08f,.255f,.035f,.030f,.022f,yawDeg,0,0,.92f,.035f,.020f,chase?.95f:.54f);
        hunterPart(mx,mz,.095f,2.08f,.255f,.035f,.030f,.022f,yawDeg,0,0,.92f,.035f,.020f,chase?.95f:.54f);
    }

    private void hunterPart(float bx,float bz,float ox,float y,float oz,float sx,float sy,float sz,float yawDeg,float rx,float rz,float r,float g,float b,float emissive){
        double a=Math.toRadians(yawDeg);float wx=bx+ox*(float)Math.cos(a)+oz*(float)Math.sin(a);float wz=bz-ox*(float)Math.sin(a)+oz*(float)Math.cos(a);
        drawBoxPose(wx,y,wz,sx,sy,sz,rx,yawDeg,rz,r,g,b,emissive);
    }

    private void drawBox(float x,float y,float z,float sx,float sy,float sz,float r,float g,float b,float emissive){
        drawBoxPose(x,y,z,sx,sy,sz,0,0,0,r,g,b,emissive);
    }

    private void drawBoxRot(float x,float y,float z,float sx,float sy,float sz,float ry,float r,float g,float b,float emissive){
        drawBoxPose(x,y,z,sx,sy,sz,0,ry,0,r,g,b,emissive);
    }

    private void drawBoxPose(float x,float y,float z,float sx,float sy,float sz,float rx,float ry,float rz,float r,float g,float b,float emissive){
        Matrix.setIdentityM(model,0);
        Matrix.translateM(model,0,x,y,z);
        if(ry!=0)Matrix.rotateM(model,0,ry,0,1,0);
        if(rx!=0)Matrix.rotateM(model,0,rx,1,0,0);
        if(rz!=0)Matrix.rotateM(model,0,rz,0,0,1);
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
