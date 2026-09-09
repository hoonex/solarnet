package com.hoonex.solarnet.gridduel;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Real OpenGL ES 2.0 renderer and direct drag aiming surface for Pulse Hockey. */
public final class PulseHockey3DView extends GLSurfaceView {
    public interface AimListener {
        void onAimChanged(float dirX, float dirZ, float power);
        void onAimReleased(float dirX, float dirZ, float power);
    }

    private final ArenaRenderer arenaRenderer;
    private AimListener aimListener;
    private boolean aimEnabled;
    private float downX;
    private float downY;

    public PulseHockey3DView(Context context) {
        this(context, null);
    }

    public PulseHockey3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        arenaRenderer = new ArenaRenderer();
        setRenderer(arenaRenderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void setAimListener(AimListener listener) {
        this.aimListener = listener;
    }

    public void setAimEnabled(boolean enabled) {
        aimEnabled = enabled;
        if (!enabled) clearAim();
    }

    public void showGame(PulseHockeyGame game) {
        if (game == null) return;
        final RenderState state = RenderState.fromGame(game);
        queueEvent(() -> arenaRenderer.setState(state));
    }

    public void showFrame(PulseHockeyGame.MotionFrame frame, PulseHockeyGame game) {
        if (frame == null || game == null) return;
        final RenderState state = RenderState.fromFrame(frame, game);
        queueEvent(() -> arenaRenderer.setState(state));
    }

    public void showAim(PulseHockeyGame.Player player, float dirX, float dirZ, float power, PulseHockeyGame.Tactic tactic) {
        queueEvent(() -> arenaRenderer.setAim(player, dirX, dirZ, power, tactic));
    }

    public void clearAim() {
        queueEvent(arenaRenderer::clearAim);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!aimEnabled) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                dispatchAim(event.getX(), event.getY(), false);
                return true;
            case MotionEvent.ACTION_UP:
                dispatchAim(event.getX(), event.getY(), true);
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                clearAim();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void dispatchAim(float x, float y, boolean released) {
        float dx = x - downX;
        float dz = -(y - downY);
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < dp(12)) {
            dx = 0f;
            dz = 1f;
            length = 1f;
        }
        float dirX = dx / length;
        float dirZ = dz / length;
        float power = clamp(length / Math.max(dp(96), Math.min(getWidth(), getHeight()) * 0.34f), 0.18f, 1f);
        if (aimListener != null) {
            if (released) aimListener.onAimReleased(dirX, dirZ, power);
            else aimListener.onAimChanged(dirX, dirZ, power);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class RenderState {
        final float puckX;
        final float puckZ;
        final float sunX;
        final float sunZ;
        final float moonX;
        final float moonZ;
        final float goalHalfWidth;
        final PulseHockeyGame.Player active;
        final PulseHockeyGame.Stance sunStance;
        final PulseHockeyGame.Stance moonStance;
        final int overdrive;

        RenderState(
                float puckX,
                float puckZ,
                float sunX,
                float sunZ,
                float moonX,
                float moonZ,
                float goalHalfWidth,
                PulseHockeyGame.Player active,
                PulseHockeyGame.Stance sunStance,
                PulseHockeyGame.Stance moonStance,
                int overdrive) {
            this.puckX = puckX;
            this.puckZ = puckZ;
            this.sunX = sunX;
            this.sunZ = sunZ;
            this.moonX = moonX;
            this.moonZ = moonZ;
            this.goalHalfWidth = goalHalfWidth;
            this.active = active;
            this.sunStance = sunStance;
            this.moonStance = moonStance;
            this.overdrive = overdrive;
        }

        static RenderState fromGame(PulseHockeyGame game) {
            return new RenderState(
                    game.getPuckX(), game.getPuckZ(),
                    game.getMalletX(PulseHockeyGame.Player.SUN), game.getMalletZ(PulseHockeyGame.Player.SUN),
                    game.getMalletX(PulseHockeyGame.Player.MOON), game.getMalletZ(PulseHockeyGame.Player.MOON),
                    game.getGoalHalfWidth(), game.getActivePlayer(),
                    game.getStance(PulseHockeyGame.Player.SUN), game.getStance(PulseHockeyGame.Player.MOON),
                    game.getOverdriveLevel());
        }

        static RenderState fromFrame(PulseHockeyGame.MotionFrame frame, PulseHockeyGame game) {
            return new RenderState(
                    frame.puckX, frame.puckZ,
                    frame.sunX, frame.sunZ,
                    frame.moonX, frame.moonZ,
                    game.getGoalHalfWidth(), game.getActivePlayer(),
                    game.getStance(PulseHockeyGame.Player.SUN), game.getStance(PulseHockeyGame.Player.MOON),
                    game.getOverdriveLevel());
        }
    }

    private static final class ArenaRenderer implements Renderer {
        private static final float[] SUN = {1.00f, 0.60f, 0.15f, 1f};
        private static final float[] SUN_GLOW = {1.00f, 0.78f, 0.28f, 0.35f};
        private static final float[] MOON = {0.35f, 0.47f, 1.00f, 1f};
        private static final float[] MOON_GLOW = {0.48f, 0.58f, 1.00f, 0.35f};
        private static final float[] PUCK = {0.87f, 0.94f, 1.00f, 1f};
        private static final float[] BOARD = {0.045f, 0.065f, 0.10f, 1f};
        private static final float[] WALL = {0.12f, 0.17f, 0.25f, 1f};
        private static final float[] LINE = {0.20f, 0.66f, 0.78f, 0.62f};
        private static final float[] OVERDRIVE = {1.00f, 0.18f, 0.34f, 0.86f};

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] pv = new float[16];
        private final float[] mvp = new float[16];

        private RenderState state = new RenderState(
                0f, 0f, 0f, -5.8f, 0f, 5.8f, 2.1f,
                PulseHockeyGame.Player.SUN,
                PulseHockeyGame.Stance.NONE,
                PulseHockeyGame.Stance.NONE,
                0);
        private Mesh cube;
        private Mesh cylinder;
        private int program;
        private int aPosition;
        private int aNormal;
        private int uMvp;
        private int uModel;
        private int uColor;
        private int uLight;

        private boolean aiming;
        private PulseHockeyGame.Player aimPlayer = PulseHockeyGame.Player.NONE;
        private PulseHockeyGame.Tactic aimTactic = PulseHockeyGame.Tactic.STRIKE;
        private float aimX;
        private float aimZ = 1f;
        private float aimPower;

        void setState(RenderState state) {
            this.state = state;
        }

        void setAim(PulseHockeyGame.Player player, float dirX, float dirZ, float power, PulseHockeyGame.Tactic tactic) {
            aiming = true;
            aimPlayer = player;
            aimX = dirX;
            aimZ = dirZ;
            aimPower = power;
            aimTactic = tactic == null ? PulseHockeyGame.Tactic.STRIKE : tactic;
        }

        void clearAim() {
            aiming = false;
            aimPlayer = PulseHockeyGame.Player.NONE;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.018f, 0.026f, 0.045f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_CULL_FACE);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aNormal = GLES20.glGetAttribLocation(program, "aNormal");
            uMvp = GLES20.glGetUniformLocation(program, "uMvp");
            uModel = GLES20.glGetUniformLocation(program, "uModel");
            uColor = GLES20.glGetUniformLocation(program, "uColor");
            uLight = GLES20.glGetUniformLocation(program, "uLightDir");
            cube = Mesh.cube();
            cylinder = Mesh.cylinder(32);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float aspect = Math.max(0.5f, width / (float) Math.max(1, height));
            Matrix.perspectiveM(projection, 0, 48f, aspect, 1f, 70f);
            Matrix.setLookAtM(view, 0,
                    0f, 13.4f, -16.2f,
                    0f, 0f, 0.7f,
                    0f, 1f, 0f);
            Matrix.multiplyMM(pv, 0, projection, 0, view, 0);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniform3f(uLight, -0.35f, 0.90f, -0.22f);

            drawCube(0f, -0.38f, 0f, 10.1f, 0.55f, 18.1f, BOARD);
            drawCube(0f, -0.065f, 0f, 9.6f, 0.045f, 0.055f, LINE);
            drawCube(0f, -0.055f, -4.5f, 9.6f, 0.025f, 0.035f, new float[]{0.32f, 0.20f, 0.08f, 0.45f});
            drawCube(0f, -0.055f, 4.5f, 9.6f, 0.025f, 0.035f, new float[]{0.08f, 0.13f, 0.34f, 0.45f});

            drawCube(-5.05f, 0.25f, 0f, 0.22f, 0.82f, 18.5f, WALL);
            drawCube(5.05f, 0.25f, 0f, 0.22f, 0.82f, 18.5f, WALL);
            drawEndWalls(-9.08f, state.goalHalfWidth);
            drawEndWalls(9.08f, state.goalHalfWidth);

            float[] goalColor = state.overdrive > 0 ? OVERDRIVE : LINE;
            drawCube(-state.goalHalfWidth, 0.42f, -9.20f, 0.12f, 1.15f, 0.18f, goalColor);
            drawCube(state.goalHalfWidth, 0.42f, -9.20f, 0.12f, 1.15f, 0.18f, goalColor);
            drawCube(-state.goalHalfWidth, 0.42f, 9.20f, 0.12f, 1.15f, 0.18f, goalColor);
            drawCube(state.goalHalfWidth, 0.42f, 9.20f, 0.12f, 1.15f, 0.18f, goalColor);

            drawStance(PulseHockeyGame.Player.SUN, state.sunX, state.sunZ, state.sunStance, SUN_GLOW, SUN);
            drawStance(PulseHockeyGame.Player.MOON, state.moonX, state.moonZ, state.moonStance, MOON_GLOW, MOON);
            drawCylinder(state.puckX, 0.23f, state.puckZ, 0.47f, 0.22f, PUCK);

            if (aiming && aimPlayer != PulseHockeyGame.Player.NONE) drawAim();
        }

        private void drawEndWalls(float z, float goalHalfWidth) {
            float outer = PulseHockeyGame.HALF_WIDTH + 0.08f;
            float segment = Math.max(0.1f, outer - goalHalfWidth);
            float center = goalHalfWidth + segment * 0.5f;
            drawCube(-center, 0.25f, z, segment, 0.82f, 0.22f, WALL);
            drawCube(center, 0.25f, z, segment, 0.82f, 0.22f, WALL);
        }

        private void drawStance(
                PulseHockeyGame.Player player,
                float x,
                float z,
                PulseHockeyGame.Stance stance,
                float[] glow,
                float[] body) {
            boolean active = state.active == player;
            if (active || stance != PulseHockeyGame.Stance.NONE) {
                float ringScale = stance == PulseHockeyGame.Stance.GUARD ? 1.24f : stance == PulseHockeyGame.Stance.COUNTER ? 1.12f : 1.02f;
                float[] ringColor = stance == PulseHockeyGame.Stance.COUNTER ? OVERDRIVE : glow;
                drawCylinder(x, 0.09f, z, PulseHockeyGame.MALLET_RADIUS * ringScale, 0.055f, ringColor);
            }
            drawCylinder(x, 0.30f, z, PulseHockeyGame.MALLET_RADIUS, 0.42f, body);
            drawCylinder(x, 0.62f, z, PulseHockeyGame.MALLET_RADIUS * 0.58f, 0.30f, body);
        }

        private void drawAim() {
            float startX = aimPlayer == PulseHockeyGame.Player.SUN ? state.sunX : state.moonX;
            float startZ = aimPlayer == PulseHockeyGame.Player.SUN ? state.sunZ : state.moonZ;
            float magnitude = (float) Math.sqrt(aimX * aimX + aimZ * aimZ);
            if (magnitude < 0.001f) return;
            float dx = aimX / magnitude;
            float dz = aimZ / magnitude;
            float length = 1.4f + aimPower * 3.7f;
            float midX = startX + dx * length * 0.5f;
            float midZ = startZ + dz * length * 0.5f;
            float angle = (float) Math.toDegrees(Math.atan2(dx, dz));
            float[] color = aimTactic == PulseHockeyGame.Tactic.POWER ? OVERDRIVE : LINE;

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, midX, 0.12f, midZ);
            Matrix.rotateM(model, 0, angle, 0f, 1f, 0f);
            Matrix.scaleM(model, 0, 0.10f + aimPower * 0.06f, 0.065f, length);
            drawMesh(cube, color);

            drawCylinder(startX + dx * length, 0.15f, startZ + dz * length, 0.18f + aimPower * 0.08f, 0.10f, color);
        }

