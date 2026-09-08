# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, and deterministic game-state recovery.

## Current milestone — 0.14

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit Bluetooth-only Android transport using secure Bluetooth Classic RFCOMM;
- deterministic `ISolarGameStateMachine`, SHA-256 state digests, journal replay, snapshot fallback, reconnect, and client process resume;
- deterministic authority checkpoints and promoted-host bootstrap into a fenced session epoch;
- room/checkpoint consistency validation for migration;
- safety-first deterministic successor selection using stable room slot order;
- deterministic SHA-256-derived authority epoch IDs independent of transient link observations;
- migrated-room authority bootstrap that preserves original player slots while moving `HostPeerId` to the elected successor;
- Nearby topology rebuild from discoverer to advertiser on the successor and migrated-room rediscovery on survivors;
- Bluetooth Classic client/server role rebuilding with authenticated last-link device-address recovery;
- Grid Duel synchronized link-loss migration: checkpoint safety gate, authority epoch rotation, topology rebuild, room rejoin, and authoritative resync;
- Unity Diagnostics and playable Grid Duel samples;
- deterministic 240-turn chaos soak plus recovery, resume, authority-promotion, migration-planning, migration-orchestration, and Grid Duel migration CI gates;
- Nearby/Bluetooth Android libraries and transport Probe APK built in CI.

## Host migration status

The deterministic, topology-rebuild, and sample integration pieces are separated cleanly:

1. synchronized peer captures `SolarAuthorityCheckpoint`;
2. every survivor calls `SolarHostMigrationPlanner.Create(roomSnapshot, checkpoint)`;
3. the lowest non-host stable room slot is the only elected successor;
4. synchronized survivors derive the same new authority session ID;
5. only that successor calls `SolarAuthorityPromotion.CreatePromotedHost(...)`;
6. `SolarRoomMigration.CreateSession(...)` rebuilds room authority around the successor without rewriting player slots;
7. Nearby/Bluetooth migration switches stop the old topology before opening the successor/server topology;
8. Grid Duel now invokes this path automatically when a synchronized two-player match loses its authenticated peer link.

Transient `IsConnected` values do not affect successor choice or epoch ID. This intentionally favors split-brain safety over fallback liveness. Grid Duel also refuses automatic promotion if its local canonical state bytes no longer match the last authoritative digest observed by the current session.

Nearby can rediscover the migrated room and request the elected successor endpoint. Bluetooth Classic retains the device address associated with the previously authenticated SolarNet peer, allowing automatic reconnect to the elected successor when that address is known. If no authenticated address exists, paired-device selection remains explicit; SolarNet does not guess peer identity.

A two-player link-loss migration has an unavoidable evidence boundary without a replication acknowledgement/quorum protocol: the elected successor can only preserve the latest authoritative commit it actually observed. A final host-local commit that never reached the successor before the link failed can be lost. SolarNet 0.14 therefore describes this as recovery to the **latest replicated authoritative state**, not guaranteed preservation of host-only in-flight state.

See `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md`.

## Repository layout

```text
Packages/com.hoonex.solarnet/
  Runtime/Protocol/                 wire framing + engine version
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   Nearby transport + room discovery/migration switching
  Runtime/BluetoothClassic/         Bluetooth-only RFCOMM transport + migration switching
  Runtime/Android/                  Unity Android C# adapters + permission helpers
  Runtime/Room/                     room lifecycle + migration planning/bootstrap
  Runtime/State/                    reducer / digest / journal / snapshots
  Runtime/Turns/                    authoritative turn state + checkpoint restore
  Runtime/Session/                  game orchestration + authority checkpoint/promotion
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone transport/room diagnostics
  Samples~/GridDuel/                local + two-phone playable 2D sample + migration workflow
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
10. Migrated-room bootstrap + Nearby/Bluetooth transport role switching — done in deterministic CI models.
11. Grid Duel synchronized link-loss authority migration — wired into the Unity sample and deterministic smoke tests.
12. Next: persist migrated authority epochs across process death, add a replicated-commit durability barrier, then perform physical multi-phone radio soak and release hardening.

Read `docs/chaos-soak.md`, `docs/process-resume.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md` for recovery contracts and limits.
