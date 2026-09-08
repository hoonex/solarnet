# Grid Duel

Grid Duel is SolarNet's deliberately small playable 2D turn-game sample. The same deterministic rules run over local loopback, Nearby Connections, or Bluetooth Classic.

## Rules

- 5x5 board;
- two players: SUN and MOON;
- 3 HP each;
- one action per turn;
- move one orthogonal cell into an empty tile, or attack an orthogonally adjacent opponent;
- an attack deals 1 damage;
- reducing the opponent to 0 HP wins.

## Local Unity demo

Import **Grid Duel** from SolarNet's Package Manager Samples section. Add `GridDuelLocalDemo` to an empty GameObject.

The local demo creates two independent `SolarTurnSession` instances and two independent `GridDuelStateMachine` instances over `LoopbackTransport`, so it exercises the same turn/session path without requiring phones.

## Two-phone Android demo

Add `GridDuelNetworkedDemo` to an empty GameObject and build the same project to two Android phones.

1. Choose one phone as **Host** and the other as **Client**.
2. Choose **Nearby** or **Bluetooth Classic** on both phones.
3. Grant the requested Android permissions.
4. Connect the phones. Bluetooth Classic expects them to be paired in Android Settings first. Nearby shows authentication digits that must match and be accepted on both phones.
5. Toggle Ready on both phones.
6. Start the match on the host.
7. Only the active player can submit board input.

## Recovery

A temporary link interruption keeps the existing client game session alive; reconnecting to the same host requests resync from the client's known turn.

For a full **client app process restart while the host remains alive**, the sample stores the client logical peer ID in `PlayerPrefs`. Relaunching the client and reconnecting with the same transport mode lets the room reclaim the original slot. Because the fresh client process has no trusted game state, it requests resync from turn 0 after receiving the existing `gameSessionId`; SolarNet replays retained commits or falls back to an authoritative snapshot.

For **current-authority process restart**, SolarNet 0.17 adds a separate durable authority-epoch path. Grid Duel stores the current authority's last durable room/game checkpoint and transport mode in `PlayerPrefs` after a persistable game start and after each `DurabilityAdvanced` event. The stored record includes canonical state bytes/hash, turn metadata, stable player order, current host identity, game session ID, and designated replica.

After relaunch, **Resume saved authority** reconstructs that same persisted authority epoch. It does not immediately unlock gameplay: the engine rejects local and remote turns with `ReplicationPending` until the same designated replica verifies the persisted frontier and state hash through a valid `ReplicationAck`. A snapshot/resync exchange can provide that proof. If the other phone has already migrated to a newer epoch, the stale restored authority cannot obtain the old-epoch proof and remains fenced.

An orderly migration clears the former authority's stale local record and writes a record on the newly promoted authority. Explicit **Stop** or **Discard saved authority** clears the saved authority record. A process/OS kill intentionally leaves it available for recovery.

This is not a two-node consensus protocol. Simultaneous process loss on both phones, automatic selection of the newest persisted epoch across devices, and real physical radio/process-kill recovery still require separate validation.

See `docs/authority-epoch-persistence.md` for the full safety contract and limitations.
