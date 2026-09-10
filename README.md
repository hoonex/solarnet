# Nightshift

Nightshift is a clean-slate 1–4 player cooperative horror game prototype. The previous Solar Arcade / SolarNet transport demo product has been retired on the reboot branch; the new product has no Bluetooth or Nearby transport path.

## Core loop

1. Join or create a room on a public/LAN Nightshift authority server.
2. Ready up; the room leader starts the match.
3. Restore three breaker stations inside a dark facility.
4. Avoid a server-controlled hunter that reacts to line of sight, sprint noise and interaction noise.
5. Revive downed teammates, unlock extraction, and escape.

## Architecture

- `shared/` — authoritative game rules, facility geometry, snapshots and binary protocol. No Android dependency.
- `server/` — Java 17 TCP authority, room registry, fixed 20 Hz simulation, 10 Hz snapshots.
- `android/` — Android first-person OpenGL ES client. Sends input only; it does not own monster/objective truth.
- `tests/` — executable smoke tests for game rules, protocol round-trips and a real localhost two-client server session.
- `docs/` — product, networking and milestone contracts.

## Run the authority

```bash
mkdir -p out
javac --release 17 -d out $(find shared/src/main/java server/src/main/java -name '*.java')
printf 'Main-Class: com.hoonex.nightshift.server.NightshiftServer\n' > /tmp/nightshift-manifest.mf
jar cfm nightshift-server.jar /tmp/nightshift-manifest.mf -C out .
java -jar nightshift-server.jar 46000
```

The Android client connects to the server host/IP and port. For Internet play, run the authority on a publicly reachable host and expose TCP `46000` (or the chosen port). Production TLS, authentication, matchmaking service and relay fallback are later milestones; v0.1.0 intentionally proves the authoritative game loop before adding infrastructure layers.

## Evidence boundary

CI can prove Java compilation, deterministic rule/protocol smoke tests, a localhost two-client TCP session, Android compilation, package identity and APK generation. CI does **not** prove physical-device touch feel, rendering comfort, network quality over a real carrier/Wi-Fi path, performance, thermal behavior or battery use.
