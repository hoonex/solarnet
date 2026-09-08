# Android Nearby transport

SolarNet 0.2 adds a Google Nearby Connections adapter without changing the turn/session protocol.

## Topology

The current game architecture is host-authoritative, so `NearbyConnectionStrategy.Star` is the default: one host advertises and multiple clients discover/connect to that host. `Cluster` remains available for future mesh-style games, and `PointToPoint` for strict two-device sessions. Both sides must use the same strategy and service ID.

## Security boundary

Do not auto-accept a connection request. Google Nearby gives both devices the same short authentication digits. Show those digits to both players and call `AcceptConnectionAsync(endpointId)` only after they confirm the values match. Call `RejectConnectionAsync` otherwise.

After Nearby reports the physical connection as established, SolarNet sends a private transport HELLO that maps the ephemeral Nearby endpoint ID to the durable SolarNet peer ID. Game/session packets are then wrapped with that peer ID. Conflicting peer claims are disconnected instead of silently replacing an existing mapping.

## Basic wiring

```csharp
using SolarNet.Nearby;
using SolarNet.Nearby.Android;

var adapter = new AndroidNearbyAdapter();
var options = new NearbyTransportOptions(
    serviceId: "com.yourstudio.yourgame",
    endpointName: playerDisplayName,
    role: isHost ? NearbyConnectionRole.Advertiser : NearbyConnectionRole.Discoverer,
    strategy: NearbyConnectionStrategy.Star);

var transport = new NearbyTransport(playerPeerId, adapter, options);

transport.EndpointDiscovered += endpoint =>
{
    // Client UI: show endpoint.EndpointName, then connect when the player selects it.
};

transport.ConnectionVerificationRequired += request =>
{
    // UI: show request.AuthenticationDigits on both phones.
    // After the players confirm they match:
    // await transport.AcceptConnectionAsync(request.EndpointId);
};

transport.PeerConnected += peerId =>
{
    // SolarNet peer handshake completed. It is now safe to create/use the game session.
};

await transport.StartAsync();
```

For a discoverer, call `RequestConnectionAsync(endpoint.EndpointId)` after the player chooses a discovered host. Discovery stops after the first physical connection by default because continued discovery is radio-heavy and can make established connections less reliable. Set `stopDiscoveryAfterFirstConnection: false` only when the product actually needs continued discovery.

## Android permissions

The package Android library declares the permissions required through Android 16 / target SDK 36. Runtime permission prompts remain owned by the game UI; SolarNet does not pop system permission dialogs on its own.

Use `NearbyAndroidPermissions.GetRequiredRuntimePermissions(deviceSdkInt, targetSdkInt)` to compute the dangerous permissions relevant to the current device, then request them with your app's normal Unity Android permission flow before calling `StartAsync()`.

For Android 17 devices when the app **targets SDK 37 or higher**, add this to the application's own Android manifest and request it at runtime:

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

Do not request `ACCESS_LOCAL_NETWORK` while the app still targets SDK 36 or lower; Android grants legacy local-network access implicitly for those targets.

If any required runtime permission is denied, advertising/discovery should be treated as unavailable and the UI should explain that local multiplayer cannot start.

## Android dependency

The `.androidlib` pins `com.google.android.gms:play-services-nearby:19.4.0`, the latest version confirmed by Google's Play services release notes when SolarNet 0.2 was implemented. The Java bridge uses `ConnectionsClient`, `Payload.Type.BYTES`, and the human-verifiable authentication digits API.

SolarNet packets are intentionally small. The transport enforces Google's `ConnectionsClient.MAX_BYTES_DATA_SIZE` limit (1,047,552 bytes) before sending a BYTES envelope.

## Verification scope

CI compiles the exact `.androidlib` Java source against Android 36 using Unity 6.0's documented Gradle/AGP compatibility line and resolves the real Google Nearby dependency. Core smoke tests run the same `NearbyTransport` logic against a fake platform adapter with endpoint IDs intentionally different from SolarNet peer IDs.

CI does **not** prove radio behavior, permission prompts, OEM-specific Bluetooth/Wi-Fi behavior, real-device UI, thermal behavior, or reconnect behavior. A two-phone Android run remains required before calling M2 device-verified.
