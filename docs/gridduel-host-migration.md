# Grid Duel automatic host migration

SolarNet 0.14 wires the deterministic host-migration primitives into the `GridDuelNetworkedDemo` Unity sample.

## Trigger and safety gate

Grid Duel is a two-player sample. During a `Playing` room, loss of the authenticated peer link causes each still-running process to evaluate the same deterministic authority rotation.

Before any topology change, `GridDuelMigrationWorkflow.Prepare(...)` captures a `SolarAuthorityCheckpoint`. `SolarAuthorityPromotion.Capture(...)` compares the local canonical game-state bytes with the turn session's last authoritative digest. If they do not match, preparation throws and the sample does not promote that local state; the ordinary reconnecting path remains active instead.

Successor selection still uses stable room slot order rather than transient link observations. For the initial Grid Duel room, slot 1 is therefore the deterministic successor to the original slot-0 host. This deliberately trades fallback liveness for split-brain resistance: if the elected successor process is actually gone, another survivor is not silently elected from a different local view.

## Topology and authority rotation

After the safety gate passes:

1. the source room records the peer link loss and detaches;
2. the source game session stops, fencing the previous authority epoch;
3. the current Nearby or Bluetooth Classic topology stops;
4. the elected successor becomes Nearby advertiser or RFCOMM server;
5. the non-successor becomes Nearby discoverer or RFCOMM client;
6. `SolarRoomMigration.CreateSession(...)` preserves the original player slots while moving room authority to the successor;
7. the successor creates a promoted `SolarTurnSession` from the checkpoint;
8. the non-successor rejoins the migrated room and requests an authoritative state snapshot before play continues.

`NearbyMigrationTransportSwitch` and `BluetoothClassicMigrationTransportSwitch` expose a pre-start configuration callback. Grid Duel uses it to subscribe to discovery, verification, connection, disconnection, and fault events before the new transport can start or perform an immediate Bluetooth connection. This avoids losing synchronous lifecycle events at the role-switch boundary.

## Nearby behavior

A non-successor scans for the migrated room advertisement and automatically requests a matching endpoint. Endpoint metadata is only discovery metadata, not authority proof. The sample still requires the user to compare and accept Nearby authentication digits on both phones, and after the SolarNet handshake it requires the remote logical peer ID to equal `SolarHostMigrationPlan.SuccessorPeerId`. A different peer is disconnected.

## Bluetooth Classic behavior

`BluetoothClassicTransport` associates the Android device address delivered by the RFCOMM adapter with the SolarNet peer ID established by the SolarNet hello handshake. The last authenticated peer-to-device address survives a link disconnect long enough for the caller to capture it before transport shutdown.

Grid Duel resolves the elected successor's last authenticated address before switching roles. If available, the non-successor can immediately reconnect as an RFCOMM client after the successor becomes server. If no authenticated address is known, the sample falls back to explicit paired-device selection. It never chooses an arbitrary bonded device as the successor. The logical SolarNet peer-ID fence still applies after connection.

## Durability boundary

This is deterministic authority recovery, not a consensus protocol. In a two-player game there is no quorum that can prove the elected successor received every host-local commit before the link failed. The successor can preserve the latest authoritative state that it actually observed. A final commit that existed only on the old host and was never replicated to the successor can therefore be lost.

For that reason the 0.14 contract is **recovery to the latest replicated authoritative state**, not guaranteed preservation of unreplicated in-flight host state. A future durability barrier can require successor acknowledgement before a host commit is considered migration-durable.

## Process-death boundary

The automatic path assumes the migration participant is still running when the link-loss event is handled. Existing ordinary client process-resume support remains intact, but the new migration plan/checkpoint/new authority epoch is not yet persisted as a crash-recovery record. Persisting that epoch so an app can die during/after migration and restart into the correct role is the next recovery milestone.

## Evidence

CI verifies:

- the same `GridDuelMigrationWorkflow` used by the Unity sample refuses diverged local state;
- synchronized Grid Duel peers derive the same successor, checkpoint digest, and new session epoch;
- slot-1 authority restores the former host with a snapshot and canonical turns continue;
- transport configuration occurs before new Nearby discovery or Bluetooth start/connect boundaries;
- Bluetooth peer/device identity remains available after disconnect;
- the Unity Grid Duel surface compiles with the automatic migration wiring;
- the existing recovery, chaos, room, migration, Grid Duel, and Android transport gates remain in the normal workflow.

CI does not verify physical multi-phone radio timing, OEM-specific Bluetooth behavior, Android background/process-death behavior during migration, thermal behavior, or power use. Those require real-device runs.
