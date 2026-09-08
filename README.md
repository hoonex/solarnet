# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine keeps gameplay independent from the connection technology: Google Nearby Connections, Bluetooth, LAN, relay, or another transport can implement the same `ISolarTransport` contract.

## Current milestone

`0.3` now has three layers working together:

- host-authoritative turn ordering and duplicate/out-of-order protection;
- Google Nearby advertiser/discoverer transport for Unity Android;
- explicit human authentication-code confirmation before accepting a Nearby connection;
- Nearby endpoint-ID -> SolarNet peer-ID handshake;
- optional deterministic `ISolarGameStateMachine` for game-rule validation;
- SHA-256 state digests attached to authoritative commits;
- bounded action journal for missed-turn replay;
- automatic gap/digest mismatch detection;
- authoritative snapshot fallback with pre/post-restore digest verification;
- explicit reconnect `RequestResyncAsync()` API;
- .NET protocol/E2E CI plus real Android/Play Services library compile CI.

## Repository layout

```text
Packages/com.hoonex.solarnet/       Unity package / source of truth
  Runtime/Protocol/                 game/session wire packet + codec
  Runtime/Transport/                base transport abstraction + loopback
  Runtime/Nearby/                   platform-neutral Nearby transport logic
  Runtime/Android/                  Unity Android adapter
  Runtime/State/                    reducer, digest, journal, snapshots/resync
  Runtime/Turns/                    authoritative turn state machine
  Runtime/Session/                  host/client session orchestration
  Plugins/Android/                  Google Nearby .androidlib bridge
src/SolarNet.Core/                  .NET build wrapper for portable runtime source
tests/SolarNet.Core.SmokeTests/     multiplayer/transport tests
tests/SolarNet.StateIntegrity.SmokeTests/ deterministic desync/recovery tests
android-smoke/                      Gradle harness for real Android/Play Services compile
docs/nearby-android.md              Android integration and permission contract
docs/state-integrity.md             deterministic game-state contract
docs/architecture.md                protocol and roadmap
```

## Unity

Add this repository as a Git package, or copy `Packages/com.hoonex.solarnet` into a Unity project. Portable networking is in `SolarNet.Runtime`; the Android bridge is in `SolarNet.Android`.

For local Android multiplayer, read `docs/nearby-android.md`. For deterministic game state, replay, and reconnect recovery, read `docs/state-integrity.md`.

## Transport / engine roadmap

1. `LoopbackTransport` — implemented and tested.
2. Android Google Nearby Connections — implemented; two-device radio verification pending.
3. Deterministic state integrity / replay / snapshot resync — implemented and tested in-memory.
4. Lobby/session UX + room lifecycle + compatibility negotiation.
5. Bluetooth Classic/BLE transport experiments where platform constraints make sense.
6. LAN transport.
7. Optional relay/internet transport without changing game/session APIs.

The next engine milestone is M4: a real room/lobby lifecycle around discovery, ready state, reconnect identity, and version compatibility rather than making each game assemble those pieces manually.
