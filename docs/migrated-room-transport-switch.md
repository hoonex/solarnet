# Migrated room transport switching

SolarNet 0.13 connects the deterministic host-migration plan to room authority and transport lifecycle without changing the turn wire protocol.

## Room authority bootstrap

`SolarRoomMigration.CreateSession(...)` rebuilds the room around an existing `SolarHostMigrationPlan`:

- the elected successor becomes the new room host;
- original player slots and display names are preserved;
- the dead former host remains in its original slot but starts disconnected;
- the successor starts connected;
- the room remains in `Playing`;
- the room advertises `plan.NextGameSessionId` as the active game epoch;
- non-successors create ordinary client room sessions configured to trust only `plan.SuccessorPeerId`.

A migrated host is not required to occupy slot zero. Slot order is player identity/order metadata; room authority identity is `HostPeerId`.

## Nearby role switch

`NearbyMigrationTransportSwitch.SwitchAsync(...)` performs a bounded topology rebuild:

1. stop the old `NearbyTransport` and its physical topology;
2. reuse the same `INearbyPeerAdapter`;
3. if the local peer is the elected successor, start a new `Advertiser` transport with the existing room advertisement metadata;
4. otherwise start a new `Discoverer` transport using the local display name;
5. a survivor may call `RequestSuccessorConnectionAsync(...)` only for an endpoint advertising the expected room;
6. after the SolarNet hello handshake, `IsExpectedAuthority(peerId)` must match `plan.SuccessorPeerId` before the connection is trusted as migrated authority.

Nearby authentication/verification is intentionally not bypassed. Applications should keep the existing verification-code UX when the platform requests it.

## Bluetooth Classic role switch

`BluetoothClassicMigrationTransportSwitch.SwitchAsync(...)` performs the equivalent RFCOMM role rebuild:

- elected successor: old topology stops, then a new RFCOMM `Server` starts;
- survivor: old topology stops, then a new RFCOMM `Client` starts;
- when the successor device address is already resolved, the client reconnects immediately;
- when the address is unknown, the result reports `RequiresSuccessorDeviceAddress=true` and does not guess an address.

This limitation is deliberate. In a Bluetooth star topology, two former clients may never have learned each other's Android Bluetooth addresses. A future address-exchange or migration-generation layer can close that gap without weakening identity guarantees.

## Evidence boundary

CI proves:

- a slot-1 successor can own migrated room authority while slot zero remains the former host;
- another survivor rejoins that room and accepts the new authority;
- the room switches to the planner-derived game session epoch;
- the promoted host serves an authoritative snapshot and both peers continue turn execution;
- Nearby switches the successor to advertiser and survivors to discoverer, and migrated-room discovery filtering works;
- Bluetooth switches the successor to server and survivors to client, with explicit device-address handling.

CI and the Android library/Probe APK builds do **not** prove physical-radio host migration on real phones. Real-device Nearby/Bluetooth migration soak remains required before claiming device-runtime reliability.
