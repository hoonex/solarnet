# Nightshift — network architecture

## Authority rule

The server owns every state that affects fairness or completion: collision-resolved position, stamina/battery, lobby phase, fuse/keycard/breaker/extraction state, blackout/threat/surge pacing, down/revive/escape, hunter sensing/AI/attacks and terminal result. Clients submit only movement axes, yaw, sprint, interaction and desired flashlight state.

## Protocol v3 / 0.3.0

Frames remain `int32 length + binary payload` on a persistent TCP connection.

- server simulation: 20 Hz
- snapshots: 10 Hz
- client input: 20 Hz
- room size: 1–4
- room code: six characters
- room-scoped random 64-bit session token
- protocol: v3 (`NSH1` magic + version field)

Protocol v3 preserves the v2 input ACK and `RESUME` contract and extends snapshots with:

- per-player carried-fuse flag
- global fuse pickup states
- security-keycard recovery
- blackout state
- threat level
- remaining breaker hunt-surge ticks

The wire version changes deliberately so older clients/servers fail closed rather than silently mis-decoding objective or horror state.

## Prediction and interpolation

Canonical player locomotion remains shared by server truth and Android local prediction. Server snapshots acknowledge the last accepted input sequence; the client prunes acknowledged inputs, rebuilds from authority, replays outstanding input and smooths bounded correction. Remote players and hunter remain buffered one snapshot and never extrapolate past authority.

## Reconnect lease and objective safety

Unexpected transport loss reserves the player ID/token for 200 server ticks (10 seconds), neutralizes movement/interaction and turns off authoritative flashlight intent. Carried fuse state is preserved during that lease. If the lease expires and the player is removed, the fuse is returned to its original spawn so disconnect cleanup cannot make the objective graph unwinnable.

## Remaining production gaps

0.3.0 still does not provide measured WAN quality, TLS/production authentication, public matchmaking, relay/NAT traversal, process-loss reconnect persistence, DDoS/rate limiting, production observability or release signing.
