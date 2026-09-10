package com.hoonex.solarnet.gridduel;

/**
 * Deterministic camera policy for Pulse Hockey's 3D arena.
 *
 * The camera follows the action only within a small safe window. Height is fitted from the
 * complete rink dimensions first, then increased near the edges so following never turns into
 * a close-up that hides the opposite side of the board.
 */
public final class PulseHockeyCameraRig {
    static final float FOV_Y_DEGREES = 58f;
    static final float MIN_ASPECT = 0.45f;
    static final float MAX_ASPECT = 2.40f;
    static final float MIN_EYE_HEIGHT = 22.0f;
    static final float MAX_EYE_HEIGHT = 31.0f;
    static final float EYE_BACK_DISTANCE = 7.0f;
    static final float LOOK_AHEAD_Z = 0.65f;
    static final float MAX_FOCUS_X = 0.55f;
    static final float MAX_FOCUS_Z = 1.25f;
    static final float FOLLOW_ALPHA = 0.075f;

    private static final float ARENA_HALF_WIDTH_MARGIN = PulseHockeyGame.HALF_WIDTH + 0.90f;
    private static final float ARENA_HALF_LENGTH_MARGIN = PulseHockeyGame.HALF_LENGTH + 1.25f;

    private PulseHockeyCameraRig() {}

    static final class Frame {
        final float focusX;
        final float focusZ;
        final float eyeHeight;
        final float fovY;

        Frame(float focusX, float focusZ, float eyeHeight, float fovY) {
            this.focusX = focusX;
            this.focusZ = focusZ;
            this.eyeHeight = eyeHeight;
            this.fovY = fovY;
        }
    }

    static Frame plan(
            float aspect,
            float puckX,
            float puckZ,
            float sunX,
            float sunZ,
            float moonX,
            float moonZ) {
        float safeAspect = clamp(aspect, MIN_ASPECT, MAX_ASPECT);

        // Puck leads the composition; both mallets keep the framing anchored to the whole play.
        float focusX = clamp(puckX * 0.60f + sunX * 0.20f + moonX * 0.20f,
                -MAX_FOCUS_X, MAX_FOCUS_X);
        float focusZ = clamp(puckZ * 0.62f + sunZ * 0.19f + moonZ * 0.19f,
                -MAX_FOCUS_Z, MAX_FOCUS_Z);

        double tanHalfVertical = Math.tan(Math.toRadians(FOV_Y_DEGREES * 0.5));
        float widthFit = (float) (ARENA_HALF_WIDTH_MARGIN / (tanHalfVertical * safeAspect) * 1.12);
        float lengthFit = (float) (ARENA_HALF_LENGTH_MARGIN / tanHalfVertical * 1.16);
        float baseHeight = Math.max(MIN_EYE_HEIGHT, Math.max(widthFit, lengthFit));

        // Pull farther back as the puck approaches either goal/wall and when actors spread wide.
        float edgeZ = clamp(Math.abs(puckZ) / PulseHockeyGame.HALF_LENGTH, 0f, 1f);
        float edgeX = clamp(Math.abs(puckX) / PulseHockeyGame.HALF_WIDTH, 0f, 1f);
        float spreadX = Math.max(puckX, Math.max(sunX, moonX))
                - Math.min(puckX, Math.min(sunX, moonX));
        float spreadExtra = Math.max(0f, spreadX - 4.0f) * 0.28f;
        float eyeHeight = clamp(baseHeight + edgeZ * 1.8f + edgeX * 0.7f + spreadExtra,
                MIN_EYE_HEIGHT, MAX_EYE_HEIGHT);

        return new Frame(focusX, focusZ, eyeHeight, FOV_Y_DEGREES);
    }

    static Frame blend(Frame current, Frame target) {
        if (current == null) return target;
        return new Frame(
                lerp(current.focusX, target.focusX, FOLLOW_ALPHA),
                lerp(current.focusZ, target.focusZ, FOLLOW_ALPHA),
                lerp(current.eyeHeight, target.eyeHeight, FOLLOW_ALPHA),
                target.fovY);
    }

    static float conservativeHorizontalHalfSpan(Frame frame, float aspect) {
        float safeAspect = clamp(aspect, MIN_ASPECT, MAX_ASPECT);
        double tanHalfVertical = Math.tan(Math.toRadians(frame.fovY * 0.5));
        return (float) (frame.eyeHeight * tanHalfVertical * safeAspect);
    }

    static float conservativeVerticalHalfSpan(Frame frame) {
        double tanHalfVertical = Math.tan(Math.toRadians(frame.fovY * 0.5));
        return (float) (frame.eyeHeight * tanHalfVertical);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
