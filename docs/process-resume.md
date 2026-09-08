# Process-death resume

SolarNet has two distinct process-restart paths. They intentionally use different evidence because a non-authoritative client can recover from a live host, while an authoritative process must recover from a previously persisted durable checkpoint.

## Client process death while the host remains alive

A reconnecting client must reuse the same logical SolarNet peer ID and the same room display name. The Grid Duel Unity sample stores its client peer ID in `PlayerPrefs`, so terminating and relaunching the client app does not create a new room identity.

Do not derive the logical peer ID from a transient Nearby endpoint ID, Bluetooth MAC/connection handle, or a fresh GUID created on every launch.

Client resume flow:

1. The host keeps the existing `SolarRoomSession` and `SolarTurnSession` alive.
2. The client process exits and its transport disconnects.
3. The host marks the existing room player offline but preserves the slot because the room is already `Playing`.
4. The relaunched client starts a transport with the same logical peer ID.
5. The new `SolarRoomSession` joins the same host. The host recognizes the existing identity, marks that slot online, and republishes the current `Playing` snapshot with the original `gameSessionId`.
6. The fresh client receives `GameStarted`, creates a new game-state machine and a new `SolarTurnSession`, then requests authoritative recovery from turn `0`.
7. If the host journal still covers the range, commits are replayed. If not, the host sends an authoritative snapshot. The client must not accept local gameplay input until recovery converges.

The Grid Duel sample detects this path when a fresh client joins directly into `Playing` without first observing `Lobby`; it then requests the full resync automatically.

`SolarNet.ProcessResume.SmokeTests` deliberately uses a host journal capacity of `1`, advances Grid Duel, destroys the original client room/game/transport, then creates a fresh client process model with the same peer ID and empty game state. The resumed client must reclaim its slot, preserve the game session ID, recover by snapshot fallback, match the host digest, and continue play without duplicating roster identity.

## Current authority process death

SolarNet 0.17 adds a separate path for the **currently authoritative peer**. The authority can persist a `SolarAuthorityEpochRecord` only at a designated-replica durable frontier, then reconstruct the same room/game authority after its process restarts.

That record carries room/roster identity, game session ID, canonical state bytes/hash, turn metadata, and the required replica. Only the persisted authority peer ID can restore it.

Restore is not enough to resume new turns. A restarted authority with a required replica begins with `DurabilityRevalidationPending`. Local and remote actions are rejected with `ReplicationPending` until the same replica verifies the persisted frontier and exact state hash. This prevents a stale local record from immediately producing competing turns when another device may already have migrated the match to a newer authority epoch.

See `docs/authority-epoch-persistence.md` for the complete durable-record and replica-revalidation contract.

## Limits

The two paths do **not** yet solve simultaneous process loss on all players. A non-authority process does not currently persist enough authoritative epoch history to discover and choose the newest epoch if every process dies. Physical Android process-kill/relaunch and Nearby/Bluetooth radio reconnection also remain unverified until multi-phone device testing is performed.
