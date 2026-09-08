# Replication acknowledgements

SolarNet 0.15 adds a state-verified acknowledgement path for authoritative game state.

## What an acknowledgement proves

A client sends `ReplicationAck` only after it has accepted authoritative state locally and the resulting canonical state digest matches the authoritative digest.

For an ordinary `TurnCommitted` packet the client:

1. applies the committed action through its `ISolarGameStateMachine`;
2. captures the canonical state;
3. verifies the SHA-256 digest against the host's committed digest;
4. advances its local next-turn metadata;
5. sends `ReplicationAck(nextTurnIndex, stateHash)`.

For an authoritative snapshot the acknowledgement is sent only after snapshot bytes pass the wire digest check, `RestoreSnapshot`, and the canonical capture/rehash round trip.

A transport send completing on the host is therefore **not** treated as replication evidence. Only the returned state-verified acknowledgement advances the host's proof frontier.

## Host API

A state-integrity-enabled host exposes:

```csharp
long frontier = session.GetReplicationFrontier(peerId);
bool replicated = session.IsReplicatedThrough(peerId, nextTurnIndex);
```

The frontier is the highest `nextTurnIndex` for which that remote peer supplied a valid authoritative state digest. Duplicate or older acknowledgements are idempotent.

`ReplicationAcknowledged` is raised only when a peer's frontier advances. `ReplicationAcknowledgementFailed` reports a client's best-effort acknowledgement send failure without reclassifying a link failure as a protocol corruption.

## Validation and bounded evidence

The host accepts an acknowledgement only when:

- transport peer identity matches packet sender identity through the ordinary SolarNet frame boundary;
- the sender is a known non-host player in the session;
- the acknowledgement is not ahead of authoritative state;
- payload is empty and a state digest is present;
- the supplied digest matches the authoritative digest recorded for that frontier.

Recent authoritative frontier digests are retained in a bounded proof cache sized from the session journal capacity. A legitimate acknowledgement older than that cache is ignored rather than treated as corruption. A wrong digest for a retained frontier is a protocol fault and cannot advance the frontier.

## Compatibility

`ReplicationAck` uses packet type 9 while SolarNet's core packet version remains 1. The acknowledgement is an additive capability: older sessions that do not implement it ignore the packet type, while 0.15 hosts simply never receive a frontier advance from such peers. Code that requires replication proof must check the frontier rather than infer capability from connection state.

## Durability boundary

0.15 creates a **replication proof boundary**, not a transactional commit protocol.

It still does not make a host-local commit atomic with its replica. A host can apply a turn locally and fail before the successor receives it. The successor cannot infer that an unseen newer commit existed.

The next milestone is therefore a durability barrier built on this acknowledgement primitive: a user-visible/ migration-safe commit must not be declared durable until the designated replica has acknowledged the corresponding frontier. That policy must also define timeout, provisional state, and rollback/recovery semantics instead of pretending that `SendAsync` means remote durability.

## CI coverage

`SolarNet.ReplicationAck.SmokeTests` verifies:

- a digest-verified committed action advances the remote peer frontier;
- a snapshot applied after promoted-host recovery advances the frontier;
- duplicate acknowledgements are idempotent;
- a forged digest cannot advance replication proof and raises a protocol fault.

Physical-radio behavior is still a separate evidence class. These tests prove protocol/state semantics, not OEM Bluetooth/Nearby delivery characteristics.
