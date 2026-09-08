# SolarNet architecture

## Design goal

SolarNet is a local-first, transport-agnostic multiplayer engine optimized first for turn-based games. The engine separates four concerns: transport, room lifecycle, authoritative turns, and deterministic game state.

```text
physical transport (Nearby / loopback / future LAN)
             |
             +---- roomId --------> RoomSession: join / ready / start
             |
             +---- gameSessionId --> TurnSession: action / commit / resync
                                         |
                                         v
                                  deterministic game state
```

## Authority model

The room host owns roster/revision/start decisions. The game host owns turn index/player order and, when an `ISolarGameStateMachine` is attached, the canonical game state. Game-rule rejection happens before turn advancement; successful commits carry SHA-256 state digests.

## Nearby identity boundary

Google Nearby endpoint IDs remain transport-local. A `SNBY` handshake maps them to durable SolarNet peer IDs before room/game packets are delivered. Human authentication digits must still be confirmed before the Nearby connection is accepted.

## State integrity boundary

Clients apply committed turns exactly once at `KnownNextTurnIndex`. Gaps trigger journal replay; digest mismatches trigger authoritative snapshot recovery. Snapshots are verified before and after restore, and stale snapshots cannot rewind newer state.

## Room boundary

Room traffic uses the room ID as its packet session identity. Starting a game creates a different game session ID while preserving the same transport connection. Room snapshots have monotonically increasing revisions and stable player slots. Join admission checks both SolarNet core protocol version and a game-defined compatibility key.

Host migration is explicitly not synthesized from a disconnect event. 0.4 supports close-on-host-loss or reconnect-to-the-same-host policies. A future migration design must transfer coordinator/journal/state authority with fencing to prevent split brain.

## Milestones

### M1 — Core — complete
Host-authoritative turns, packet codec, transport abstraction, loopback E2E tests.

### M2 — Nearby Android — implementation complete, device verification pending
Nearby discovery/advertising adapter, explicit authentication confirmation API, peer-ID handshake, permission model, Android library compile gate, and Nearby-backed E2E core smoke test.

### M3 — State integrity — complete in deterministic protocol tests
Game reducer, SHA-256 digests, bounded action journal, gap replay, snapshot fallback, stale-snapshot protection, reconnect resync API.

### M4 — Room lifecycle — core complete
Join admission, game compatibility key, authoritative roster revisions, ready state, stable slots, start transition, same-transport room/game multiplexing, and explicit host-disconnect policy. True host migration remains deliberately unimplemented until authority transfer can be fenced.

### M5 — Product/sample layer
Unity sample game and room UI, followed by real two-device Android verification. Additional Bluetooth/LAN transports can then be compared behind the same interfaces.
