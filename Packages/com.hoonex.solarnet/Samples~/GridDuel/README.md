# Grid Duel

Grid Duel is a deliberately small playable 2D turn-game sample for SolarNet. It proves that game rules can live in a deterministic state machine while SolarNet owns room admission, turn authority, packet delivery, state hashes, resync, and reconnect recovery.

## Rules

- 5x5 board;
- two players: SUN and MOON;
- 3 HP each;
- one action per turn;
- move one orthogonal cell into an empty tile, or attack an orthogonally adjacent opponent;
- an attack deals 1 damage;
- reducing the opponent to 0 HP wins.

## Local demo

Import **Grid Duel** from SolarNet's Package Manager Samples section. Create an empty GameObject and add `GridDuelLocalDemo`. The demo creates two independent `SolarTurnSession` instances over `LoopbackTransport`.

## Two-phone Android demo

Create an empty GameObject and add `GridDuelNetworkedDemo`, then build the same Unity project to two Android phones.

### Bluetooth Classic

1. Pair the two phones in Android Bluetooth settings.
2. On both phones select **Bluetooth Classic** and grant the requested permission.
3. Start one phone as **Host** and the other as **Client**.
4. The client refreshes paired devices and connects to the host.
5. Both players toggle Ready; the host presses Start match.
6. Only the active player can move or attack.

### Nearby Connections

1. Select **Nearby** on both phones and grant the requested permissions.
2. Start one phone as Host and the other as Client.
3. The client selects the advertised Grid Duel room.
4. Confirm the same Nearby verification digits on both phones and Accept.
5. Both players toggle Ready; the host starts the match.

## Active-match reconnect

SolarNet keeps room identity and game state separate from the physical radio link. If the client loses the link but the app process stays alive, reconnect with the same peer identity. The room host restores the existing slot instead of creating a duplicate. The client then calls `SolarTurnSession.RequestResyncAsync()`; the host replays missing commits from its action journal, or sends an authoritative snapshot when replay is unavailable. After convergence, normal turn submission continues.

The networked sample requests this resync automatically when a client reconnects to the host during an active match.

CI forcibly unregisters the client `LoopbackTransport` mid-match, lets the host commit a turn while the client is absent, reconnects the same peer, replays the missing turn, verifies canonical state convergence, and then verifies that the reconnected client can submit the next attack.
