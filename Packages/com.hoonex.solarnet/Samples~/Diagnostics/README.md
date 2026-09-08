# SolarNet Diagnostics sample

This sample is a deliberately small two-phone Android test harness for the real SolarNet package. It can exercise either Google Nearby Connections or explicit Bluetooth Classic RFCOMM, then continue through the room/ready/start protocol and exchange authoritative turn packets.

## Import

In Unity Package Manager, select SolarNet and import **SolarNet Diagnostics** from the Samples section. Create an empty GameObject and add the `SolarNetDiagnostics` component.

## Build

1. Switch the Unity project to Android.
2. Keep Internet access optional; Bluetooth Classic does not need it. Nearby Connections itself is offline but uses Google Play services on Android.
3. Build the same project to two Android phones.
4. For Bluetooth Classic, pair both phones in Android Bluetooth settings before opening the app.
5. On each phone tap **Request Android permissions**, approve the dialogs, then tap Start.

## Bluetooth Classic test

- Phone A: Host + Bluetooth Classic + Start host.
- Phone B: Client + Bluetooth Classic + Start client.
- Phone B: Refresh paired devices, then select Phone A.
- Wait until both devices show a SolarNet peer connection.
- Both players toggle Ready.
- Host starts the game session.
- The active player taps **Send diagnostic turn**. Turns alternate between the two devices.

## Nearby test

- Phone A: Host + Nearby Connections + Start host.
- Phone B: Client + Nearby Connections + Start client.
- Phone B selects the discovered SolarNet Diagnostics room.
- Both devices compare the authentication digits and tap Accept only if they match.
- Ready, start, and exchange diagnostic turns as above.

The on-screen event log is intended to make screenshots and bug reports useful. Physical radio behavior is not proven by repository CI; this sample is the device evidence harness.
