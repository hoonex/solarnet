# Nightshift roadmap

## M0 — Reboot foundation (this branch)

- remove the previous SolarNet/Solar Arcade/Bluetooth/Nearby product tree
- define the co-op horror loop and authority ownership
- implement shared facility/game simulation
- implement versioned binary protocol
- implement room-coded Java authority server
- prove a real localhost two-client session
- bootstrap a first-person Android OpenGL client with INTERNET-only permission
- produce Nightshift 0.1.0 debug APK in CI

## M1 — Real playable network feel

- client snapshot interpolation
- local movement prediction + server reconciliation
- reconnect lease and player slot reservation
- measured WAN soak under latency/jitter/loss
- interaction progress UI, down/revive presentation, objective audio

## M2 — Horror presentation

- authored facility mesh and collision export
- flashlight cone, shadows/fog, flicker zones
- spatial audio and hunter state audio language
- hiding spots / doors / line-of-sight occlusion authored from map data
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
