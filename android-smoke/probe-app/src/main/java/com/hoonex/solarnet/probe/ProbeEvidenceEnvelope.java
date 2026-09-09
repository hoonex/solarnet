package com.hoonex.solarnet.probe;

final class ProbeEvidenceEnvelope {
    private ProbeEvidenceEnvelope() { }

    static String toJson(
            String transport,
            String probeVersion,
            String deviceModel,
            int sdkInt,
            String androidRelease,
            ProbeEvidenceSnapshot start,
            ProbeEvidenceSnapshot end,
            String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) resultJson = "null";
        return "{" +
                "\"transport\":\"" + jsonEscape(transport) + "\"," +
                "\"probeVersion\":\"" + jsonEscape(probeVersion) + "\"," +
                "\"deviceModel\":\"" + jsonEscape(deviceModel) + "\"," +
                "\"sdkInt\":" + sdkInt + "," +
                "\"androidRelease\":\"" + jsonEscape(androidRelease) + "\"," +
                "\"evidence\":{" +
                    "\"start\":" + (start == null ? "null" : start.toJson()) + "," +
                    "\"end\":" + (end == null ? "null" : end.toJson()) +
                "}," +
                "\"result\":" + resultJson +
                "}";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
