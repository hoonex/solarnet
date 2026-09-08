# Authority checkpoint and promoted-host foundation

SolarNet 0.11 adds the core state-transfer primitive required before automatic host migration can be implemented safely.

## Why a new authority epoch is required

When an authoritative host disappears, old packets from that host can still be delayed in a transport queue. Reusing the same game session ID would make stale packets indistinguishable from packets produced by the replacement authority.

`SolarAuthorityPromotion.CreatePromotedHost` therefore requires a **new session ID**. The previous match state is preserved, but the authority epoch changes. Packets from the old epoch are naturally ignored by `SolarTurnSession` session multiplexing.

## Checkpoint contents

`SolarAuthorityCheckpoint` contains:

- source session ID;
- canonical ordered player IDs;
- authoritative next-turn index;
- active player ID;
- round;
- SHA-256 game-state digest;
- canonical game-state snapshot bytes.

Checkpoint construction validates the digest and also verifies that turn index, round, active player, and player order are internally consistent.

## Capture

A synchronized peer with an `ISolarGameStateMachine` can call:

```csharp
var checkpoint = SolarAuthorityPromotion.Capture(
    session,
    playerIds,
    gameStateMachine);
```

Capture is rejected if the current game-state bytes do not match the session's last authoritative state hash. This prevents a locally corrupted client from promoting itself using an untrusted state.

## Promotion

After transport/topology re-establishment, the elected peer creates the next authority epoch:

```csharp
var promoted = SolarAuthorityPromotion.CreatePromotedHost(
    newSessionId,
    newHostPeerId,
    transport,
    checkpoint,
    gameStateMachine);
```

The state machine is restored and digest-verified, and `TurnCoordinator.Restore` recreates the exact next turn, current player, and round. Sequence replay fences intentionally reset because the new session ID is the replay boundary.

Fresh peers joining the promoted authority request resync. Because the promoted host's new action journal starts empty, it can immediately serve the checkpoint state as an authoritative snapshot; new commits then populate the new epoch journal normally.

## CI evidence

`SolarNet.AuthorityCheckpoint.SmokeTests` runs a three-player match through turn 4, captures a trusted checkpoint on the future successor, tears down the old topology, creates a new session epoch with the successor as authoritative host, restores another peer by snapshot, continues two turns, then restarts the former host as an ordinary client and restores it from the successor. Turn order and state remain continuous through turn 7.

## Not automatic host migration yet

This milestone deliberately does not choose the successor or rebuild Nearby/Bluetooth topology automatically. A complete host-migration policy still needs:

- deterministic successor election;
- migration generation / authority-epoch distribution through the room layer;
- transport role switching (Nearby advertiser/discoverer or Bluetooth server/client);
- quorum/conflict rules so two isolated peers cannot both become authoritative;
- timeout and abandonment policy.

The checkpoint API isolates those orchestration concerns from deterministic game-state recovery.
