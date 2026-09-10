# Nightshift — network architecture

## Authority rule

The server owns every state that can affect fairness or completion:

- player world position after movement/collision resolution
- stamina and flashlight battery
- ready/start phase
- breaker state and extraction unlock
- down/revive/escape state
- hunter sensing, target selection, movement and attacks
- match terminal state

Clients own only presentation and submit intent: movement axes, yaw, sprint, interact and desired flashlight state.

## v0.1 transport

A persistent TCP stream is used deliberately for the first playable foundation. Each frame is `int32 length + binary protocol payload`; the payload has a magic/version/type header. TCP gives reliable ordered lobby/control messages without Bluetooth, Nearby, peer-host migration or NAT-to-NAT assumptions.

- server simulation: 20 Hz fixed tick
- server snapshots: 10 Hz
- client input: 20 Hz
- room size: 1–4 players
- room codes: six characters, excluding ambiguous glyphs
- session authentication: random per-player 64-bit token scoped to the room process
- protocol version: 1 (`NSH1`)

This is not claimed to be the final latency architecture. If real Internet testing shows head-of-line stalls are material, movement/snapshot traffic can move to UDP/QUIC while keeping the same authority boundary and room/game APIs.

## Trust boundary

A client cannot submit its own position, breaker completion, revive result, monster position, damage/down result or match win. Inputs are sequence-checked inside the simulation so an older movement command cannot overwrite a newer one.

## Reconnect and production gaps

v0.1 intentionally does not claim seamless reconnect. Socket loss removes the player from the room. Production work still needs:

- authenticated identity and reconnect lease
- TLS or a secure tunnel around the authority port
- public room/matchmaking service
- server deployment/health supervision
- snapshot interpolation and local movement prediction
- bandwidth/latency/loss instrumentation
- abuse/rate limiting and stronger session tokens

These are explicit milestones, not hidden assumptions.
