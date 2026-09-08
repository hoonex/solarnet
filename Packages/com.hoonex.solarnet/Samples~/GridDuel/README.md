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

For a full **client app process restart**, the sample stores the client logical peer ID in `PlayerPrefs`. If the host match is still alive, relaunching the client and reconnecting with the same transport mode lets the room reclaim the original slot. Because the fresh process has no trusted game state, the sample requests resync from turn 0 after it receives the existing `gameSessionId`. SolarNet replays retained commits or falls back to an authoritative snapshot.

Host process death is not recovered by this sample yet.
