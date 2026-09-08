# Designated-replica durability barrier

SolarNet 0.16 builds a migration-safety fence on top of the state-verified `ReplicationAck` primitive introduced in 0.15.

## Goal

A transport send is not durability. A host may have applied a turn locally while the intended successor has never observed it. If the host then fails, the successor cannot recover bytes it never received.

The 0.16 barrier lets an authoritative host designate one known remote player as its required replica. The host can still form and broadcast the next authoritative commit, but it will not accept another turn until that replica proves the resulting canonical state with a valid `ReplicationAck`.

For two-player Grid Duel, the opponent is the only possible successor and is therefore the designated replica. After migration, the former host becomes the promoted authority's designated replica.

## State model

`KnownNextTurnIndex` and `DurableNextTurnIndex` are deliberately different concepts.

- `KnownNextTurnIndex`: the host's current authoritative/local state, including at most one provisional turn waiting for replication proof.
- `DurableNextTurnIndex`: the frontier explicitly proven by the designated replica.
- `DurabilityPending`: true while one authoritative commit is waiting for the designated replica.
- `RequiredReplicationPeerId`: the player whose verified ACK advances durability.

`ActionCommitted` remains the existing local authoritative-commit signal for compatibility. `DurabilityAdvanced` is the explicit replication-safe signal. Host `SubmitActionAsync` waits for the durability proof when the barrier is enabled, while the host gate itself is released so recovery traffic can continue.

## Commit flow

```text
validate turn
  -> deterministic reducer apply
  -> canonical state hash
  -> journal + KnownNextTurnIndex advance
  -> install pending durability record
  -> ActionCommitted
  -> broadcast TurnCommitted
  -> release host gate
  -> wait for designated-replica proof
       -> ReplicationAck verified against authoritative hash
       -> DurableNextTurnIndex advance
       -> DurabilityAdvanced
       -> SubmitActionAsync completes
```

The pending record is installed before broadcast. This is required because loopback and fast transports can deliver the client's ACK reentrantly while the host is still inside `BroadcastAsync`.

## Pending-turn fence

While `DurabilityPending` is true, another turn is rejected with `SolarTurnRejectReason.ReplicationPending`. The coordinator, reducer state, journal, and turn index do not advance for that rejected action.

This means SolarNet never builds a chain of later authoritative turns on top of an unreplicated predecessor. With the barrier enabled there can be at most one provisional turn beyond the designated replica's durable frontier.

## ACK loss and resync

An ACK can be lost even when the replica already applied the commit. The host must not hold `_hostGate` while waiting for durability, because doing so would deadlock recovery.

A replica can request resync at the host's current next-turn index. The host returns an authoritative snapshot. After snapshot digest verification, restore, and canonical rehash, the client emits another `ReplicationAck`. That ACK can clear the original pending durability record and resume normal turns.

No timeout silently disables the fence. If the required replica is unavailable, the session intentionally sacrifices liveness rather than pretending an unreplicated state is migration-safe.

## Migration contract

For migration safety, the designated replica should be the peer that the migration policy will elect as successor. Grid Duel satisfies this automatically because it is two-player and its deterministic successor is the only non-host player.

For larger games, callers must align `requiredReplicationPeerId` with their successor/quorum policy. A single designated-replica barrier is not a general multi-replica consensus protocol.

`SolarAuthorityPromotion.CreatePromotedHost` accepts an optional required replica so the promoted authority can continue the durability contract in the new epoch.

## Evidence

The dedicated smoke suite attacks four boundaries:

1. host local state advances but `DurableNextTurnIndex` and submit completion wait for the designated ACK;
2. a second turn while pending is rejected without state mutation;
3. a valid ACK from a non-designated peer records replication proof but cannot release the durability fence;
4. a dropped first ACK is recovered through same-turn snapshot resync without deadlocking the host gate.

Grid Duel migration tests also assert that the promoted successor retains a barrier and designates the former host as its replica.

## Remaining limits

- Durability/frontier state is in-memory; migrated authority epoch persistence across process death remains a later milestone.
- The barrier proves one designated replica, not quorum consensus.
- A provisional local host turn may exist before ACK. It cannot have dependent later turns while the fence is pending.
- Real Nearby/Bluetooth radio behavior still requires physical multi-phone soak evidence; CI proves protocol, deterministic state, Java compilation, and APK construction only.
