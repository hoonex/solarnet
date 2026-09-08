# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine keeps gameplay independent from the connection technology: Google Nearby Connections, Bluetooth, LAN, relay, or another transport can implement the same `ISolarTransport` contract.

## Current milestone

`0.2` adds the first real Android transport layer on top of the verified `0.1` turn core:

- host-authoritative turn ordering and duplicate/out-of-order protection;
- deterministic game/session packet framing;
- transport-independent peer identity;
- Google Nearby advertiser/discoverer adapter for Unity Android;
- explicit human authentication-code confirmation before accepting a Nearby connection;
- Nearby endpoint-ID -> SolarNet peer-ID handshake with collision protection;
- BYTES payload transport with Google's size limit enforced;
- Android permission guidance through Android 17 target-SDK behavior;
- Android library compile CI plus platform-neutral Nearby E2E smoke tests.

## Repository layout

```text
Packages/com.hoonex.solarnet/       Unity package / source of truth
  Runtime/Protocol/                 game/session wire packet + codec
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   platform-neutral Nearby transport logic
  Runtime/Android/                  Unity Android adapter
  Runtime/Turns/                    authoritative turn state machine
  Runtime/Session/                  host/client session orchestration
  Plugins/Android/                  Google Nearby .androidlib bridge
src/SolarNet.Core/                  .NET build wrapper for portable runtime source
tests/SolarNet.Core.SmokeTests/     dependency-free executable tests
android-smoke/                      Gradle harness for real Android/Play Services compile
docs/nearby-android.md              Android integration and permission contract
docs/architecture.md                protocol and roadmap
```

## Unity

Add this repository as a Git package, or copy `Packages/com.hoonex.solarnet` into a Unity project. Portable networking is in `SolarNet.Runtime`; the Android bridge is in `SolarNet.Android`.

For Nearby integration, read `docs/nearby-android.md`. The game owns permission UI, nearby-host selection, and the authentication confirmation screen; the engine owns transport framing, identity mapping, and turn delivery.

## Transport roadmap

1. `LoopbackTransport` — implemented and tested.
2. Android Google Nearby Connections — implemented; two-device radio verification pending.
3. State integrity + reconnect journal.
4. Bluetooth Classic/BLE transport experiments where platform constraints make sense.
5. LAN transport.
6. Optional relay/internet transport without changing game/session APIs.

SolarNet intentionally does not put game rules inside the networking layer. A game submits an opaque action kind + payload; the host owns turn authority. The next engine milestone adds deterministic reducers, state digests, snapshots, and reconnect/resync.
