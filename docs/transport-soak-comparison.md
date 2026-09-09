# Nearby vs Bluetooth physical soak comparison

SolarNet Probe 0.4.0 runs the same framed soak protocol and the same `ProbeSoakStats` implementation over two Android transports:

- secure Bluetooth Classic RFCOMM;
- Google Nearby Connections using the P2P STAR strategy.

The purpose is not to declare one transport universally faster. The purpose is to collect comparable packet-loss, disconnect/error, RTT, and bounded start/end device evidence under the same phones, source build, timing, and workload.

CI can prove the common controller compiles, deterministic metric/evidence serialization passes, and the Probe APK is produced. It cannot prove physical radio reliability, latency, thermal behavior, or power usage. Those claims require real phones.

## Shared measurement contract

Both transports use the same controller and wire format:

```text
SNP1|PING|<runId>|<sequence>|<clientElapsedRealtimeNanos>
SNP1|PONG|<runId>|<sequence>|<clientElapsedRealtimeNanos>
```

The client uses Android's monotonic `SystemClock.elapsedRealtimeNanos()` for elapsed time and RTT. The host only echoes the client timestamp, so the result never depends on synchronized device clocks.

Default workload:

- one PING per second;
- ten-minute duration;
- one client and one echo host;
- identical counters and `clean` semantics for both transports.

The final log record begins with `SOLARNET_PROBE_RESULT` and identifies the transport as either `bluetooth-classic` or `nearby-connections`.

Probe 0.4.0 also records the same evidence envelope for either transport: source SHA, installed APK SHA-256, Android version, start/end battery percentage, battery temperature/voltage when exposed, and Android thermal status when supported. This evidence is sampled only at the run boundaries and does not change the 1 Hz packet workload.

## Fair-comparison controls

Use the same conditions for both runs:

1. same two phones, without changing which phone is host/client during one comparison pair;
2. same Probe APK produced from the same source commit;
3. same APK SHA-256 recorded with both results;
4. same approximate phone placement, orientation, distance, and obstacles;
5. same screen/power state policy;
6. no deliberate background download, hotspot change, or radio stress added to only one run;
7. preserve every run result, including failures;
8. do not keep retrying until a clean run appears and discard earlier failures.

For a stronger comparison, perform at least two pairs and reverse transport order on the second pair:

```text
Pair A: Bluetooth -> Nearby
Pair B: Nearby -> Bluetooth
```

This reduces warm-up, temperature, battery, and order effects. It still does not make two phones a statistically general device population.

## Bluetooth Classic procedure

1. Pair the two phones in Android Bluetooth settings.
2. Open Probe 0.4.0 on both phones and press **Request radio permissions** as needed.
3. Wait for the evidence APK SHA-256 ready log.
4. On phone A, press **BT HOST: start RFCOMM server**.
5. On phone B, press **BT CLIENT: refresh paired devices** and connect to phone A.
6. Confirm both phones report a Bluetooth connection.
7. On phone B only, press **CLIENT: start 10-minute soak**.
8. Leave the run active until `completionReason=completed`.
9. Capture the client `SOLARNET_PROBE_RESULT` record.

Bluetooth uses the existing paired-device RFCOMM path. Pairing state is therefore part of its setup evidence.

## Nearby procedure

1. Install the same Probe 0.4.0 APK on both phones.
2. Open Probe and grant the requested Bluetooth/location/Nearby Wi-Fi permissions applicable to that Android version.
3. Wait for the evidence APK SHA-256 ready log.
4. On phone A, press **Nearby HOST: advertise**.
5. On phone B, press **Nearby CLIENT: discover**.
6. When phone A appears, press its **Nearby connect** button.
7. Both devices must show the same Nearby authentication digits. Accept only by pressing **Digits match** after visually confirming the codes match. Reject a mismatch.
8. After connection, Probe stops advertising on the host and discovery on the client before the soak workload. This prevents continued discovery activity from becoming a transport-specific measurement load.
9. On phone B only, press **CLIENT: start 10-minute soak**.
10. Leave the run active until `completionReason=completed`.
11. Capture the client `SOLARNET_PROBE_RESULT` record.

Do not automate away the authentication-digit check. An unverified Nearby connection is not equivalent evidence to the intended authenticated flow.

## Comparison priority

Interpret evidence in this order:

1. artifact/source identity match;
2. completion reason;
3. disconnects and send/bridge errors;
4. missing/loss, duplicate, and invalid packets;
5. RTT distribution summary;
6. start/end thermal and battery observations;
7. richer thermal/power evidence, if separately measured.

A lower RTT does not compensate for repeated disconnects or packet loss in a local turn-based game. Reliability comes before small latency differences for SolarNet's target workload.

The Probe currently records minimum, average, and maximum RTT rather than a full percentile distribution. Preserve the raw run summary as produced; do not invent p50/p95/p99 values from min/avg/max.

## Evidence record

The `SOLARNET_PROBE_RESULT` JSON now automatically carries most build/client evidence. For every physical run still preserve at minimum:

```text
SOLARNET_PROBE_RESULT JSON
CI artifact checksum file
host device model + Android version
physical placement notes
unexpected disconnect/error notes
```

Inside the JSON, preserve and compare:

```text
sourceSha
apkSha256
probeVersion
transport
client device model + Android version
start/end capture timestamps
start/end battery percentage/temperature/voltage
start/end thermal status
packet/RTT result object
```

The source SHA and installed APK SHA should match the intended CI build identity for the normal single-APK sideload path. A mismatch is provenance evidence that must be explained before comparing transport results.

If thermal or power is being evaluated beyond the bounded start/end fields, also preserve the richer ADB/system evidence described in `physical-radio-soak.md`.

## Pass/fail usage

For an initial baseline, do not invent a universal latency SLA before observing devices. A useful first target is:

- tested source/artifact identity matches the intended build;
- `completionReason=completed`;
- no disconnects;
- no bridge/send errors;
- zero missing, duplicate, and invalid responses preferred;
- RTT min/avg/max preserved exactly as measured;
- start/end device evidence retained even when the run is not clean.

A non-clean result is evidence, not a reason to hide the run. Diagnose the failure class before changing transport code.

## Evidence boundary

Before real phones are used, the maximum valid claim remains:

```text
SOURCE_VERIFIED
COMPILE_GREEN
TEST_GREEN
ARTIFACT_VERIFIED
DEVICE_RUNTIME_UNVERIFIED
PERF_UNVERIFIED
THERMAL_UNVERIFIED
POWER_UNVERIFIED
```

Running one transport physically upgrades evidence only for that tested transport, device pair, build, and workload. It does not automatically validate the other transport or all Android devices. Automatic battery/thermal fields improve evidence capture but do not upgrade those states until a real run exists.
