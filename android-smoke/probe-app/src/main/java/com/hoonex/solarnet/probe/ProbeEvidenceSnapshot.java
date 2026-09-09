package com.hoonex.solarnet.probe;

final class ProbeEvidenceSnapshot {
    final long capturedAtEpochMs;
    final int batteryPercent;
    final double batteryTemperatureC;
    final int batteryVoltageMv;
    final int thermalStatus;
    final String sourceSha;
    final String apkSha256;

    ProbeEvidenceSnapshot(
            long capturedAtEpochMs,
            int batteryPercent,
            double batteryTemperatureC,
            int batteryVoltageMv,
            int thermalStatus,
            String sourceSha,
            String apkSha256) {
        this.capturedAtEpochMs = capturedAtEpochMs;
        this.batteryPercent = batteryPercent;
        this.batteryTemperatureC = batteryTemperatureC;
        this.batteryVoltageMv = batteryVoltageMv;
        this.thermalStatus = thermalStatus;
        this.sourceSha = sourceSha == null ? "unknown" : sourceSha;
        this.apkSha256 = apkSha256 == null ? "unknown" : apkSha256;
    }

    String toJson() {
        return "{" +
                "\"capturedAtEpochMs\":" + capturedAtEpochMs + "," +
                "\"batteryPercent\":" + batteryPercent + "," +
                "\"batteryTemperatureC\":" + number(batteryTemperatureC) + "," +
                "\"batteryVoltageMv\":" + batteryVoltageMv + "," +
                "\"thermalStatus\":" + thermalStatus + "," +
                "\"sourceSha\":\"" + jsonEscape(sourceSha) + "\"," +
                "\"apkSha256\":\"" + jsonEscape(apkSha256) + "\"" +
                "}";
    }

    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "null";
        return Double.toString(value);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
