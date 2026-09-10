# Nightshift — game design contract

## Product thesis

Nightshift is a compact co-op horror game built around **shared information, noisy movement, resource transport, and rescue pressure**, not combat. The hunter is not a damage sponge and players do not win by shooting it.

## Round structure

- Players: 1–4.
- Target session: 8–15 minutes once authored content is mature.
- Phase: lobby → playing → won/lost.
- Three fuse pickups exist in separated facility zones.
- A player may carry one fuse at a time.
- Each breaker consumes one carried fuse.
- A shared security keycard must also be recovered.
- Extraction unlocks only after all three breakers are powered **and** the keycard is recovered.
- Hunter contact downs a player; teammate USE for 2 seconds revives.
- If no active players remain and nobody escaped, the session is lost. If at least one player already escaped, it resolves as a win with casualties.

## Horror pacing

`HorrorDirector` is deterministic and authority-owned.

- First blackout begins 18 seconds after actual match play starts.
- Blackouts repeat every 26 seconds and last 3.5 seconds.
- Restoring a breaker creates a six-second hunt surge.
- Powered breakers and active horror beats raise threat.
- Higher threat increases hunter movement/hearing; blackout reduces vision but increases hearing pressure.

This is intentionally server state, not random client-only effects, so every player shares the same beat.

## Player verbs

Walk, sprint, look, flashlight, and contextual USE remain the complete control surface. USE handles fuse pickup, keycard recovery, breaker insertion, revive, and extraction.

## Hunter

The hunter remains server-authoritative with `ROAM`, `INVESTIGATE`, `CHASE`, and `SEARCH`. It reacts to line of sight, sprint noise, interaction noise, objective progression, blackout state, and breaker surges.

## Presentation contract

The Android vertical slice uses simple procedural geometry but now expresses gameplay state through directional flashlight illumination, fog falloff, emergency lighting, visible objectives, breaker power state, extraction lock state, teammate fuse carry state, and hunter chase emphasis.

Authored mesh, shadows, animation and spatial audio remain future presentation work. Build success is not evidence of final visual or device quality.