        private void drawCube(float x, float y, float z, float sx, float sy, float sz, float[] color) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, x, y, z);
            Matrix.scaleM(model, 0, sx, sy, sz);
            drawMesh(cube, color);
        }

        private void drawCylinder(float x, float y, float z, float radius, float height, float[] color) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, x, y, z);
            Matrix.scaleM(model, 0, radius, height, radius);
            drawMesh(cylinder, color);
        }

        private void drawMesh(Mesh mesh, float[] color) {
            Matrix.multiplyMM(mvp, 0, pv, 0, model, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            GLES20.glUniform4fv(uColor, 1, color, 0);
            mesh.draw(aPosition, aNormal);
        }

        private static int createProgram(String vertexShader, String fragmentShader) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException("OpenGL link failed: " + log);
            }
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("OpenGL compile failed: " + log);
            }
            return shader;
        }

        private static final String VERTEX_SHADER =
                "uniform mat4 uMvp;\n" +
                "uniform mat4 uModel;\n" +
                "uniform vec3 uLightDir;\n" +
                "attribute vec3 aPosition;\n" +
                "attribute vec3 aNormal;\n" +
                "varying float vLight;\n" +
                "void main(){\n" +
                "  vec3 n = normalize(mat3(uModel) * aNormal);\n" +
                "  vLight = 0.38 + 0.62 * max(dot(n, normalize(uLightDir)), 0.0);\n" +
                "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n" +
                "uniform vec4 uColor;\n" +
                "varying float vLight;\n" +
                "void main(){\n" +
                "  gl_FragColor = vec4(uColor.rgb * vLight, uColor.a);\n" +
                "}\n";
    }

    private static final class Mesh {
        private final FloatBuffer buffer;
        private final int vertexCount;

        Mesh(float[] data) {
            vertexCount = data.length / 6;
            buffer = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            buffer.put(data).position(0);
        }

        void draw(int aPosition, int aNormal) {
            int stride = 6 * 4;
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, buffer);
            buffer.position(3);
            GLES20.glEnableVertexAttribArray(aNormal);
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, buffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aNormal);
        }

        static Mesh cube() {
            float[] p = {
                    // front
                    -.5f,-.5f,.5f, 0,0,1,  .5f,-.5f,.5f, 0,0,1,  .5f,.5f,.5f, 0,0,1,
                    -.5f,-.5f,.5f, 0,0,1,  .5f,.5f,.5f, 0,0,1,  -.5f,.5f,.5f, 0,0,1,
                    // back
                    .5f,-.5f,-.5f, 0,0,-1,  -.5f,-.5f,-.5f, 0,0,-1,  -.5f,.5f,-.5f, 0,0,-1,
                    .5f,-.5f,-.5f, 0,0,-1,  -.5f,.5f,-.5f, 0,0,-1,  .5f,.5f,-.5f, 0,0,-1,
                    // left
                    -.5f,-.5f,-.5f, -1,0,0,  -.5f,-.5f,.5f, -1,0,0,  -.5f,.5f,.5f, -1,0,0,
                    -.5f,-.5f,-.5f, -1,0,0,  -.5f,.5f,.5f, -1,0,0,  -.5f,.5f,-.5f, -1,0,0,
                    // right
                    .5f,-.5f,.5f, 1,0,0,  .5f,-.5f,-.5f, 1,0,0,  .5f,.5f,-.5f, 1,0,0,
                    .5f,-.5f,.5f, 1,0,0,  .5f,.5f,-.5f, 1,0,0,  .5f,.5f,.5f, 1,0,0,
                    // top
                    -.5f,.5f,.5f, 0,1,0,  .5f,.5f,.5f, 0,1,0,  .5f,.5f,-.5f, 0,1,0,
                    -.5f,.5f,.5f, 0,1,0,  .5f,.5f,-.5f, 0,1,0,  -.5f,.5f,-.5f, 0,1,0,
                    // bottom
                    -.5f,-.5f,-.5f, 0,-1,0,  .5f,-.5f,-.5f, 0,-1,0,  .5f,-.5f,.5f, 0,-1,0,
                    -.5f,-.5f,-.5f, 0,-1,0,  .5f,-.5f,.5f, 0,-1,0,  -.5f,-.5f,.5f, 0,-1,0
            };
            return new Mesh(p);
        }

        static Mesh cylinder(int sides) {
            float[] data = new float[sides * 12 * 6];
            int cursor = 0;
            for (int i = 0; i < sides; i++) {
                float a0 = (float) (Math.PI * 2.0 * i / sides);
                float a1 = (float) (Math.PI * 2.0 * (i + 1) / sides);
                float x0 = (float) Math.cos(a0);
                float z0 = (float) Math.sin(a0);
                float x1 = (float) Math.cos(a1);
                float z1 = (float) Math.sin(a1);

                cursor = put(data, cursor, 0, .5f, 0, 0, 1, 0);
                cursor = put(data, cursor, x0, .5f, z0, 0, 1, 0);
                cursor = put(data, cursor, x1, .5f, z1, 0, 1, 0);

                cursor = put(data, cursor, 0, -.5f, 0, 0, -1, 0);
                cursor = put(data, cursor, x1, -.5f, z1, 0, -1, 0);
                cursor = put(data, cursor, x0, -.5f, z0, 0, -1, 0);

                cursor = put(data, cursor, x0, -.5f, z0, x0, 0, z0);
                cursor = put(data, cursor, x1, -.5f, z1, x1, 0, z1);
                cursor = put(data, cursor, x1, .5f, z1, x1, 0, z1);
                cursor = put(data, cursor, x0, -.5f, z0, x0, 0, z0);
                cursor = put(data, cursor, x1, .5f, z1, x1, 0, z1);
                cursor = put(data, cursor, x0, .5f, z0, x0, 0, z0);
            }
            return new Mesh(data);
        }

        private static int put(float[] target, int at, float x, float y, float z, float nx, float ny, float nz) {
            target[at++] = x;
            target[at++] = y;
            target[at++] = z;
            target[at++] = nx;
            target[at++] = ny;
            target[at++] = nz;
            return at;
        }
    }
}
