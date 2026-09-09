from pathlib import Path

path = Path('android-smoke/probe-app/src/main/java/com/hoonex/solarnet/probe/MainActivity.java')
text = path.read_text()

replacements = [
    ('private static final String PROBE_VERSION = "0.3.0";',
     'private static final String PROBE_VERSION = "0.4.0";'),
    ('    private TransportMode activeMode;\n    private boolean hostMode;\n',
     '    private TransportMode activeMode;\n    private boolean hostMode;\n    private ProbeEvidenceSnapshot bluetoothStartEvidence;\n    private ProbeEvidenceSnapshot nearbyStartEvidence;\n'),
    ('            public void onFinished(ProbeSoakStats.Snapshot snapshot, String reason) {\n                String result = buildSoakResultJson(mode, snapshot, reason);\n                Log.i("SolarNetProbe", "SOLARNET_PROBE_RESULT " + result);\n                log("SOAK FINISH transport=" + mode.evidenceName + " " + snapshot.toSummary() + " reason=" + reason);\n            }',
     '            public void onFinished(ProbeSoakStats.Snapshot snapshot, String reason) {\n                ProbeEvidenceSnapshot start = startEvidence(mode);\n                ProbeEvidenceSnapshot end = ProbeDeviceEvidence.capture(MainActivity.this, BuildConfig.SOURCE_SHA);\n                String result = ProbeEvidenceEnvelope.toJson(\n                        mode.evidenceName,\n                        PROBE_VERSION,\n                        Build.MANUFACTURER + " " + Build.MODEL,\n                        Build.VERSION.SDK_INT,\n                        Build.VERSION.RELEASE,\n                        start,\n                        end,\n                        snapshot.toJson(reason));\n                setStartEvidence(mode, null);\n                Log.i("SolarNetProbe", "SOLARNET_PROBE_RESULT " + result);\n                log("SOAK FINISH transport=" + mode.evidenceName + " " + snapshot.toSummary() + " reason=" + reason);\n            }'),
    ('        try {\n            String runId = controller.start();\n            setStatus(activeMode.displayName + " soak " + runId);\n        } catch (Throwable t) {\n            log("ERROR start soak: " + message(t));\n        }',
     '        try {\n            setStartEvidence(activeMode, ProbeDeviceEvidence.capture(this, BuildConfig.SOURCE_SHA));\n            String runId = controller.start();\n            setStatus(activeMode.displayName + " soak " + runId);\n        } catch (Throwable t) {\n            setStartEvidence(activeMode, null);\n            log("ERROR start soak: " + message(t));\n        }'),
    ('    private String buildSoakResultJson(\n            TransportMode mode,\n            ProbeSoakStats.Snapshot snapshot,\n            String reason) {\n        String device = Build.MANUFACTURER + " " + Build.MODEL;\n        return "{" +\n                "\\\"transport\\\":\\\"" + mode.evidenceName + "\\\"," +\n                "\\\"probeVersion\\\":\\\"" + PROBE_VERSION + "\\\"," +\n                "\\\"deviceModel\\\":\\\"" + jsonEscape(device) + "\\\"," +\n                "\\\"sdkInt\\\":" + Build.VERSION.SDK_INT + "," +\n                "\\\"result\\\":" + snapshot.toJson(reason) +\n                "}";\n    }',
     '    private ProbeEvidenceSnapshot startEvidence(TransportMode mode) {\n        return mode == TransportMode.BLUETOOTH_CLASSIC ? bluetoothStartEvidence : nearbyStartEvidence;\n    }\n\n    private void setStartEvidence(TransportMode mode, ProbeEvidenceSnapshot snapshot) {\n        if (mode == TransportMode.BLUETOOTH_CLASSIC) bluetoothStartEvidence = snapshot;\n        else nearbyStartEvidence = snapshot;\n    }')
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, got {count}: {old[:80]!r}')
    text = text.replace(old, new)

path.write_text(text)
