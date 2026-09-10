# Nightshift

Nightshift is a first-person horror game prototype for Android. **Single-player is now the primary standalone path:** install the APK, tap `PLAY SOLO`, and the full facility simulation runs in-process with no server address, room code, second device, or network connection.

## 0.4.0 — standalone playable build

The launcher is now a real game menu rather than a server configuration screen. `PLAY SOLO` starts a local 20 Hz authoritative `GameSimulation` through `SoloGameRuntime`; multiplayer remains available as a separate experimental menu.

A solo round boots directly into gameplay. The player can walk/look/sprint, toggle the flashlight, pick up one fuse at a time, recover the security keycard, install fuses into three breakers, survive blackouts and hunter surges, unlock extraction, and either escape or get caught. The in-game HUD shows objective state, stamina, flashlight charge, threat, context-sensitive USE prompts, event banners, and an explicit win/loss screen with retry.

## Core loop

1. Tap `PLAY SOLO` from the launcher.
2. Explore the facility and recover three physical fuses plus the security keycard.
3. Carry one fuse at a time to an unpowered breaker and hold USE to restore it.
4. Avoid the authoritative hunter; sprint and interaction noise can reveal you.
5. Survive periodic blackouts and the surge triggered by restoring power.
6. After all three breakers and the keycard are complete, reach the north extraction door and escape.

## Architecture

- `shared/` — game rules, local solo runtime, objective/director state, facility geometry, snapshots and Protocol v3.
- `android/` — standalone first-person game, main menu, optional multiplayer lobby, OpenGL ES presentation.
- `server/` — optional Java 17 TCP authority for experimental 1–4 player multiplayer.
- `tests/` — standalone runtime, simulation, protocol, prediction/interpolation and localhost multiplayer smoke tests.

Single-player and multiplayer intentionally use the same `GameSimulation` rules. Solo does not fake or bypass the game: it owns an in-process authority and advances the same 20 Hz simulation without sockets.

## Multiplayer

`MULTIPLAYER · EXPERIMENTAL` opens the server lobby. That path still requires a reachable Nightshift authority server and keeps Protocol v3 prediction/reconciliation/reconnect behavior. Multiplayer is no longer required to launch or play the APK.

## Evidence boundary

CI proves the standalone runtime boot/step contract, shared game tests, server tests, Android compilation, package/version identity, launcher source contract and APK generation. It does **not** prove real-phone control feel, final 3D art quality, frame pacing, thermal/power behavior, audio quality, or real WAN multiplayer quality. Those require physical-device/runtime testing.
