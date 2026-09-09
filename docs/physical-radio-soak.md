# Physical transport radio soak

SolarNet's transport Probe APK includes a bounded, machine-readable physical-radio soak harness. Probe 0.4.0 runs the same framed workload and metric accounting over Bluetooth Classic RFCOMM and Google Nearby Connections, and now captures start/end device evidence around every timed run.

The harness being present and compiling in CI does **not** prove physical-radio reliability. Until a real two-phone run is captured, device runtime, latency distribution, thermal behavior, and power behavior remain unverified.

For an apples-to-apples Nearby/Bluetooth procedure, also read `transport-soak-comparison.md`.

## What the soak measures

The client sends one framed probe per second for ten minutes by default:

```text
SNP1|PING|<runId>|<sequence>|<clientElapsedRealtimeNanos>
```

The host echoes the same run ID, sequence, and client timestamp:

```text
SNP1|PONG|<runId>|<sequence>|<clientElapsedRealtimeNanos>
```

The client measures round-trip time with Android's monotonic `SystemClock.elapsedRealtimeNanos()`. The host never compares clocks with the client; it only echoes the client timestamp.

The final record includes:

- unique probes sent and accepted PONGs;
- missing responses and loss percentage;
- duplicate responses;
- invalid/foreign/malformed responses;
- disconnect and bridge/send error counters;
- minimum, average, and maximum RTT;
- elapsed duration;
- device model, Android release/SDK, transport, and Probe version;
- exact source commit SHA embedded by CI;
- SHA-256 of the installed Probe APK;
- start/end battery percentage;
- start/end battery temperature and voltage when Android exposes them;
- start/end Android thermal status when supported.

A `clean=true` result means that the packet run sent at least one probe and observed no missing, duplicate, invalid, disconnect, or error evidence. It is a transport-test result, not a general device-quality certification.

## Evidence capture behavior

Probe 0.4.0 keeps measurement ownership separate from device evidence collection.

- `ProbeSoakStats` still owns packet/RTT accounting.
- `ProbeDeviceEvidence` samples battery and thermal state only at soak start and finish.
- The installed APK is SHA-256 hashed once in a background thread after app launch and the cached digest is reused by the timed run.
- The soak start button is fenced until that background APK digest is ready, so the timed workload does not begin with synchronous APK file I/O on the UI thread.
- The source SHA is injected into `BuildConfig.SOURCE_SHA` by CI. A local build without `SOLARNET_SOURCE_SHA`/`GITHUB_SHA` records `unknown` instead of inventing an identity.

Battery or thermal fields may use `-1`/`null` when the platform does not expose a value. Missing OEM telemetry must remain missing evidence; do not infer it.

## Build identity

Use the Probe APK produced by the CI run for the exact source commit being tested. CI uploads `probe-app-debug.apk.sha256`, while the running Probe hashes its installed base APK independently.

For the normal single-APK sideload path, preserve both digests and compare them. A mismatch means the installed bytes being tested are not proven identical to the CI artifact and the run should not be promoted as artifact-matched evidence until explained.

The Probe is an internal debug artifact, not a signed production release APK.

## Two-phone Bluetooth Classic procedure

1. Install the same Probe APK build on both Android phones.
2. Pair the phones in Android Bluetooth settings before launching the RFCOMM test.
3. Open the Probe on both phones and press **Request radio permissions** when Android requires it.
4. Wait for the event log to report that the evidence APK SHA-256 is ready.
5. On phone A, press **BT HOST: start RFCOMM server**.
6. On phone B, press **BT CLIENT: refresh paired devices**, then connect to phone A.
7. Confirm both screens show a connected state. **Broadcast manual PING** may be used as a quick preflight, but it is not soak evidence.
8. On phone B only, press **CLIENT: start 10-minute soak**.
9. Leave the connection active until the run reports `completionReason=completed`. Do not treat a manually stopped run as a completed soak.
10. Capture the machine-readable result from phone B's logcat.

The host handles framed soak packets without adding one UI-log row for every PONG operation. The client also suppresses successful per-ping operation log rows; this avoids making the UI log itself a significant part of the 1 Hz measurement workload.

## Two-phone Nearby procedure

1. Install the same Probe APK build on both phones and grant the radio permissions requested for each Android version.
2. Wait for the evidence APK SHA-256 ready log on both phones.
3. On phone A, press **Nearby HOST: advertise**.
4. On phone B, press **Nearby CLIENT: discover** and select phone A when it appears.
5. Compare the displayed authentication digits on both phones. Accept only when the digits match; reject a mismatch.
6. After the connection succeeds, Probe stops host advertising and client discovery so continued discovery work is not mixed into the soak workload.
7. On phone B only, press **CLIENT: start 10-minute soak**.
8. Leave the connection active until `completionReason=completed` and capture the client result.

