# Room / lobby lifecycle

SolarNet 0.4 adds a transport-agnostic room protocol so games do not have to rebuild join/ready/start/version checks around every transport.

## Lifecycle

```text
Nearby/LAN peer connected
        |
        v
RoomJoinRequest
        |
 host checks core protocol + game compatibility key + capacity
        |
        v
authoritative RoomState snapshots
        |
players toggle Ready
        |
all connected players ready
        |
host StartGameAsync()
        |
new gameSessionId + ordered player IDs
        |
SolarTurnSession uses the SAME transport
```

The room ID and game session ID are deliberately different. `SolarRoomSession` stays subscribed to the transport while the turn session runs, but it ignores game packets because their `sessionId` is different. No disconnect/reconnect handoff is required at game start.

## Transport ownership

`SolarRoomSession.Attach()` subscribes to an already-managed `ISolarTransport`; it does **not** start or stop the radio/network transport. This lets a Nearby discovery/verification UI own the physical connection lifecycle and then hand the same live transport into both room and game sessions.

## Compatibility

Every join request carries:

- SolarNet core protocol version;
- a game-defined `compatibilityKey`;
- player display name.

Use a compatibility key that changes when two game builds cannot safely play together, for example `cards-v3-ruleset-7`. The host rejects incompatible clients before adding them to the roster.

The SolarNet engine semantic version is exposed through `SolarNetVersion.EngineVersion`; room compatibility is not tied to exact engine patch versions when the wire protocol/game contract is still compatible.

## Ready / start

The host owns the authoritative room roster and monotonic room revision. Clients do not optimistically change ready state: they request it and wait for the host's next `RoomState`.

`CanStart` is true only when the room is in Lobby, at least two players exist, and every roster player is connected and ready. `StartGameAsync()` produces a `SolarGameStartInfo` containing the new game session ID and stable slot-ordered peer IDs. The host can call `CreateHostTurnCoordinator()` directly on that object.

## Nearby discovery metadata

A Nearby host can advertise a human-readable room name plus machine-readable room metadata in its endpoint name:

```csharp
var endpointName = NearbyRoomAdvertisementCodec.Encode(
    roomId,
    "Lunch Break Match",
    compatibilityKey);
```

Clients can parse `EndpointDiscovered.EndpointName` with `TryDecode` before requesting a connection, so incompatible rooms can be filtered in the discovery UI.

After the SolarNet Nearby peer handshake completes, `SolarNearbyRoomBridge` can feed `PeerConnected` / `PeerDisconnected` events into the room session automatically.

## Disconnect policy

0.4 deliberately does **not** perform silent host migration. Host authority owns turn ordering and state recovery, so electing a new host without a proven authority-transfer protocol would risk split-brain state.

Clients choose one explicit host-disconnect policy:

- `CloseRoom`: terminate the room locally when the host disappears;
- `WaitForReconnect`: enter `Reconnecting`; if the same host peer ID returns, re-send the join request and recover the authoritative room snapshot.

Non-host players that disconnect remain in a playing roster as disconnected identities so slot order does not silently change mid-game. In the lobby, a voluntary leave removes the slot and later joins reuse the lowest free slot.

True host migration is intentionally deferred until SolarNet can transfer coordinator state, journal ownership, and a fenced authority epoch rather than just picking another phone.
