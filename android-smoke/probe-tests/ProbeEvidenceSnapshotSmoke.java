package com.hoonex.solarnet.probe;

public final class ProbeEvidenceSnapshotSmoke {
    public static void main(String[] args) {
        ProbeEvidenceSnapshot snapshot = new ProbeEvidenceSnapshot(
                123456789L,
                87,
                31.4,
                4123,
                2,
                "abcdef123456",
                "0123456789abcdef");

        String json = snapshot.toJson();
        require(json.contains("\"capturedAtEpochMs\":123456789"), "timestamp missing");
        require(json.contains("\"batteryPercent\":87"), "battery percent missing");
        require(json.contains("\"batteryTemperatureC\":31.4"), "battery temperature missing");
        require(json.contains("\"batteryVoltageMv\":4123"), "voltage missing");
        require(json.contains("\"thermalStatus\":2"), "thermal status missing");
        require(json.contains("\"sourceSha\":\"abcdef123456\""), "source SHA missing");
        require(json.contains("\"apkSha256\":\"0123456789abcdef\""), "APK SHA missing");

        ProbeEvidenceSnapshot unavailable = new ProbeEvidenceSnapshot(
                1L, -1, Double.NaN, -1, -1, null, null);
        String unavailableJson = unavailable.toJson();
        require(unavailableJson.contains("\"batteryTemperatureC\":null"), "NaN must serialize as null");
        require(unavailableJson.contains("\"sourceSha\":\"unknown\""), "unknown source fallback missing");
        require(unavailableJson.contains("\"apkSha256\":\"unknown\""), "unknown APK fallback missing");

        String envelope = ProbeEvidenceEnvelope.toJson(
                "nearby-connections",
                "0.4.0",
                "Example Phone",
                36,
                "16",
                snapshot,
                unavailable,
                "{\"completionReason\":\"completed\"}");
        require(envelope.contains("\"transport\":\"nearby-connections\""), "transport missing");
        require(envelope.contains("\"probeVersion\":\"0.4.0\""), "probe version missing");
        require(envelope.contains("\"androidRelease\":\"16\""), "Android release missing");
        require(envelope.contains("\"evidence\":{\"start\":"), "evidence start missing");
        require(envelope.contains("\"end\":"), "evidence end missing");
        require(envelope.contains("\"completionReason\":\"completed\""), "result payload missing");

        System.out.println("Probe evidence snapshot smoke: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
