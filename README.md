# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine separates physical transport, room/lobby lifecycle, authoritative turns, deterministic game-state recovery, replication proof, durable authority persistence, and host-migration safety.

## Current milestone — 0.17

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit Bluetooth-only Android transport using secure Bluetooth Classic RFCOMM;
- deterministic `ISolarGameStateMachine`, SHA-256 state digests, journal replay, snapshot fallback, reconnect, and client process resume;
- deterministic authority checkpoints and promoted-host bootstrap into a fenced session epoch;
- safety-first deterministic successor selection using stable room slot order;
- migrated-room authority bootstrap plus Nearby/Bluetooth role switching;
- Grid Duel synchronized link-loss migration with authority epoch rotation, room rejoin, and authoritative resync;
- state-verified `ReplicationAck` packets and host-side per-peer replication frontiers;
- designated-replica durability fencing with `DurableNextTurnIndex`, `DurabilityPending`, `DurabilityAdvanced`, and `ReplicationPending`;
- ACK-loss recovery through same-turn snapshot resync without holding the host gate;
- durable `SolarAuthorityEpochRecord` capture containing room authority, game epoch, stable roster order, canonical state, turn metadata, and designated replica;
- deterministic `SNAE` authority-record encoding with SHA-256 corruption detection and bounded decode limits;
- same-authority process restart through `SolarAuthorityEpochResume`;
- post-restart `DurabilityRevalidationPending` fence: no local or remote turn is accepted until the designated replica re-verifies the persisted frontier/hash;
- Grid Duel `PlayerPrefs` integration with saved-authority Resume/Discard UI, orderly stale-record cleanup, and transport-mode persistence;
- Unity Diagnostics and playable Grid Duel samples;
- deterministic 240-turn chaos soak plus recovery, resume, replication, durability, authority-epoch persistence, promotion, migration, and Grid Duel CI gates;
- Nearby/Bluetooth Android libraries and transport Probe APK built in CI;
- Probe 0.3.0 physical-soak harness using one shared monotonic RTT/loss/duplicate/invalid/disconnect/error metric owner for Bluetooth Classic and Nearby, with machine-readable result output and deterministic JVM metric tests.

## Replication, durability, persistence, and migration

The authority path is evidence-driven:

1. authoritative turns carry canonical state digests;
2. a client applies the turn and verifies the resulting digest;
3. only then does it return `ReplicationAck(nextTurnIndex, stateHash)`;
4. the host validates that ACK and advances the peer's replication frontier;
5. with a designated-replica barrier, the host refuses dependent turns until the required replica proves the current state;
6. only a frontier where `DurableNextTurnIndex == KnownNextTurnIndex` can be captured as a durable authority epoch;
7. a restarted authority restores that exact persisted room/game/state checkpoint;
8. if a required replica exists, the restored authority remains fenced until that replica verifies the persisted frontier/hash again;
9. synchronized peers may instead derive a deterministic successor and rotate to a new authority epoch when migration is required.

A completed `SendAsync` is **not** remote durability evidence. The ACK is the explicit proof that a remote state machine reproduced the authoritative state. Snapshot recovery uses the same rule: ACK occurs only after snapshot digest verification, restore, and canonical rehash.

`KnownNextTurnIndex` can temporarily be one turn ahead of `DurableNextTurnIndex` while proof is pending. `ActionCommitted` remains the local authoritative-commit signal; `DurabilityAdvanced` is the replication-safe signal. While a durability commit or post-restart revalidation fence is pending, another action is rejected with `ReplicationPending` without advancing the coordinator or mutating the reducer.

A persisted authority record is **not** treated as a globally unique authority lease. After process restart, the old authority may be stale because another peer could already have migrated the match to a newer session. `DurabilityRevalidationPending` therefore blocks all new turns until the same designated replica ACKs the exact persisted frontier/hash. If that peer has already moved to a newer session, the stale epoch cannot obtain the required proof and stays fenced. SolarNet intentionally sacrifices liveness rather than claiming false authority safety.

For two-player Grid Duel, the other player is both the deterministic successor and the designated replica. After migration, the former host becomes the promoted authority's replica. Larger games must define their own successor/quorum policy; SolarNet 0.17 is not a general consensus protocol.

The authority record's SHA-256 detects accidental corruption but does not authenticate hostile local storage changes. Applications needing tamper resistance should add platform-backed authenticated storage or signatures.

Nearby can rediscover a migrated room and Bluetooth Classic retains authenticated peer device-address hints. Probe 0.3.0 now gives Nearby and Bluetooth Classic the same physical-soak metric semantics, but compiling that harness is not physical-radio evidence. Physical process-kill/relaunch, radio reliability/RTT, thermal behavior, and power behavior remain unverified until real devices are measured.

See `docs/replication-ack.md`, `docs/durability-barrier.md`, `docs/authority-epoch-persistence.md`, `docs/process-resume.md`, `docs/authority-checkpoint.md`, `docs/host-migration-planner.md`, `docs/migrated-room-transport-switch.md`, `docs/gridduel-host-migration.md`, `docs/physical-radio-soak.md`, and `docs/transport-soak-comparison.md`.

## Repository layout

```text
Packages/com.hoonex.solarnet/
  Runtime/Protocol/                 wire framing + engine version
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   Nearby transport + room discovery/migration switching
  Runtime/BluetoothClassic/         Bluetooth-only RFCOMM transport + migration switching
  Runtime/Android/                  Unity Android C# adapters + permission helpers
  Runtime/Room/                     room lifecycle + migration/persisted-authority bootstrap
  Runtime/State/                    reducer / digest / journal / snapshots
  Runtime/Turns/                    authoritative turn state + checkpoint restore
  Runtime/Session/                  game orchestration + replication/durability + authority persistence/promotion
  Plugins/Android/                  Nearby + Bluetooth Classic .androidlib bridges
  Samples~/Diagnostics/             two-phone transport/room diagnostics
  Samples~/GridDuel/                local + two-phone playable sample + migration/persistence workflow
src/SolarNet.Core/                  .NET build wrapper
tests/                              protocol, recovery, replication, durability, persistence, chaos, migration, room, game, Android gates
android-smoke/                      Android libraries + dual-transport Probe APK + physical-soak metric harness
docs/                               architecture, recovery, and physical-evidence contracts
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
14. Persist current/migrated durable authority epochs across authority process death with post-restart replica revalidation — done in deterministic CI models and Unity compile surface.
15. Bluetooth Classic physical-radio soak harness with machine-readable packet/RTT evidence — done and build-verified.
16. Nearby physical-soak parity using the same controller/metrics and authenticated connection flow — implemented; PR/device evidence pending.
17. Next after harness verification: physical multi-phone process-kill/radio/thermal evidence, then evaluate follower-side durable epoch hints and release hardening.

Read the documents above for exact recovery contracts, evidence boundaries, and known limitations.
