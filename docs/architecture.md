# SolarNet architecture

## Design goal

SolarNet is a local-first, transport-agnostic multiplayer engine optimized first for turn-based games. Turn-based networking lets the engine prioritize correctness, recoverability, reconnection, and deterministic state over high-frequency transform replication.

## Authority model

The first protocol uses one authoritative host.

```text
client action
    |
    v
[ host validates ] -> [ commit ] -> broadcast to peers
    |                    |
 reject                 game applies committed action
```

The host owns the canonical turn index and player order. Clients may propose actions only for the turn they believe is current. A proposal is accepted only when:

1. the sender identity matches the transport peer identity;
2. the packet belongs to the current session;
3. the sender is a known player;
4. the sender is the active player;
5. the expected turn index equals the host turn index;
6. the per-peer sequence is newer than the last accepted sequence.

A committed action advances exactly one turn. This makes duplicate delivery safe at the turn authority boundary.

## Packet boundary

`SolarPacketCodec` owns framing. Transport implementations move opaque `byte[]` frames only. This keeps Bluetooth/Nearby/LAN implementations from leaking platform-specific objects into the game layer.

Current frame fields:

- magic `SNET`;
- protocol version;
- packet type;
- session ID;
- sender peer ID;
- sequence;
- turn index;
- optional state hash slot;
- payload.

The payload is message-specific. The current turn protocol has action, committed-action, and rejection payloads.

## Game boundary

SolarNet does not decide whether an attack, card placement, movement, or skill is legal. `TurnCoordinator` currently proves transport/turn authority only. The next game-facing layer will insert a game-rule validator and state reducer before a commit is broadcast.

Planned commit pipeline:

```text
identity -> turn authority -> game-rule validation -> apply reducer
         -> state digest -> commit broadcast -> digest comparison
```

## Transport boundary

`ISolarTransport` requires:

- one local peer ID;
- start/stop lifecycle;
- direct send;
- broadcast;
- async frame delivery with the remote peer ID supplied out-of-band.

`LoopbackTransportHub` is the executable reference behavior. Future transports must preserve the same sender identity semantics.

## Next milestones

### M1 — Core (current)
Host-authoritative turns, packet codec, transport abstraction, loopback E2E tests.

### M2 — Nearby Android
Android Nearby Connections adapter, discovery, connection authentication UI, permission handling, disconnect/reconnect states, two-device sample.

### M3 — State integrity
Deterministic game reducer contract, state digests, snapshots, resync requests, reconnect catch-up, action journal.

### M4 — Lobby/session UX
Room creation, nearby discovery, player ready state, host migration policy, explicit protocol compatibility errors.

### M5 — Additional transports
Bluetooth experiments and LAN adapter behind the same contract; optional relay transport later.
