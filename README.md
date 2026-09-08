# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, deterministic game-state recovery, replication proof, and host-migration safety.

## Current milestone — 0.16

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
- designated-replica durability fencing with `DurableNextTurnIndex`, `DurabilityPending`, and `DurabilityAdvanced`;
- pending-turn rejection with `ReplicationPending`, preventing dependent authoritative turns from building on an unreplicated predecessor;
- ACK-loss recovery through same-turn snapshot resync without holding the host gate;
- Grid Duel initial and promoted authorities automatically designate the only other player as their durability replica;
- bounded authoritative digest evidence, duplicate-ACK idempotency, and forged-digest rejection;
- Unity Diagnostics and playable Grid Duel samples;
- deterministic 240-turn chaos soak plus recovery, resume, replication, durability, authority-promotion, migration, and Grid Duel migration CI gates;
- Nearby/Bluetooth Android libraries and transport Probe APK built in CI.

## Replication, durability, and host migration

The migration path is intentionally evidence-driven:

1. authoritative turns carry canonical state digests;
2. a client applies the turn through its reducer and verifies the resulting digest;
3. only then does it return `ReplicationAck(nextTurnIndex, stateHash)`;
4. the host validates that acknowledgement against authoritative state and advances that peer's replication frontier;
5. when a designated-replica barrier is enabled, the host will not accept another turn until that replica has proven the current state;
6. synchronized peers can capture `SolarAuthorityCheckpoint` and derive a deterministic successor/new authority epoch;
7. only the elected successor promotes itself and rebuilds the room/transport topology.

A completed `SendAsync` is **not** remote durability evidence. The ACK is the explicit proof that the remote state machine accepted and reproduced the authoritative state. Snapshot recovery uses the same rule: ACK occurs only after snapshot digest verification, restore, and canonical rehash.

SolarNet 0.16 separates the host's local authoritative frontier from its designated-replica durability frontier. `KnownNextTurnIndex` may be one turn ahead while replication proof is pending; `DurableNextTurnIndex` advances only after the required replica's verified ACK. `ActionCommitted` remains the existing local authoritative-commit signal, while `DurabilityAdvanced` is the explicit replication-safe signal. Host `SubmitActionAsync` waits for that proof when the barrier is enabled.

While durability is pending, another action is rejected with `ReplicationPending` without advancing the coordinator or mutating the reducer. The host gate is not held during the proof wait, so an ACK that was lost can be recovered by `ResyncRequest -> Snapshot -> verified ReplicationAck`. SolarNet does not silently disable the fence on timeout; loss of the required replica intentionally sacrifices liveness rather than claiming false migration safety.

For two-player Grid Duel, the opponent is both the only non-host player and the deterministic successor, so it is automatically the designated replica. After migration, the former host becomes the promoted authority's designated replica. Larger games must align the designated replica with their own successor/quorum policy; this is not a general consensus protocol.

Transient `IsConnected` values do not affect successor choice or authority epoch ID. Grid Duel also refuses automatic promotion if its canonical state no longer matches the last authoritative digest observed by the session.

Nearby can rediscover the migrated room and request the elected successor endpoint. Bluetooth Classic retains the device address associated with the previously authenticated SolarNet peer, allowing automatic reconnect to the elected successor when that address is known. If no authenticated address exists, paired-device selection remains explicit; SolarNet does not guess peer identity.

See `docs/replication-ack.md`, `docs/durability-barrier.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md`.

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
  Runtime/Session/                  game orchestration + replication/durability + authority promotion
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone transport/room diagnostics
  Samples~/GridDuel/                local + two-phone playable 2D sample + migration workflow
src/SolarNet.Core/                  .NET build wrapper
tests/                              protocol, recovery, replication, durability, chaos, migration, room, game, Android gates
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
13. Designated-replica durable-turn fence with ACK-loss snapshot recovery — done and wired into Grid Duel.
14. Next: persist migrated authority/durability epochs across process death, then perform physical multi-phone radio soak and release hardening.

Read `docs/replication-ack.md`, `docs/durability-barrier.md`, `docs/chaos-soak.md`, `docs/process-resume.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, and `docs/gridduel-host-migration.md` for recovery contracts and limits.
