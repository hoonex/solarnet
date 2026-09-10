# Nightshift

Nightshift is a standalone-first Android first-person horror game prototype with an optional experimental 1–4 player authority-server mode.

## 0.5.0 game-feel pass

0.5.0 focuses on the part that earlier builds were missing: the APK must present itself like a game, not like a networking demo.

The solo path still starts without a server, room code or second device, but the presentation layer is substantially richer:

- an enclosed industrial facility with perimeter walls, panel seams, service pipes/ducts, support columns, lockers, crates and extraction hazard markings;
- objective props are modeled as recognizable fuse units, breaker cabinets, a security console/keycard and a mechanical extraction doorway;
- the hunter is a multi-part animated creature silhouette with long limbs, head/jaw separation and emissive eyes instead of two red boxes;
- surface-facing flashlight shading, deeper fog, blackout lighting, low-battery flashlight flicker, sprint FOV, head bob and hunt-surge camera shake;
- screen-space vignette, threat pulse, scanlines and a movement-sensitive reticle;
- procedural in-APK ambience, footsteps, heartbeat, pickup, breaker, danger and extraction cues plus event haptics;
- a more game-like launch screen and in-game control/HUD styling.

All sound is synthesized locally by the Android client at runtime, so this pass does not add external audio licenses or asset-download dependencies.

## Standalone core loop

1. Launch the APK and choose **BEGIN SHIFT**.
2. Drag on the left side to move and the right side to look.
3. Search the facility for three fuses and the security keycard.
4. Carry one fuse at a time to an unpowered breaker and hold **USE** to install it.
5. Avoid the hunter; sprinting and interactions make you easier to find.
6. Survive periodic blackouts and the hunt surge caused by restoring power.
7. After all three breakers and the keycard are complete, reach the north extraction door and escape.

## Architecture

- `shared/` — authoritative game rules, objectives/director, facility collision, prediction/interpolation, snapshots and Protocol v3.
- `server/` — Java 17 TCP authority for experimental multiplayer.
- `android/` — standalone game runtime, first-person OpenGL ES 2 renderer, Android UI/audio and multiplayer client.
- `tests/` — standalone/runtime/rule/protocol/prediction/interpolation and localhost TCP smoke tests.

Solo uses the same authoritative `GameSimulation` in-process at 20 Hz. Multiplayer keeps game truth on the server and retains the existing prediction/reconciliation and reconnect lease.

## Evidence boundary

CI verifies shared/server compilation, standalone runtime behavior, existing multiplayer regression tests, Android compilation, package/version identity, absence of Bluetooth permissions and APK generation. CI cannot prove how 0.5.0 actually looks, sounds or controls on a physical phone. Real-device rendering, touch feel, audio output, frame pacing, thermal behavior and battery use remain device-unverified until exercised on hardware.
