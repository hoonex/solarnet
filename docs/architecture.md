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

`SolarPacketCodec` owns gameplay/session framing. Transport implementations move opaque `byte[]` frames only. This keeps Bluetooth/Nearby/LAN implementations from leaking platform-specific objects into the game layer.

Current game frame fields:

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

## Nearby identity boundary

Google Nearby gives each connection an ephemeral endpoint ID. SolarNet does not treat that identifier as game identity.

```text
Nearby endpoint id -- authenticated connection --> SolarNet Nearby envelope
                                                -> durable peer id
                                                -> ISolarTransport SolarFrame
```

Every Nearby peer sends a `SNBY` HELLO after the physical connection is established. DATA envelopes also carry the sender's durable peer ID, so delivery can recover the mapping even when HELLO and game traffic are tightly adjacent. A single endpoint may not change peer identity, and a peer ID may not silently move to a second live endpoint.

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

`LoopbackTransportHub` is the executable reference behavior. `NearbyTransport` preserves the same contract while delegating platform discovery/radio work to `INearbyPeerAdapter`.

Android uses a separate `SolarNet.Android` assembly and a Unity `.androidlib` bridge, keeping Unity/Java types out of `SolarNet.Runtime` and the deterministic .NET test surface.

## Milestones

### M1 — Core — complete
Host-authoritative turns, packet codec, transport abstraction, loopback E2E tests.

### M2 — Nearby Android — implementation complete, device verification pending
Nearby discovery/advertising adapter, explicit authentication confirmation API, peer-ID handshake, permission model, Android library compile gate, disconnect mapping, and Nearby-backed E2E core smoke test. Two physical Android devices are still required to verify real radios and permission/UI behavior.

### M3 — State integrity
Deterministic game reducer contract, state digests, snapshots, resync requests, reconnect catch-up, action journal.

### M4 — Lobby/session UX
Room creation, nearby discovery, player ready state, host migration policy, explicit protocol compatibility errors.

### M5 — Additional transports
Bluetooth experiments and LAN adapter behind the same contract; optional relay transport later.
