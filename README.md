# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, deterministic game-state recovery, and host-migration evidence.

## Current milestone — 0.15

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit Bluetooth-only Android transport using secure Bluetooth Classic RFCOMM;
- deterministic `ISolarGameStateMachine`, SHA-256 state digests, journal replay, snapshot fallback, reconnect, and client process resume;
- deterministic authority checkpoints and promoted-host bootstrap into a fenced session epoch;
- safety-first deterministic successor selection using stable room slot order;
- migrated-room authority bootstrap plus Nearby/Bluetooth role switching;
- Grid Duel synchronized link-loss migration with authority epoch rotation, room rejoin, and authoritative resync;
- state-verified `ReplicationAck` packets after successful committed-action or snapshot application;
- host-side per-peer replication frontiers through `GetReplicationFrontier` / `IsReplicatedThrough`;
- bounded authoritative digest evidence, duplicate-ACK idempotency, and forged-digest rejection;
- Unity Diagnostics and playable Grid Duel samples;
- deterministic 240-turn chaos soak plus recovery, resume, authority-promotion, migration, Grid Duel migration, and replication-ACK CI gates;
- Nearby/Bluetooth Android libraries and transport Probe APK built in CI.

## Replication and host migration

The migration path is intentionally evidence-driven:

1. authoritative turns carry canonical state digests;
2. a client applies the turn through its reducer and verifies the resulting digest;
3. only then does it return `ReplicationAck(nextTurnIndex, stateHash)`;
4. the host validates that acknowledgement against authoritative state and advances that peer's replication frontier;
5. synchronized peers can capture `SolarAuthorityCheckpoint` and derive a deterministic successor/new authority epoch;
6. only the elected successor promotes itself and rebuilds the room/transport topology.

A completed `SendAsync` is **not** remote durability evidence. The acknowledgement is the first explicit proof that the remote state machine accepted and reproduced the authoritative state. Snapshot recovery uses the same rule: ACK occurs only after snapshot digest verification, restore, and canonical rehash.

SolarNet 0.15 still does not claim transactional durability for a host-local turn. A host can mutate its own state and fail before the elected successor observes that commit. The successor cannot discover an unseen newer commit merely from an ACK ledger. Therefore 0.15 establishes the replication-proof primitive; the next durability milestone must gate when a commit is declared migration-safe/user-visible on an acknowledgement from the designated replica and define timeout/provisional-state semantics explicitly.

Transient `IsConnected` values do not affect successor choice or authority epoch ID. Grid Duel also refuses automatic promotion if its canonical state no longer matches the last authoritative digest observed by the session.

Nearby can rediscover the migrated room and request the elected successor endpoint. Bluetooth Classic retains the device address associated with the previously authenticated SolarNet peer, allowing automatic reconnect to the elected successor when that address is known. If no authenticated address exists, paired-device selection remains explicit; SolarNet does not guess peer identity.

See `docs/replication-ack.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md`.

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
  Runtime/Session/                  game orchestration + replication ACKs + authority promotion
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone transport/room diagnostics
  Samples~/GridDuel/                local + two-phone playable 2D sample + migration workflow
src/SolarNet.Core/                  .NET build wrapper
tests/                              protocol, recovery, replication, chaos, migration, room, game, Android gates
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
12. State-verified replication acknowledgements and per-peer proof frontiers — done.
13. Next: build a designated-replica durable-commit barrier, persist migrated authority epochs across process death, then perform physical multi-phone radio soak and release hardening.

Read `docs/replication-ack.md`, `docs/chaos-soak.md`, `docs/process-resume.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md` for recovery contracts and limits.
