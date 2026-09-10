# Nightshift

Nightshift is a clean-slate 1–4 player cooperative first-person horror prototype. The retired Solar Arcade / SolarNet Bluetooth/Nearby product paths are not part of the current product.

## 0.3.0 vertical slice

The team now has to recover three physical fuses, install one fuse into each breaker, recover the shared security keycard, and then reach the extraction door. A disconnected player keeps a carried fuse during the reconnect lease; if the lease expires and the player is removed, that fuse returns to its spawn so the round cannot become unwinnable.

The authority also owns deterministic horror pacing. Blackouts begin after play has actually started, repeat on a fixed schedule, and breaker activation creates a six-second hunter surge. Snapshot state carries the blackout/threat/surge/objective truth so every client presents the same event.

The Android renderer now has a directional flashlight beam, distance fog, dim ambient light, emergency-light blackouts, world-visible fuses/keycard, powered breaker feedback, a locked/unlocked extraction door, and a stronger hunter silhouette. HUD/event banners expose carried fuse, keycard, threat, blackout, hunt surge and major server events.

## Multiplayer architecture

- `shared/` — authoritative game rules, objective/director state, facility geometry, prediction/interpolation, snapshots and Protocol v3.
- `server/` — Java 17 TCP authority, room registry, 20 Hz simulation and 10 Hz snapshots.
- `android/` — Android first-person OpenGL ES client.
- `tests/` — rule/protocol/prediction/interpolation and real localhost TCP smoke tests.

Nightshift keeps game truth on the server. Local movement prediction only improves presentation; snapshots acknowledge accepted input and reconciliation remains the correctness boundary. Remote actors are interpolated without extrapolation. Transient disconnects retain the same room/player/token for the existing 10-second resume lease.

## Core loop

1. Join/create a room and ready up.
2. Search separate facility zones for three fuses and the security keycard.
3. Carry one fuse at a time to an unpowered breaker.
4. Expect a hunter surge after power restoration and periodic facility blackouts.
5. Revive downed teammates.
6. After all three breakers and the keycard are complete, escape through the north extraction door.

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

CI can prove Java compilation, objective/director/protocol/prediction/interpolation tests, localhost TCP create/join/resume behavior, Android compilation, package/permission identity and artifact generation. CI does **not** prove real-phone touch feel, final visual quality, actual WAN latency/jitter/loss behavior, performance, thermal behavior, audio quality or battery use.
