# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, and deterministic game-state recovery.

## Current milestone — 0.6

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit Bluetooth-only Android transport using secure Bluetooth Classic RFCOMM;
- logical peer identity handshakes independent from Nearby endpoint IDs or Bluetooth connection/MAC identity;
- explicit human authentication-code confirmation before Nearby acceptance;
- paired-device Bluetooth path that avoids scan permission;
- deterministic `ISolarGameStateMachine` and game-rule rejection;
- SHA-256 state digests, action-journal replay, snapshot fallback;
- room join admission with protocol + game compatibility checks;
- authoritative player roster, stable slots, Ready state, and host Start;
- separate `roomId` / `gameSessionId` multiplexing over the same live transport;
- reconnect-to-same-host or close-on-host-loss policy;
- Unity Package Manager Diagnostics sample for two-phone testing;
- CI for portable protocol/E2E, `UNITY_ANDROID` C# bridge compilation, and both native Android transport libraries.

## Repository layout

```text
Packages/com.hoonex.solarnet/
  Runtime/Protocol/                 wire framing + engine version
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   Nearby transport + room discovery metadata
  Runtime/BluetoothClassic/         Bluetooth-only RFCOMM transport
  Runtime/Android/                  Unity Android C# adapters + permission helpers
  Runtime/Room/                     join / roster / ready / start lifecycle
  Runtime/State/                    reducer / digest / journal / snapshots
  Runtime/Turns/                    authoritative turn state
  Runtime/Session/                  game session orchestration
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone Unity diagnostics harness
src/SolarNet.Core/                  .NET build wrapper
tests/                              protocol, recovery, room, Bluetooth, Android-C# gates
android-smoke/                      real Android library compile harness
docs/                               architecture and integration contracts
```

## Unity flow

1. Choose a transport: `NearbyTransport` or `BluetoothClassicTransport` on Android.
2. Attach `SolarRoomSession`.
3. Connect peers, join the room, confirm Ready, and wait for `GameStarted`.
4. Create `SolarTurnSession` using `SolarGameStartInfo.GameSessionId` and the same transport.
5. Attach `ISolarGameStateMachine` when deterministic validation/resync is required.

For the fastest device check, import **SolarNet Diagnostics** from the package Samples section, add `SolarNetDiagnostics` to an empty GameObject, and build the same project to two Android phones.

Read `docs/nearby-android.md`, `docs/bluetooth-classic.md`, `docs/room-lifecycle.md`, `docs/state-integrity.md`, and `docs/unity-diagnostics.md` for the contracts.

## Roadmap

1. Core transport/turn protocol — done.
2. Android Nearby transport — implemented; physical-device verification pending.
3. Deterministic state integrity/recovery — done in protocol tests.
4. Room/lobby lifecycle — done; true host migration intentionally deferred.
5. Android Bluetooth Classic RFCOMM transport — implemented; physical-device verification pending.
6. Unity two-phone diagnostics harness — implemented; physical-device execution is the next evidence step.
7. **Next: build the actual 2D turn-based game shell on top of SolarNet, after transport behavior is confirmed on two phones.**
