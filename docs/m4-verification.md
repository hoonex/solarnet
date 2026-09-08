# M4 verification scope

SolarNet M4 adds the room/lobby lifecycle on top of the existing transport and turn-session layers.

CI proves:

- the portable runtime compiles;
- existing multiplayer smoke tests still pass;
- state integrity/resync tests still pass;
- room join/compatibility/ready/start lifecycle tests pass;
- the Android Nearby bridge still compiles against the real Play Services Nearby dependency.

Not proven by CI:

- real two-phone Nearby discovery and connection UX;
- OEM-specific Android radio behavior;
- runtime permission dialogs on a physical device;
- thermal and battery behavior;
- reconnect after an actual process kill or radio interruption.
