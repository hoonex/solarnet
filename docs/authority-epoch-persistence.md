# Durable authority epoch persistence

SolarNet 0.17 can persist the current authoritative game epoch after it has reached the designated-replica durability frontier, then reconstruct that same authority after the authority process restarts.

This is a safety-first recovery mechanism, not a distributed lease or a general consensus protocol.

## Persisted record

`SolarAuthorityEpochRecord` binds one `Playing` room snapshot to one canonical `SolarAuthorityCheckpoint` and the current required replication peer.

The record preserves:

- room ID, name, compatibility key, revision, capacity, and `Playing` phase;
- current authority peer ID;
- current game session ID;
- stable player slot order, display names, ready state, and roster identity;
- next turn index, current player, and round;
- canonical game-state bytes and SHA-256 state digest;
- designated replica peer ID when the durability barrier is enabled.

Room slot order must exactly match the checkpoint player order. Duplicate slots, duplicate peer IDs, mismatched epochs, missing hosts, or invalid replica identities are rejected.

## Durable-only capture

`SolarAuthorityEpochPersistence.Capture` is host-only and state-integrity-only. It refuses to persist a provisional turn when `DurabilityPending` is true or when `DurableNextTurnIndex` trails `KnownNextTurnIndex`.

Therefore a host-local state mutation that has not yet been verified by its required replica cannot become the durable recovery record.

A record that was already persisted at a verified durability frontier remains valid evidence after a process restart even though the new process begins with an empty in-memory replication-ACK ledger. The persisted checkpoint is not silently advanced beyond that frontier.

## Encoding and integrity

`SolarAuthorityEpochCodec` uses a versioned deterministic binary record with `SNAE` magic and a SHA-256 digest over the serialized body. Length, truncation, trailing bytes, roster bounds, and state-size limits are validated while decoding.

This detects accidental corruption. It is **not** cryptographic authentication against a malicious local user who can rewrite both the record and its digest. Applications requiring tamper resistance need platform-backed authenticated storage or signatures above this codec.

## Authority process restart

`SolarAuthorityEpochResume.Create` only allows the peer ID recorded as the current room authority to restore the epoch.

Resume performs the following safety checks and reconstruction:

1. restore canonical game-state bytes into the reducer;
2. capture the reducer again and require the persisted SHA-256 digest;
3. rebuild the authoritative `TurnCoordinator` at the persisted turn/current-player/round;
4. rebuild the `Playing` room with the same game session ID and stable roster order;
5. increment the room revision and mark only the restarted authority as connected initially;
6. restore the designated-replica durability barrier;
7. require replica revalidation before accepting any new turn.

The game session ID is intentionally preserved because this is restoration of the same persisted authority epoch, not election of a new authority epoch.

## Replica revalidation fence

A persisted record is not treated as a fresh globally unique authority lease. Another device may have survived the crash and already moved the match to a newer authority epoch.

When the persisted record contains a designated replica, the restored host starts with `DurabilityRevalidationPending == true`. While that flag is set, both local and remote actions are rejected with `ReplicationPending` without mutating game state or advancing the turn coordinator.

The fence clears only after the same required replica produces a valid `ReplicationAck` covering the persisted next-turn frontier with the exact persisted canonical state hash. A normal `ResyncRequest -> Snapshot -> verified ReplicationAck` exchange can supply that proof.

If the replica has already migrated to a newer session, packets from the restored old session do not match that newer epoch and the old authority cannot obtain the required ACK. It therefore remains fenced instead of creating competing new turns. SolarNet intentionally sacrifices liveness rather than claiming false authority safety.

## Grid Duel PlayerPrefs integration

The Grid Duel Unity sample stores the encoded authority record as Base64 in `PlayerPrefs`, together with the selected transport mode.

The current authority writes a record:

- when a new game authority starts at a persistable durable frontier;
- after each `DurabilityAdvanced` event;
- after orderly host migration when this device becomes the new authority.

When a device migrates away from authority during an orderly migration, its stale local authority record is cleared. Explicit **Stop** or **Discard saved authority** also clears the record. An OS/process kill does not intentionally clear it.

On relaunch, Grid Duel exposes **Resume saved authority** instead of silently starting a competing fresh match. The restored game remains input-fenced until designated-replica revalidation succeeds.

## Evidence

`SolarNet.AuthorityEpochResume.SmokeTests` covers:

- migrated authority persistence and same-peer process restart;
- canonical state, turn, player order, room epoch, and designated-replica restoration;
- immediate re-capture of the already-persisted durable frontier after restart;
- rejection of local/remote gameplay before replica revalidation;
- snapshot/ACK revalidation followed by continued durable play;
- rejection of provisional-turn persistence;
- corrupted-record rejection;
- wrong-peer authority takeover rejection.

Permanent CI also compiles the Grid Duel Unity surface and runs the existing durability, replication, process-resume, migration, chaos, room, and game regressions.

## Limits

SolarNet 0.17 does not yet prove:

- simultaneous process loss on all players with automatic discovery of the newest surviving epoch;
- multi-replica quorum/consensus semantics for larger rooms;
- protection against malicious local storage modification;
- real-device Android process-kill/relaunch behavior;
- physical Nearby/Bluetooth reconnect behavior after authority process death;
- radio, thermal, power, or long-duration physical soak behavior.

Those claims require follower-side durable epoch hints and physical multi-phone validation rather than additional in-memory tests.
