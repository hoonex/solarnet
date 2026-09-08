# Android Bluetooth Classic transport

SolarNet 0.5 adds a Bluetooth-only Android transport for paired phones. It uses secure RFCOMM sockets and does not require Wi-Fi, Internet access, Google Nearby, or a game server.

## Why paired-device only first

The initial transport deliberately does not perform Bluetooth discovery. The game lists devices already paired in Android settings and lets the player select the host. This keeps the Android 12+ runtime permission surface to `BLUETOOTH_CONNECT` instead of adding scan/location behavior.

## Connection topology

```text
Host phone
  BluetoothServerSocket / SolarNet UUID
       |-- RFCOMM socket -> client A
       |-- RFCOMM socket -> client B
       `-- RFCOMM socket -> client C
```

The server keeps accepting sockets, so a turn-based room can have multiple clients. Every socket is length-framed in the Android bridge because RFCOMM exposes a byte stream rather than message boundaries.

## Identity

A Bluetooth MAC address or native connection ID is never used as the SolarNet player identity. Immediately after the socket connects, both sides exchange a SolarNet hello envelope containing the logical `peerId`. The transport rejects a connection that changes identity, duplicates an already-connected peer ID, or claims the local peer ID.

## Unity flow

```csharp
var adapter = new AndroidBluetoothClassicAdapter();
var transport = new BluetoothClassicTransport(
    localPeerId,
    adapter,
    new BluetoothClassicTransportOptions(isHost ? BluetoothClassicRole.Server : BluetoothClassicRole.Client));

await transport.StartAsync();

if (!isHost)
{
    var devices = transport.GetBondedDevices();
    await transport.ConnectAsync(devices[0].Address);
}
```

After `PeerConnected`, the same transport can back `SolarRoomSession` and then `SolarTurnSession` without a reconnect.

## Android permissions

The package manifest declares legacy `BLUETOOTH` through API 30 and `BLUETOOTH_CONNECT` for modern Android. On Android 12/API 31 and later, request `BluetoothClassicAndroidPermissions.GetRequiredRuntimePermissions(apiLevel)` before listing bonded devices, hosting, or connecting.

No `BLUETOOTH_SCAN` permission is required by this paired-device-only implementation.

## Security and scope

SolarNet uses `listenUsingRfcommWithServiceRecord` and `createRfcommSocketToServiceRecord`, the secure/authenticated RFCOMM pair. Devices therefore need a valid Bluetooth pairing/link key. The SolarNet peer handshake is an application identity mapping layer; it is not a replacement for cryptographic user authentication.

CI compiles the Android bridge and executes an in-memory Bluetooth adapter test that carries authoritative turn packets. Physical radio behavior, vendor-specific Bluetooth stacks, permission UI, pairing UX, range, thermal behavior, and multi-phone soak testing remain device evidence boundaries.
