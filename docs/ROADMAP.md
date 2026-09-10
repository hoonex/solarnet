# Nightshift roadmap

## M0 — Reboot foundation — shipped in 0.1.0

- previous SolarNet/Solar Arcade/Bluetooth/Nearby product tree removed
- cooperative horror loop and server ownership defined
- shared facility/game simulation and binary protocol
- room-coded Java authority server
- real localhost two-client smoke
- first-person Android OpenGL client with INTERNET-only permission

## M1 — Real playable network feel

### 0.2.0 slice
- canonical player locomotion shared by authority and prediction
- server snapshots acknowledge the last accepted input sequence
- local movement prediction with authoritative reconciliation
- one-snapshot remote player/hunter interpolation with no extrapolation
- 10-second reconnect lease and player-slot reservation
- bounded Android reconnect attempts using protocol RESUME
- reconnect/ACK/interpolation smoke coverage

### Still open in M1
- measured real WAN latency/jitter/loss soak
- interaction progress UI
- down/revive presentation
- objective/spatial audio

## M2 — Horror presentation
- authored facility mesh and collision export
- flashlight cone, shadows/fog, flicker zones
- spatial audio and hunter state audio language
- hiding spots / doors / authored line-of-sight occlusion
- animation and creature presentation

## M3 — Session depth
- randomized breaker subset / objective order
- consumable battery/fuse items
- hunter archetype modifiers
- difficulty director based on team separation/noise
- post-match survival summary

## M4 — Internet productization
- hosted authority deployment
- authenticated matchmaking / room service
- secure transport
- reconnect across process/network loss
- observability, rate limits, crash reporting and release signing
