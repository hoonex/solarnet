# Nightshift

Nightshift is a clean-slate 1–4 player cooperative first-person horror prototype. The retired Solar Arcade / SolarNet Bluetooth/Nearby product paths are not part of the current product.

## Core loop

1. Join or create a room on a Nightshift authority server.
2. Ready up; the room leader starts the match.
3. Restore three breaker stations inside a dark facility.
4. Avoid a server-controlled hunter that reacts to line of sight, sprint noise and interaction noise.
5. Revive downed teammates, unlock extraction, and escape.

## Architecture

- `shared/` — game rules, facility geometry, canonical player locomotion, client prediction/interpolation models, snapshots and Protocol v2.
- `server/` — Java 17 TCP authority, room registry, 20 Hz simulation and 10 Hz snapshots.
- `android/` — Android first-person OpenGL ES client.
- `tests/` — rule/protocol/prediction/interpolation and real localhost TCP smoke tests.
- `docs/` — product, networking and milestone contracts.

Nightshift 0.2.0 keeps game truth on the server while reducing perceived input and snapshot latency. Local movement is predicted with the same locomotion step used by the authority, snapshots acknowledge the last processed input sequence, and unacknowledged inputs are replayed during reconciliation. Remote actors are rendered through a one-snapshot interpolation buffer and are never extrapolated beyond the newest authoritative snapshot.

Transient disconnects reserve the existing player slot for 10 seconds. The Android client performs a bounded reconnect sequence and uses Protocol v2 `RESUME` with the existing room/player/token. An explicit leave still removes the player immediately.

## Run the authority

```bash
mkdir -p out
javac --release 17 -d out $(find shared/src/main/java server/src/main/java -name '*.java')
printf 'Main-Class: com.hoonex.nightshift.server.NightshiftServer\n' > /tmp/nightshift-manifest.mf
jar cfm nightshift-server.jar /tmp/nightshift-manifest.mf -C out .
java -jar nightshift-server.jar 46000
```

For Internet play, run the authority on a publicly reachable host and expose the chosen TCP port. TLS, authenticated matchmaking, relay fallback and hosted production infrastructure remain later milestones.

## Evidence boundary

CI can prove Java compilation, deterministic rule/protocol/prediction/interpolation tests, localhost TCP create/join/resume behavior, Android compilation, package/permission identity and artifact generation. It does **not** prove real-phone touch/render quality, actual WAN latency/jitter/loss behavior, performance, thermal behavior or battery use.
