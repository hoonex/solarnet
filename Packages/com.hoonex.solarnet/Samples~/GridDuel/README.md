# Grid Duel

Grid Duel is a deliberately small playable 2D turn-game sample for SolarNet. It proves that game rules can live in a deterministic state machine while SolarNet owns room admission, turn authority, packet delivery, state hashes, and resync.

## Rules

- 5x5 board;
- two players: SUN and MOON;
- 3 HP each;
- one action per turn;
- move one orthogonal cell into an empty tile, or attack an orthogonally adjacent opponent;
- an attack deals 1 damage;
- reducing the opponent to 0 HP wins.

## Local demo

Import **Grid Duel** from SolarNet's Package Manager Samples section. Create an empty GameObject and add `GridDuelLocalDemo`.

The local demo creates two independent `SolarTurnSession` instances with independently-owned `GridDuelStateMachine` instances. Frames travel through `LoopbackTransport`, so the same authoritative turn/session path is exercised without two phones.

## Two-phone Android demo

Create an empty GameObject and add `GridDuelNetworkedDemo`, then build the same Unity project to two Android phones.

### Bluetooth Classic

1. Pair the two phones first in Android Bluetooth settings.
2. On both phones select **Bluetooth Classic** and grant the requested permission.
3. Start one phone as **Host** and the other as **Client**.
4. On the client, refresh paired devices and connect to the host phone.
5. Both players toggle Ready; the host presses Start match.
6. The board appears on both phones. Only the active player can move or attack.

This path is explicitly Bluetooth-only and uses SolarNet's secure RFCOMM transport.

### Nearby Connections

1. Select **Nearby** on both phones and grant the requested permissions.
2. Start one phone as Host and the other as Client.
3. The client selects the advertised Grid Duel room.
4. Confirm the same Nearby verification digits on both phones and Accept.
5. Both players toggle Ready; the host starts the match.

Nearby chooses Bluetooth/BLE/Wi-Fi transports internally; SolarNet still exposes the same room and turn APIs.

## State integrity

`GridDuelStateMachine` is passed directly to `SolarTurnSession` on both phones. Every accepted move or attack produces a canonical snapshot and SHA-256 state digest. A mismatch uses SolarNet's existing journal/snapshot resync path instead of letting the two game boards silently diverge.

The CI integration suite also exercises room join -> Ready -> Start -> Grid Duel gameplay over one shared `LoopbackTransport`, proving that room and game packets coexist on the same connection before physical-radio testing.
