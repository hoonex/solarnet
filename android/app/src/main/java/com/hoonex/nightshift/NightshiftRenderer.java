package com.hoonex.nightshift;

import com.hoonex.nightshift.core.FacilityMap;
import com.hoonex.nightshift.core.GameSnapshot;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class NightshiftRenderer implements GLSurfaceView.Renderer {
    private static final float[] CUBE = {
        -1,-1, 1,  1,-1, 1,  1, 1, 1, -1,-1, 1,  1, 1, 1, -1, 1, 1,
         1,-1,-1, -1,-1,-1, -1, 1,-1,  1,-1,-1, -1, 1,-1,  1, 1,-1,
        -1,-1,-1, -1,-1, 1, -1, 1, 1, -1,-1,-1, -1, 1, 1, -1, 1,-1,
         1,-1, 1,  1,-1,-1,  1, 1,-1,  1,-1, 1,  1, 1,-1,  1, 1, 1,
        -1, 1, 1,  1, 1, 1,  1, 1,-1, -1, 1, 1,  1, 1,-1, -1, 1,-1,
        -1,-1,-1,  1,-1,-1,  1,-1, 1, -1,-1,-1,  1,-1, 1, -1,-1, 1
    };
    private final FloatBuffer cubeBuffer;
    private final float[] projection = new float[16], view = new float[16], model = new float[16], pv = new float[16], mvp = new float[16];
    private volatile GameSnapshot snapshot;
    private volatile int localPlayerId;
    private volatile float pitch;
    private int program, positionHandle, mvpHandle, colorHandle;

    NightshiftRenderer() {
        cubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        cubeBuffer.put(CUBE).position(0);
    }
    void setSnapshot(GameSnapshot s) { snapshot = s; }
    void setLocalPlayerId(int id) { localPlayerId = id; }
    void setPitch(float pitch) { this.pitch = pitch; }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.008f,0.012f,0.016f,1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        String vs = "uniform mat4 uMVP; attribute vec3 aPos; void main(){ gl_Position=uMVP*vec4(aPos,1.0); }";
        String fs = "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }";
        program = link(vs, fs);
        positionHandle = GLES20.glGetAttribLocation(program,"aPos");
        mvpHandle = GLES20.glGetUniformLocation(program,"uMVP");
        colorHandle = GLES20.glGetUniformLocation(program,"uColor");
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0,0,width,height);
        float aspect = width / (float)Math.max(1,height);
        Matrix.perspectiveM(projection,0,67f,aspect,0.08f,70f);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GameSnapshot s = snapshot;
        float px = 0f, pz = -15f, yaw = 0f;
        if (s != null) {
            for (GameSnapshot.PlayerView p : s.players) if (p.id == localPlayerId) { px=(float)p.x; pz=(float)p.z; yaw=(float)p.yawRadians; break; }
        }
        float cp=(float)Math.cos(pitch), sp=(float)Math.sin(pitch), sy=(float)Math.sin(yaw), cy=(float)Math.cos(yaw);
        float eyeY=1.55f;
        Matrix.setLookAtM(view,0, px,eyeY,pz, px+sy*cp,eyeY+sp,pz+cy*cp, 0,1,0);
        Matrix.multiplyMM(pv,0,projection,0,view,0);
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(positionHandle);
        cubeBuffer.position(0); GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,0,cubeBuffer);

        drawBox(0,-0.12f,0, 12f,0.10f,18f, 0.06f,0.075f,0.08f,1f);
        for (FacilityMap.Wall w : FacilityMap.WALLS) {
            float cx=(float)((w.minX+w.maxX)*0.5), cz=(float)((w.minZ+w.maxZ)*0.5);
            float sx=(float)((w.maxX-w.minX)*0.5), sz=(float)((w.maxZ-w.minZ)*0.5);
            drawBox(cx,1.45f,cz,sx,1.45f,sz,0.12f,0.14f,0.15f,1f);
        }
        for (int i=0;i<FacilityMap.BREAKERS.length;i++) {
            boolean on = s != null && i < s.breakers.length && s.breakers[i];
            drawBox((float)FacilityMap.BREAKERS[i].x,0.7f,(float)FacilityMap.BREAKERS[i].z,0.35f,0.7f,0.35f,
                on?0.15f:0.32f, on?0.48f:0.18f, on?0.20f:0.08f, 1f);
        }
        drawBox((float)FacilityMap.EXIT.x,1.5f,(float)FacilityMap.EXIT.z,1.8f,1.5f,0.18f,0.12f,0.30f,0.34f,1f);
        if (s != null) {
            for (GameSnapshot.PlayerView p : s.players) {
                if (p.id == localPlayerId || p.escaped) continue;
                drawBox((float)p.x,0.85f,(float)p.z,0.28f,0.85f,0.28f, p.downed?0.30f:0.16f,p.downed?0.08f:0.38f,0.48f,1f);
            }
            drawBox((float)s.monster.x,1.05f,(float)s.monster.z,0.42f,1.05f,0.42f,0.48f,0.04f,0.04f,1f);
        }
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private void drawBox(float x,float y,float z,float sx,float sy,float sz,float r,float g,float b,float a) {
        Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,sx,sy,sz);
        Matrix.multiplyMM(mvp,0,pv,0,model,0);
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0);
        GLES20.glUniform4f(colorHandle,r,g,b,a);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,36);
    }

    private static int link(String vs, String fs) {
        int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
        int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
        int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException("GL link: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p;
    }
    private static int compile(int type,String src){ int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s); int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0); if(ok[0]==0) throw new IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(s)); return s; }
}
