# Nearby vs Bluetooth physical soak comparison

SolarNet Probe 0.3.0 runs the same framed soak protocol and the same `ProbeSoakStats` implementation over two Android transports:

- secure Bluetooth Classic RFCOMM;
- Google Nearby Connections using the P2P STAR strategy.

The purpose is not to declare one transport universally faster. The purpose is to collect comparable packet-loss, disconnect/error, and RTT evidence under the same phones, source build, timing, and workload.

CI can prove the common controller compiles, deterministic metric accounting passes, and the Probe APK is produced. It cannot prove physical radio reliability or latency. Those claims require real phones.

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
2. Open Probe 0.3.0 on both phones and press **Request radio permissions** as needed.
3. On phone A, press **BT HOST: start RFCOMM server**.
4. On phone B, press **BT CLIENT: refresh paired devices** and connect to phone A.
5. Confirm both phones report a Bluetooth connection.
6. On phone B only, press **CLIENT: start 10-minute soak**.
7. Leave the run active until `completionReason=completed`.
8. Capture the client `SOLARNET_PROBE_RESULT` record.

Bluetooth uses the existing paired-device RFCOMM path. Pairing state is therefore part of its setup evidence.

## Nearby procedure

1. Install the same Probe 0.3.0 APK on both phones.
2. Open Probe and grant the requested Bluetooth/location/Nearby Wi-Fi permissions applicable to that Android version.
3. On phone A, press **Nearby HOST: advertise**.
4. On phone B, press **Nearby CLIENT: discover**.
5. When phone A appears, press its **Nearby connect** button.
6. Both devices must show the same Nearby authentication digits. Accept only by pressing **Digits match** after visually confirming the codes match. Reject a mismatch.
7. After connection, Probe stops advertising on the host and discovery on the client before the soak workload. This prevents continued discovery activity from becoming a transport-specific measurement load.
8. On phone B only, press **CLIENT: start 10-minute soak**.
9. Leave the run active until `completionReason=completed`.
10. Capture the client `SOLARNET_PROBE_RESULT` record.

Do not automate away the authentication-digit check. An unverified Nearby connection is not equivalent evidence to the intended authenticated flow.

## Comparison priority

Interpret evidence in this order:

1. completion reason;
2. disconnects and send/bridge errors;
3. missing/loss, duplicate, and invalid packets;
4. RTT distribution summary;
5. thermal/power observations, if separately measured.

A lower RTT does not compensate for repeated disconnects or packet loss in a local turn-based game. Reliability comes before small latency differences for SolarNet's target workload.

The Probe currently records minimum, average, and maximum RTT rather than a full percentile distribution. Preserve the raw run summary as produced; do not invent p50/p95/p99 values from min/avg/max.

## Evidence record

For every physical run preserve at minimum:

```text
source commit SHA
Probe APK SHA-256
Probe version
transport
host device model + Android version
client device model + Android version
physical placement notes
start/end time
SOLARNET_PROBE_RESULT JSON
unexpected disconnect/error notes
```

If thermal or power is being evaluated, also preserve the starting/end battery and thermal evidence described in `physical-radio-soak.md`.

## Pass/fail usage

For an initial baseline, do not invent a universal latency SLA before observing devices. A useful first target is:

- `completionReason=completed`;
- no disconnects;
- no bridge/send errors;
- zero missing, duplicate, and invalid responses preferred;
- RTT min/avg/max preserved exactly as measured.

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

Running one transport physically upgrades evidence only for that tested transport, device pair, build, and workload. It does not automatically validate the other transport or all Android devices.