Nearby uses the same `ProbeSoakController` and `ProbeSoakStats` owner as Bluetooth Classic. Transport-specific code supplies only the byte send/broadcast link and connection lifecycle.

## Capturing the result

With ADB attached to the client phone:

```bash
adb logcat -s SolarNetProbe:I
```

At completion, the app writes a line beginning with:

```text
SOLARNET_PROBE_RESULT
```

The remainder is JSON. Example shape:

```json
{
  "transport": "bluetooth-classic",
  "probeVersion": "0.4.0",
  "deviceModel": "manufacturer model",
  "sdkInt": 36,
  "androidRelease": "16",
  "evidence": {
    "start": {
      "capturedAtEpochMs": 0,
      "batteryPercent": 90,
      "batteryTemperatureC": 31.2,
      "batteryVoltageMv": 4200,
      "thermalStatus": 0,
      "sourceSha": "<commit-sha>",
      "apkSha256": "<installed-apk-sha256>"
    },
    "end": {
      "capturedAtEpochMs": 0,
      "batteryPercent": 88,
      "batteryTemperatureC": 33.0,
      "batteryVoltageMv": 4150,
      "thermalStatus": 0,
      "sourceSha": "<commit-sha>",
      "apkSha256": "<installed-apk-sha256>"
    }
  },
  "result": {
    "schema": 1,
    "runId": "...",
    "completionReason": "completed",
    "clean": true,
    "elapsedSeconds": 600.0,
    "sent": 600,
    "pong": 600,
    "missing": 0,
    "lossPercent": 0.0,
    "duplicate": 0,
    "invalid": 0,
    "disconnects": 0,
    "errors": 0,
    "minRttMs": 0.0,
    "avgRttMs": 0.0,
    "maxRttMs": 0.0
  }
}
```

For Nearby, the same record uses `"transport": "nearby-connections"`.

Do not require exactly 600 sends as an invariant. Android scheduling and device conditions can shift tick timing. A completed ten-minute run should be approximately that size, while the recorded counters and elapsed duration are the evidence.

## Functional acceptance

For the first physical baseline, record rather than invent a latency SLA. Recommended minimum evidence is:

- `completionReason=completed`;
- no disconnects or bridge/send errors;
- zero missing, duplicate, and invalid responses preferred;
- min/average/max RTT preserved exactly as measured;
- embedded source SHA matches the intended tested commit;
- runtime installed-APK SHA matches the preserved CI artifact checksum for the single-APK sideload path;
- start/end battery and thermal fields preserved exactly as reported.

A non-clean run is useful evidence. Do not rerun until a clean result appears and discard the failure; retain the failing record and classify the observed failure before changing code.

## Thermal and power evidence

The automatic fields improve provenance but do **not** by themselves prove `THERMAL_VERIFIED` or `POWER_VERIFIED`.

Battery percentage/temperature and `PowerManager.getCurrentThermalStatus()` provide bounded start/end observations. For a representative thermal/power claim, also preserve the device model, Android build, workload, physical conditions, and when practical richer ADB/system evidence:

```bash
adb shell dumpsys thermalservice
adb shell dumpsys batterystats --reset
# run the representative soak
adb shell dumpsys batterystats com.hoonex.solarnet.probe
```

Where supported, also inspect CPU/memory during the run. OEM telemetry differs, so missing thermal fields must be reported as unavailable rather than inferred.

## Evidence state before a real run

After CI builds this harness successfully, the correct status is:

```text
SOURCE_VERIFIED
COMPILE_GREEN
TEST_GREEN          # ProbeSoakStats + evidence-envelope JVM smoke
ARTIFACT_VERIFIED   # Probe APK + checksum produced in CI
DEVICE_RUNTIME_UNVERIFIED
PERF_UNVERIFIED
THERMAL_UNVERIFIED
POWER_UNVERIFIED
```

A later physical test may upgrade only the evidence classes actually measured.

## Scope

Probe 0.4.0 gives Bluetooth Classic and Nearby the same timed packet/RTT owner plus an automatic evidence envelope for build identity and bounded device start/end telemetry. It still does not provide physical evidence by itself. Real multi-phone radio, process-kill/relaunch, performance, thermal, and power behavior remain pending until measured on devices.
