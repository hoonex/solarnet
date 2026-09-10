# Relay Siege Deck Lab

Deck Lab is the player-facing custom-deck system introduced with Solar Arcade 0.4.0.

## Design contract

Relay Siege deck order is strategic state, not cosmetic ordering:

- exactly 8 unique cards;
- slots 1–4 form the opening hand;
- slot 5 is the first visible NEXT card;
- later slots define the subsequent cycle;
- replacing or reordering a slot immediately changes the opening/cycle behavior used by the real match engine.

The saved deck is encoded as the ordered list of canonical card IDs and stored locally by the Android app.

## Structural analysis

`RelaySiegeDeckLab.analyze(...)` derives explainable deck properties from canonical card data instead of assigning an opaque rating. It reports:

- average Flux;
- four-card cycle cost;
- win-condition count;
- dedicated anti-air coverage;
- splash answers;
- defensive-building pulls;
- control cards;
- cheap cycle cards;
- airborne pressure;
- durable frontliners;
- pressure / defense / cycle / air-coverage scores;
- human-readable strengths and structural warnings.

Warnings are intended as missing-tool diagnostics, not claims that a deck is objectively bad.

## Real-engine matchup probes

Deck Lab can run the current custom deck against Counterforge, Spark Cycle and Split Voltage. A probe uses the same:

- `RelaySiegeGame` fixed-step combat;
- `RelaySiegeAi` decision logic;
- card data and target rules;
- Flux and deck-cycle state;
- relay/core win conditions.

Both sides make real AI decisions. Apply order alternates by decision bucket to avoid hard-coding one player as always first inside a simultaneous decision window. The result is deterministic for a fixed deck and seed.

A probe is a repeatable tactical sample, **not** a statistically sufficient balance or win-rate claim. Full balance conclusions would require many seeds, matchups and eventually human/device playtesting.

## Android UI

`RelaySiegeDeckLabActivity` provides:

1. ordered slot editing with explicit HAND / NEXT / CYCLE labels;
2. card catalog replacement with duplicate prevention;
3. starter-deck presets;
4. structural analysis and warnings;
5. background matchup probes so simulation does not block the main thread;
6. opponent-deck selection;
7. direct custom-deck AI playtest through `RelaySiegeArenaView`.

The activity pauses the live arena with the Android lifecycle and shuts down its probe executor on destroy.

## Verification boundary

CI covers ordered-deck round trips, duplicate rejection, coverage-gap detection, deterministic real-engine probes, existing Relay Siege combat/AI tests, and Android APK compilation.

Until exercised on a physical phone, Deck Lab UI reachability, touch ergonomics, font scaling, frame pacing, battery and thermal behavior remain unverified device-level evidence.
