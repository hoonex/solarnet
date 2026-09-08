# Client process-death resume

SolarNet 0.10 verifies a client-side process restart while the authoritative host process remains alive.

## Required identity contract

A reconnecting client must reuse the same logical SolarNet peer ID and the same room display name. The Grid Duel Unity sample stores its client peer ID in `PlayerPrefs`, so terminating and relaunching the client app does not create a new room identity.

Do not derive the logical peer ID from a transient Nearby endpoint ID, Bluetooth MAC/connection handle, or a fresh GUID created on every launch.

## Resume flow

1. The host keeps the existing `SolarRoomSession` and `SolarTurnSession` alive.
2. The client process exits and its transport disconnects.
3. The host marks the existing room player offline but preserves the slot because the room is already `Playing`.
4. The relaunched client starts a transport with the same logical peer ID.
5. The new `SolarRoomSession` joins the same host. The host recognizes the existing identity, marks that slot online, and republishes the current `Playing` snapshot with the original `gameSessionId`.
6. The fresh client receives `GameStarted`, creates a new game-state machine and a new `SolarTurnSession`, then requests authoritative recovery from turn `0`.
7. If the host journal still covers the range, commits are replayed. If not, the host sends an authoritative snapshot. The client must not accept local gameplay input until recovery converges.

The Grid Duel sample detects a process-resume path when a fresh client joins directly into `Playing` without first observing `Lobby`; it then requests the full resync automatically.

## CI evidence

`SolarNet.ProcessResume.SmokeTests` deliberately configures the host journal to capacity `1`, advances Grid Duel to turn 3, destroys the original client room/game/transport, and creates a fresh client process model with the same peer ID and an empty `GridDuelStateMachine`.

The resumed client must:

- reclaim the original room slot without duplicating the roster;
- receive the original game session ID;
- recover turn 3 through snapshot fallback because the old journal range is unavailable;
- match the host state hash;
- immediately submit its pending attack;
- remain converged after the host's following turn.

## Limitations

This milestone covers **client process death with the host still alive**. It does not preserve an authoritative host across host process death. Host crash recovery or host migration requires durable authoritative state, ownership transfer rules, replay fencing, and conflict handling and is intentionally a separate milestone.
