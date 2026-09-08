# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, and deterministic game-state recovery.

## Current milestone — 0.12

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit Bluetooth-only Android transport using secure Bluetooth Classic RFCOMM;
- deterministic `ISolarGameStateMachine`, SHA-256 state digests, journal replay, snapshot fallback, reconnect, and client process resume;
- deterministic authority checkpoints and promoted-host bootstrap into a fenced session epoch;
- room/checkpoint consistency validation for migration;
- safety-first deterministic successor selection using stable room slot order;
- deterministic SHA-256-derived authority epoch IDs independent of transient link observations;
- Unity Diagnostics and playable Grid Duel samples;
- deterministic 240-turn chaos soak plus recovery, resume, authority-promotion, and host-migration-planning CI gates;
- Nearby/Bluetooth Android libraries and transport Probe APK built in CI.

## Host migration status

The deterministic pieces are now separated cleanly:

1. synchronized peer captures `SolarAuthorityCheckpoint`;
2. every survivor calls `SolarHostMigrationPlanner.Create(roomSnapshot, checkpoint)`;
3. the lowest non-host stable room slot is the only elected successor;
4. all synchronized survivors derive the same new authority session ID;
5. only that successor calls `SolarAuthorityPromotion.CreatePromotedHost(...)`.

Transient `IsConnected` values do not affect successor choice or epoch ID. This intentionally favors split-brain safety over automatic fallback liveness.

Automatic Nearby/Bluetooth topology rebuilding is still the next step. See `docs/authority-checkpoint.md` and `docs/host-migration-planner.md`.

## Repository layout

```text
Packages/com.hoonex.solarnet/
  Runtime/Protocol/                 wire framing + engine version
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   Nearby transport + room discovery metadata
  Runtime/BluetoothClassic/         Bluetooth-only RFCOMM transport
  Runtime/Android/                  Unity Android C# adapters + permission helpers
  Runtime/Room/                     room lifecycle + migration planning
  Runtime/State/                    reducer / digest / journal / snapshots
  Runtime/Turns/                    authoritative turn state + checkpoint restore
  Runtime/Session/                  game orchestration + authority checkpoint/promotion
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone transport/room diagnostics
  Samples~/GridDuel/                local + two-phone playable 2D sample
src/SolarNet.Core/                  .NET build wrapper
tests/                              protocol, recovery, chaos, resume, authority/migration, room, game, Android gates
android-smoke/                      Android library + probe APK build harness
docs/                               architecture and integration contracts
```

## Roadmap

1. Core transport/turn protocol — done.
2. Android Nearby + Bluetooth transports — implemented; physical-device verification pending.
3. Deterministic state integrity/recovery — done and fault-injection tested.
4. Room/lobby lifecycle — done.
5. Grid Duel local + two-phone sample — implemented.
6. Active-match reconnect + client process-death resume — done in CI models.
7. Deterministic 240-turn chaos soak — done.
8. Authority checkpoint + promoted-host bootstrap — done.
9. Deterministic room-level successor/epoch planning — done.
10. Next: transport role switching + migrated-room republish, then physical radio soak and release hardening.

Read `docs/chaos-soak.md`, `docs/process-resume.md`, `docs/authority-checkpoint.md`, and `docs/host-migration-planner.md` for recovery contracts and limits.
