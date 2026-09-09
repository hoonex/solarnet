# Probe evidence bundle

Probe 0.4.0 adds a machine-readable provenance and bounded device-telemetry envelope around every Bluetooth Classic or Nearby soak result.

This is a validation-tooling feature. It does not change SolarNet's 0.17.0 engine protocol or package API.

## Ownership

The evidence path is intentionally split:

- `ProbeSoakStats` owns packet/RTT accounting;
- `ProbeEvidenceSnapshot` owns a single start/end evidence sample;
- `ProbeDeviceEvidence` reads Android battery/thermal state and computes the installed APK digest;
- `ProbeEvidenceEnvelope` serializes build/device evidence beside, not inside, the packet result object;
- `MainActivity` only coordinates capture at soak start/finish.

The installed APK SHA-256 is calculated once in a background thread after app startup and then cached. A timed soak cannot start until that hash is ready, preventing APK file I/O from becoming part of the measurement start path.

## Recorded fields

Each final `SOLARNET_PROBE_RESULT` includes:

- transport name;
- Probe version;
- device model;
- Android SDK and release;
- start/end capture time;
- start/end battery percent;
- start/end battery temperature when available;
- start/end battery voltage when available;
- start/end Android thermal status when available;
- exact source SHA injected into the Probe build;
- SHA-256 of the installed APK;
- the unchanged `ProbeSoakStats` result object.

Unknown platform values remain `-1`, `null`, or `unknown` as defined by the field. They are never filled from assumptions.

## Source identity

CI passes the immutable tested source identity into the Probe as `BuildConfig.SOURCE_SHA`.

- Pull-request CI uses the PR head SHA.
- `main` push CI uses the pushed merge SHA.
- a local build without `SOLARNET_SOURCE_SHA` or `GITHUB_SHA` records `unknown`.

The runtime-installed APK digest is independent of this source label. Preserve the CI `probe-app-debug.apk.sha256` file and compare it with the runtime digest for the normal single-APK sideload flow.

## Thermal and power boundary

The start/end fields are useful evidence, but two samples do not constitute a thermal or battery-energy benchmark. They can support statements such as "battery temperature increased from X to Y during this exact run" when a real device reports those values. They do not justify a general `THERMAL_VERIFIED` or `POWER_VERIFIED` claim without an appropriate device workload and richer telemetry.

## CI proof

CI verifies:

1. deterministic JSON serialization for the evidence snapshot/envelope;
2. Android compile of the runtime collector;
3. Probe 0.4.0 APK generation;
4. embedded source SHA in generated `BuildConfig`;
5. API 24 minimum and required radio permissions in the packaged APK;
6. final APK checksum and artifact upload.

CI still cannot prove real radio, OEM telemetry, runtime permission UX, performance, thermal behavior, or power use. Those remain physical-device evidence classes.
