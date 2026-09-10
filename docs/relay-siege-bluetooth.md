# Relay Siege Bluetooth Architecture

Relay Siege uses Android Bluetooth Classic RFCOMM for a direct two-phone match with no Internet relay.
The first release deliberately uses **paired devices** rather than in-app discovery/pairing: players pair the two Android phones in system Bluetooth settings, then choose the bonded HOST device inside Solar Arcade.

## Authority model

```text
HOST phone (SUN)                         CLIENT phone (MOON)
----------------                         -------------------
RelaySiegeGame                            no gameplay simulation
100 ms authoritative clock               renders authoritative snapshots
local input -> 2 tick buffer              local input -> PLAY_REQUEST(seq)
        |                                           |
        +---- canonical simulation <--- RFCOMM -----+
        |
        +---- STATE(snapshot, hash, ackSeq) -------> client
```

Only the HOST advances `RelaySiegeGame`. This intentionally avoids two independent Android timers trying to maintain lockstep under OEM scheduling, UI jank, or radio callback delay.

The CLIENT receives a full bounded snapshot at the 10 Hz gameplay rate. A later snapshot replaces an earlier one, so a delayed frame cannot permanently desynchronize the match.

## Input rules

- HOST is always `SUN`; CLIENT is always `MOON`.
- A client packet does not contain a player field, so the remote peer cannot claim the host player role through the normal protocol.
- Client deployments use a positive monotonic sequence number.
- The host accepts only the next contiguous sequence and remembers the last result.
- Duplicate delivery replays the previous result and never schedules the action twice.
- One unresolved action per player is allowed at a time. This prevents a later action from invalidating the hand/Flux assumptions of an earlier buffered action.
- Both HOST and CLIENT deployments use the same two-authoritative-tick input buffer.
- Position, hand membership, Flux, card identity and deployment zone are validated by the host.

## State privacy and integrity

The remote snapshot contains:

- authoritative tick
- CLIENT's own four-card hand and next card
- CLIENT Flux and spent Flux
- both sides' public relay/core HP
- all public battlefield entities and statuses
- winner/end reason

It deliberately does **not** serialize the HOST's hidden hand, next card, or Flux.

Snapshots use a bounded binary codec and SHA-256. Invalid magic/version, truncated frames, unknown cards/enums, impossible HP/positions/entity flags, oversized data, trailing data or a mismatched snapshot hash are rejected before the state is exposed to the UI.

## RFCOMM framing and ordering

`SolarBluetoothClassicBridge` already frames every payload as:

```text
[int32 payloadLength][payload bytes]
```

with a 2 MiB hard maximum.

The bridge now also uses a **per-connection FIFO send queue**. This is required because the bridge's shared cached thread pool could previously start two `sendBytes()` workers in reverse order even though the underlying RFCOMM stream itself is ordered. The queue makes caller order equal write order while still keeping socket I/O off the Android main thread.

## Disconnect and resume

A transport disconnect freezes the HOST logical clock immediately. No tower/unit damage is allowed to continue while the other player is offline.

The host RFCOMM server remains available for a new accepted socket. The client keeps its session ID and can reconnect to the same paired device. Resume requires:

1. the same session ID;
2. the same CLIENT deck;
3. a fresh `START` plus current authoritative `STATE`;
4. replay of the last processed client action result when needed;
5. CLIENT `READY` before the HOST clock resumes.

This covers a live app/socket loss while both app processes still retain the match session. Host process death is **not** recovered by this Android game mode yet.

## CI evidence

Automated tests cover:

- cached-pool send scheduling cannot reorder COMMIT/TICK-style messages;
- pre-close queued writes drain and new writes are rejected;
- protocol/snapshot round trip and hash corruption rejection;
- host-only simulation and perspective-safe snapshot content;
- duplicate client action idempotency;
- fixed input buffer for both roles;
- stale session fencing;
- disconnect freezes the host tick;
- same-session reconnect preserves the match;
- link loss after host receives a request but before its acknowledgement reaches the client.

The normal Android CI also compiles the native Bluetooth library and builds the Solar Arcade APK.

## Physical two-phone acceptance gate

CI is not radio-device evidence. Before marking Relay Siege Bluetooth `DEVICE_RUNTIME_VERIFIED`, run this exact test on two Android phones:

1. Install the same Solar Arcade build on both phones and record device models, Android versions, source commit and APK SHA-256.
2. Pair the phones in Android Bluetooth settings.
3. Phone A: Relay Siege -> Bluetooth 2P -> choose deck -> `방 만들기 · SUN`.
4. Phone B: choose a different deck -> `방 참가하기 · MOON` -> select Phone A from bonded devices.
5. Verify both enter the match and show the same objective HP/tick progression while each local side appears at the bottom of its screen.
6. On both phones, rapidly perform at least 20 valid deployments, including near-simultaneous opposite-side plays. Verify no duplicated deployments, illegal hand reuse, or diverging objective HP.
7. Play at least one full match to its deterministic end condition.
8. Start a second match. After a CLIENT deployment is sent, toggle Bluetooth off/on or otherwise break the connection. Verify HOST tick/HP freeze during the outage.
9. Re-enable Bluetooth and use reconnect. Verify the same match/tick/state returns and the interrupted deployment is either applied exactly once or explicitly rejected—not lost silently or duplicated.
10. Repeat the interruption with the HOST app briefly backgrounded, then with the CLIENT app backgrounded.
11. Record logcat for both devices and check for socket/read/protocol exceptions.
12. For a 10+ minute representative session, record thermal/battery observations before making performance/power claims.

Until those device steps are executed, report the implementation as `COMPILE_GREEN` / `TEST_GREEN` with `DEVICE_RUNTIME_UNVERIFIED`.
