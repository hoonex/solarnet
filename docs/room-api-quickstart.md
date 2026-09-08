# Room API quickstart

SolarNet rooms run on the same `ISolarTransport` instance that later carries the game session.

Typical flow:

```text
Nearby connected
  -> host/client SolarRoomSession.StartAsync()
  -> client JoinAsync()
  -> both receive roster state
  -> SetReadyAsync(true)
  -> host StartGameAsync()
  -> both receive the same gameSessionId
  -> construct SolarTurnSession on the existing transport
```

The host owns room membership and game start authority. Clients cannot start a room. Compatibility is checked before a client becomes a room member. The room session and the later turn session use different SolarNet session IDs, so both protocols can share one transport without reconnecting.
