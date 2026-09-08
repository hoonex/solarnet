# Unity Android diagnostics

SolarNet 0.6 adds a Unity Package Manager sample intended to turn physical two-phone testing into a reproducible protocol check rather than an ad-hoc game prototype.

The sample exposes:

- Host / client role selection;
- Nearby Connections or explicit Bluetooth Classic RFCOMM;
- runtime Android permission requests;
- Nearby room discovery and authentication-code accept/reject;
- paired Bluetooth device selection;
- logical SolarNet peer handshake status;
- room roster, ready state, and host start;
- alternating authoritative diagnostic turns;
- an on-screen event log.

## Unity assembly correction

`SolarNet.Runtime.asmdef` permits Unity engine references because Android player adapters use `AndroidJavaObject`, `AndroidJavaClass`, and `AndroidJavaProxy`. The portable core remains free of Unity dependencies at the source/API boundary; only the Android bridge requires UnityEngine at player compile time.

CI now has a `UNITY_ANDROID` compile-surface project with minimal Unity Java bridge stubs. It does not replace a real Unity build, but it ensures Android-only C# source is parsed and type-checked on every PR instead of being hidden behind editor/platform preprocessor symbols.

## Remaining evidence boundary

Repository CI still cannot prove radio behavior. The Diagnostics sample is specifically the next evidence tool for testing two real phones: permission dialogs, pairing/discovery, OEM Bluetooth behavior, reconnects, latency, and longer soak/thermal behavior.
