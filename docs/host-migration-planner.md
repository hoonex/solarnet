# Deterministic host-migration planning

SolarNet 0.12 adds a deterministic room-level planning primitive on top of authority checkpoints.

## Safety-first successor rule

For a `Playing` room, `SolarHostMigrationPlanner.Create(...)` validates that the room roster and authority checkpoint describe the same ordered players and the same source game session. It then elects the lowest stable room slot that is not the old host.

The election deliberately ignores transient `IsConnected` values. Two peers can observe radio disconnects at slightly different times; allowing those observations to change the elected host could create split-brain authorities. A fixed succession order sacrifices some liveness when the designated successor is unavailable, but keeps the first migration rule deterministic.

There is no automatic fallback to the next candidate yet. Fallback requires a migration-generation protocol or stronger quorum evidence so all survivors advance together.

## Deterministic authority epoch ID

All synchronized peers derive the same `NextGameSessionId` from:

- room ID;
- source game session ID;
- source host peer ID;
- elected successor peer ID;
- checkpoint next-turn index;
- checkpoint state hash;
- canonical ordered player IDs.

The fields are domain-separated and hashed with SHA-256. The resulting ID has the form `solarnet-migration-<sha256>`.

Room revision and transient connection flags are intentionally excluded. They are useful diagnostics, but including them would make peers with slightly different link observations derive different authority epochs.

## Promotion handoff

Only the peer for which `plan.IsSuccessor(localPeerId)` is true should create the promoted authority:

```csharp
var plan = SolarHostMigrationPlanner.Create(roomSnapshot, checkpoint);
if (plan.IsSuccessor(localPeerId))
{
    var promoted = SolarAuthorityPromotion.CreatePromotedHost(
        plan.NextGameSessionId,
        plan.SuccessorPeerId,
        transport,
        checkpoint,
        gameStateMachine);
}
```

`SolarAuthorityPromotion` independently validates the checkpoint and requires a new session ID, so planner and promotion form two separate safety checks.

## CI evidence

`SolarNet.HostMigrationPlanner.SmokeTests` builds independent room snapshots that disagree about transient successor connectivity but share the same canonical roster/checkpoint. Both derive the same successor and authority epoch ID. The elected successor then creates a promoted host and serves a snapshot to another peer before committing the next turn.

The suite also rejects room/checkpoint roster disagreement and attempts to plan migration from a non-playing room.

## Remaining orchestration

SolarNet still needs a transport/room migration coordinator that reacts to host loss, switches the elected peer into Nearby advertiser or Bluetooth server mode, makes other survivors rediscover/reconnect, and republishes the migrated room state. A later fallback policy must avoid split brain when the fixed successor itself is unavailable.
