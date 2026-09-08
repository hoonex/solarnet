# Grid Duel

Grid Duel is a deliberately small playable 2D turn-game sample for SolarNet. It proves that game rules can live in a deterministic state machine while the multiplayer engine owns turn authority, packet delivery, state hashes, and resync.

## Rules

- 5x5 board;
- two players: SUN and MOON;
- 3 HP each;
- one action per turn;
- move one orthogonal cell into an empty tile, or attack an orthogonally adjacent opponent;
- an attack deals 1 damage;
- reducing the opponent to 0 HP wins.

## Run in Unity

Import **Grid Duel** from SolarNet's Package Manager Samples section. Create an empty GameObject and add `GridDuelLocalDemo`.

The local demo is not a shortcut around networking: it creates two independent `SolarTurnSession` instances with two independently-owned `GridDuelStateMachine` instances. Frames travel through `LoopbackTransport`, so the exact same turn/session code path is exercised as a physical transport. The board is simply rendered on one screen so the integration can be tested without two phones.

For phone-to-phone transport testing use the **SolarNet Diagnostics** sample. A later game can reuse `GridDuelStateMachine`-style deterministic rules with either Nearby or Bluetooth Classic transport.
