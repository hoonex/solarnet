# Nightshift — game design contract

## Product thesis

Nightshift is a compact co-op horror game built around **shared information, noisy movement, and rescue pressure**, not combat. The monster is not a damage sponge and players do not win by shooting it. The team wins by completing a small number of risky objectives while preserving enough mobility to extract.

## Round structure

- Players: 1–4; co-op is the primary target, solo remains a debug/playable path.
- Session target: 8–15 minutes once content pacing exists.
- Phase: lobby → playing → won/lost.
- Primary objective: restore 3 breakers.
- Extraction: breaker completion unlocks the north exit; each survivor must interact at extraction.
- Downed state: hunter contact downs a player. A teammate holding USE nearby for 2 seconds revives them.
- Failure: all remaining players are downed before anyone extracts.
- Partial survival: if no active players remain but at least one already extracted, the session resolves as a win with casualties.

## Player verbs

- Walk: low noise, sustainable.
- Sprint: higher speed, drains stamina, creates a large hearing cue.
- Look: client camera orientation; yaw is included in authoritative movement input.
- Flashlight: desired on/off state is sent as input; battery is server-owned.
- Interact: breaker activation, revive and extraction use the same contextual verb.

## Hunter behavior

The hunter is fully authoritative and uses four states:

1. `ROAM` — patrol fixed facility anchors.
2. `INVESTIGATE` — move toward recent sprint/interaction noise.
3. `CHASE` — pursue a currently detected player; flashlight extends detection distance.
4. `SEARCH` — probe around the last known position before returning to patrol.

The client receives hunter state for presentation but cannot choose targets, attacks or movement.

## Facility v0.1

The facility is represented on the X/Z plane and rendered in first person. Shared geometry defines the same bounds and blocking walls for simulation and Android presentation. Three breaker anchors are deliberately separated across the map; extraction is placed beyond the last traversal zone.

## Deliberate omissions from v0.1

No weapons, inventory loot table, procedural map, voice chat, cosmetics, account system, monetization, persistence, skill progression or anti-cheat telemetry. These are excluded so the first milestone can prove the one thing the old project did not have: a coherent horror-game loop with a single multiplayer authority model.
