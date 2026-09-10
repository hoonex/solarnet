# Nightshift — network architecture

## Authority rule

The server owns every state that affects fairness or completion: collision-resolved position, stamina/battery, lobby phase, objectives, down/revive/escape, hunter sensing/AI/attacks and terminal result. Clients submit only movement axes, yaw, sprint, interaction and desired flashlight state.

## Protocol v2 / 0.2.0

Frames remain `int32 length + binary payload` on a persistent TCP connection.

- server simulation: 20 Hz
- snapshots: 10 Hz
- client input: 20 Hz
- room size: 1–4
- room code: six characters
- room-scoped random 64-bit session token
- protocol: v2 (`NSH1` magic + version field)

Protocol v2 adds two network-feel primitives:

1. every player snapshot includes `lastInputSequence`, the newest input accepted by the authority;
2. `RESUME` authenticates an existing room/player/token during a short reconnect lease.

The wire version changed because the snapshot layout and message set changed. A v1 client/server pair is intentionally rejected rather than silently mis-decoding state.

## Local prediction and reconciliation

Player locomotion lives in the shared `PlayerMotion` owner. The server uses that step for truth and the Android presentation layer uses it for immediate local prediction.

On an authoritative snapshot, the client:

1. removes pending inputs whose sequence is acknowledged;
2. rebuilds from authoritative position/stamina/battery;
3. replays remaining unacknowledged inputs;
4. hard-snaps only for a large divergence; otherwise visual correction decays over a short bounded interval.

This reduces perceived local control latency without transferring authority to the client. Because TCP scheduling and server tick/input arrival can differ, prediction is not claimed bit-identical under arbitrary WAN loss; reconciliation is the correctness boundary.

## Remote interpolation

Remote players and the hunter are rendered one 10 Hz snapshot interval behind the newest authority state. They interpolate between the two newest snapshots and never extrapolate beyond the newest one. This deliberately trades about one snapshot of presentation latency for lower visible jitter.

## Reconnect lease

An unexpected transport loss does not immediately delete the player. The room reserves the player ID/token for 200 server ticks (10 seconds), neutralizes movement/interaction input and turns off the authoritative flashlight intent. The Android client makes a finite reconnect sequence and sends `RESUME`.

An explicit leave removes the player immediately. If the lease expires, the server removes the reserved player and normal room/terminal-state rules apply.

## Remaining production gaps

0.2.0 does not prove or provide:

- measured real WAN latency/jitter/loss quality
- TLS / production authentication
- public matchmaking
- relay/NAT traversal
- process-loss reconnect persistence
- DDoS/abuse rate limiting
- production observability

A future transport may use UDP/QUIC for movement/snapshots while preserving these authority and reconciliation boundaries.
