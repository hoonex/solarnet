# SolarNet architecture

## Design goal

SolarNet is a local-first, transport-agnostic multiplayer engine optimized first for turn-based games. Turn-based networking lets the engine prioritize correctness, recoverability, reconnection, and deterministic state over high-frequency transform replication.

## Authority model

The host owns the canonical turn index, player order, action acceptance, and—when an `ISolarGameStateMachine` is attached—the canonical game state.

```text
client action
    |
    v
[ turn authority ] -> [ game reducer ] -> [ commit ] -> SHA-256 digest -> broadcast
       | reject           | reject
       +------------------+----> rejection
```

An action advances exactly one turn only after identity, active-player, turn-index, sequence, and optional game-rule checks pass.

## Packet boundary

`SolarPacketCodec` owns gameplay/session framing. Transport implementations move opaque `byte[]` frames only. This keeps Bluetooth/Nearby/LAN implementations from leaking platform-specific objects into the game layer.

Current game frame fields include protocol version, packet type, session/sender identity, monotonic packet sequence, turn index, state-hash slot, and message payload. Protocol message types now cover turn actions/commits/rejections plus snapshot/resync flow.

## Nearby identity boundary

Google Nearby gives each connection an ephemeral endpoint ID. SolarNet does not treat that identifier as game identity.

```text
Nearby endpoint id -- authenticated connection --> SolarNet Nearby envelope
                                                -> durable peer id
                                                -> ISolarTransport SolarFrame
```

Every Nearby peer sends a `SNBY` HELLO after the physical connection is established. DATA envelopes also carry the sender's durable peer ID. A single endpoint may not change peer identity, and a peer ID may not silently move to a second live endpoint.

## State integrity boundary

The optional `ISolarGameStateMachine` owns game-specific deterministic state and rules; SolarNet owns sequencing, digest comparison, journal replay, and snapshot transport.

```text
TurnCommitted(N, hash)
      |
client applies N exactly once
      |
canonical snapshot -> hash
      |
 match --------------------> continue
 mismatch/gap -> ResyncRequest(last known next turn)
                     |
        journal range available? -- yes --> replay commits
                     |
                     no
                     v
             authoritative snapshot
```

Snapshots are digest-checked before restore and again after restore. Stale snapshots cannot rewind newer local state.

## Transport boundary

`ISolarTransport` requires one local peer ID, start/stop lifecycle, direct send, broadcast, and async frame delivery with the remote peer ID supplied out-of-band.

`LoopbackTransportHub` is the executable reference behavior. `NearbyTransport` preserves the same contract while delegating platform discovery/radio work to `INearbyPeerAdapter`. Android uses a separate `SolarNet.Android` assembly and Unity `.androidlib` bridge.

## Milestones

### M1 — Core — complete
Host-authoritative turns, packet codec, transport abstraction, loopback E2E tests.

### M2 — Nearby Android — implementation complete, device verification pending
Nearby discovery/advertising adapter, explicit authentication confirmation API, peer-ID handshake, permission model, Android library compile gate, disconnect mapping, and Nearby-backed E2E core smoke test.

### M3 — State integrity — complete in deterministic protocol tests
Optional deterministic game reducer, game-rule rejection, SHA-256 state digests, bounded action journal, gap replay, snapshot fallback, stale-snapshot protection, and reconnect resync API.

### M4 — Lobby/session UX
Room creation, nearby discovery, player ready state, host migration policy, explicit protocol compatibility errors.

### M5 — Additional transports
Bluetooth experiments and LAN adapter behind the same contract; optional relay transport later.
