# SolarNet

SolarNet is a local-first multiplayer engine for turn-based games.

The first target is a 2D mobile game where nearby devices can play together without a traditional game server. The engine keeps gameplay independent from the connection technology: Bluetooth, Nearby Connections, LAN, relay, or another transport can implement the same `ISolarTransport` contract.

## Current milestone

`0.1` establishes the multiplayer core before adding Android Nearby/Bluetooth adapters:

- host-authoritative turn ordering;
- monotonic per-peer action sequences for duplicate/out-of-order protection;
- deterministic binary packet framing with protocol versioning;
- session and sender identity validation;
- transport abstraction independent from Unity/game rules;
- in-memory loopback transport for deterministic tests;
- end-to-end host/client turn submission and commit/rejection flow;
- Unity Package Manager layout plus .NET CI using the exact same runtime source.

## Repository layout

```text
Packages/com.hoonex.solarnet/       Unity package / source of truth
  Runtime/Protocol/                 wire packet + codec
  Runtime/Transport/                transport abstraction + loopback
  Runtime/Turns/                    authoritative turn state machine
  Runtime/Session/                  host/client session orchestration
src/SolarNet.Core/                  .NET build wrapper for package source
tests/SolarNet.Core.SmokeTests/     dependency-free executable tests
docs/architecture.md                protocol and roadmap
```

## Unity

Add this repository as a Git package, or copy `Packages/com.hoonex.solarnet` into a Unity project. The runtime assembly is `SolarNet.Runtime`.

## Transport roadmap

1. `LoopbackTransport` — implemented; deterministic development/test transport.
2. Android Nearby Connections transport — next target for nearby phone-to-phone multiplayer.
3. Bluetooth Classic/BLE transport experiments where platform constraints make sense.
4. LAN transport.
5. Optional relay/internet transport without changing game/session APIs.

SolarNet intentionally does not put game rules inside the networking layer. A game submits an opaque action kind + payload; the host owns turn authority and later versions will add game-state digest/snapshot hooks for desync detection and resync.
