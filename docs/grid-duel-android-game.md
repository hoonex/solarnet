# Grid Duel Android game

Grid Duel is now available as a standalone Android game app in `android-smoke/grid-duel-game`.

This module is intentionally separate from the transport Probe. It is a playable product surface, not a diagnostics UI.

## Play modes

- **AI practice** — SUN is controlled by the player, MOON uses a deterministic pursuit/attack policy.
- **Local 2P** — SUN and MOON alternate turns on one phone.
- **Bluetooth match** — host is SUN, client is MOON. The two phones must be paired in Android Bluetooth settings first.
- **Nearby match** — host advertises, client discovers, both users compare the authentication digits, and only then accept the connection.

## Rules

- 5×5 board.
- SUN starts on the left; MOON starts on the right.
- Each side starts with 3 HP.
- One action per turn.
- Tap one orthogonally adjacent highlighted cell.
- Empty highlighted cell: move.
- Highlighted opponent cell: attack for 1 damage.
- First side to reduce the opponent to 0 HP wins.

## Network authority

The Android game uses a compact host-authoritative game protocol for the standalone app:

- the host owns the canonical state;
- the client sends a requested target cell for its current turn;
- the host validates and applies that action;
- the host returns a complete canonical state frame;
- the client does not mutate its local board before the host state arrives.

This standalone Java game app reuses SolarNet's Android Bluetooth Classic and Nearby bridge modules. It does **not** claim to embed the full C# `SolarTurnSession` runtime used by the Unity package sample. The Unity Grid Duel sample remains the reference implementation for the complete SolarNet session/recovery stack.

## Build

CI builds:

```text
android-smoke:grid-duel-game:assembleDebug
```

and uploads the artifact as:

```text
grid-duel-android-game-apk
```

The debug APK package is `com.hoonex.solarnet.gridduel`.

## Evidence boundary

CI proves deterministic game-rule tests, Java/Android compilation, manifest/package identity, checksum generation, and APK artifact generation. It does not prove touch ergonomics or physical Bluetooth/Nearby gameplay until the APK is installed and exercised on phones.
