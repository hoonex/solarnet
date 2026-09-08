# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine now separates physical connection, room/lobby lifecycle, authoritative turns, and deterministic game-state recovery.

## Current milestone — 0.4

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit human authentication-code confirmation before Nearby acceptance;
- Nearby endpoint-ID -> SolarNet peer-ID handshake;
- deterministic `ISolarGameStateMachine` and game-rule rejection;
- SHA-256 state digests, action-journal replay, snapshot fallback;
- room join admission with protocol + game compatibility checks;
- authoritative player roster, stable slots, Ready state, and host Start;
- separate `roomId` / `gameSessionId` multiplexing over the same live transport;
- reconnect-to-same-host or close-on-host-loss policy;
- .NET protocol/E2E CI plus real Android/Play Services library compile CI.

## Repository layout

```text
Packages/com.hoonex.solarnet/
  Runtime/Protocol/                 wire framing + engine version
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   Nearby transport + room bridge/discovery metadata
  Runtime/Android/                  Unity Android adapter
  Runtime/Room/                     join / roster / ready / start lifecycle
  Runtime/State/                    reducer / digest / journal / snapshots
  Runtime/Turns/                    authoritative turn state
  Runtime/Session/                  game session orchestration
  Plugins/Android/                  Google Nearby .androidlib bridge
src/SolarNet.Core/                  .NET build wrapper
tests/                              multiplayer, state-integrity, and room smoke tests
android-smoke/                      real Android/Play Services compile harness
docs/                               architecture and integration contracts
```

## Unity flow

1. Start a transport (`NearbyTransport` on Android).
2. Attach `SolarRoomSession` and optionally `SolarNearbyRoomBridge`.
3. Join, confirm Ready, and wait for `GameStarted`.
4. Create `SolarTurnSession` using `SolarGameStartInfo.GameSessionId` and the same transport.
5. Attach `ISolarGameStateMachine` when deterministic validation/resync is required.

Read `docs/nearby-android.md`, `docs/room-lifecycle.md`, and `docs/state-integrity.md` for the contracts.

## Roadmap

1. Core transport/turn protocol — done.
2. Android Google Nearby transport — implemented; physical-device verification pending.
3. Deterministic state integrity/recovery — done in protocol tests.
4. Room/lobby lifecycle — core done; true host migration intentionally deferred.
5. **Next: build the actual Unity 2D sample/game shell and room UI, then run it on two Android phones.**
6. Add Bluetooth/LAN transports where they provide a useful alternative to Nearby.
