package com.hoonex.solarnet.gridduel;

public final class PulseHockeyCameraRigSmoke {
    public static void main(String[] args) {
        PulseHockeyCameraRig.Frame portrait = PulseHockeyCameraRig.plan(
                0.50f, 0f, 0f, 0f, -5.8f, 0f, 5.8f);
        assertTrue(portrait.fovY >= 56f, "overview FOV must be wider than the old close framing");
        assertTrue(portrait.eyeHeight > 20f, "overview camera must sit materially higher than old camera");
        assertTrue(
                PulseHockeyCameraRig.conservativeHorizontalHalfSpan(portrait, 0.50f)
                        >= PulseHockeyGame.HALF_WIDTH + 0.5f,
                "portrait framing must preserve full rink width with margin");
        assertTrue(
                PulseHockeyCameraRig.conservativeVerticalHalfSpan(portrait)
                        >= PulseHockeyGame.HALF_LENGTH + 1.0f,
                "framing must preserve full rink length with margin");

        PulseHockeyCameraRig.Frame moonEdge = PulseHockeyCameraRig.plan(
                0.50f, 0.8f, 8.5f, -1.0f, -6.0f, 1.2f, 6.0f);
        PulseHockeyCameraRig.Frame sunEdge = PulseHockeyCameraRig.plan(
                0.50f, -0.8f, -8.5f, -1.2f, -6.0f, 1.0f, 6.0f);
        assertTrue(moonEdge.focusZ > 0.5f, "camera should follow action toward MOON end");
        assertTrue(sunEdge.focusZ < -0.5f, "camera should follow action toward SUN end");
        assertTrue(Math.abs(moonEdge.focusZ) <= PulseHockeyCameraRig.MAX_FOCUS_Z + 0.001f,
                "follow must remain clamped");
        assertTrue(moonEdge.eyeHeight > portrait.eyeHeight,
                "edge action should trigger extra zoom-out");

        PulseHockeyCameraRig.Frame narrow = PulseHockeyCameraRig.plan(
                0.45f, 0f, 0f, 0f, -5.8f, 0f, 5.8f);
        assertTrue(narrow.eyeHeight >= portrait.eyeHeight,
                "narrow portrait screens must never zoom in more than wider portrait screens");

        PulseHockeyCameraRig.Frame blended = PulseHockeyCameraRig.blend(portrait, moonEdge);
        assertTrue(blended.focusZ > portrait.focusZ && blended.focusZ < moonEdge.focusZ,
                "follow must move toward action without snapping");
        assertTrue(blended.eyeHeight > portrait.eyeHeight && blended.eyeHeight < moonEdge.eyeHeight,
                "auto zoom must transition smoothly");

        PulseHockeyCameraRig.Frame repeated = PulseHockeyCameraRig.plan(
                0.50f, 0.8f, 8.5f, -1.0f, -6.0f, 1.2f, 6.0f);
        assertNear(moonEdge.focusX, repeated.focusX, "camera policy must be deterministic");
        assertNear(moonEdge.focusZ, repeated.focusZ, "camera policy must be deterministic");
        assertNear(moonEdge.eyeHeight, repeated.eyeHeight, "camera policy must be deterministic");

        System.out.println("Pulse Hockey overview camera smoke: PASS");
    }

    private static void assertNear(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > 0.0001f)
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
