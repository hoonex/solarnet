# Solar Arcade

`android-smoke/grid-duel-game` builds the **Solar Arcade** Android app.

## 02 — Relay Siege

An original real-time two-lane deck strategy game built around card rotation, Flux trades, target rules, building pulls and defense-to-counterpush conversion.

- 8-card decks, 4-card hand and visible next card
- ground / air / splash / control / structure-priority interactions
- deterministic information-fair AI
- three starter archetypes with detailed deck guides
- guide examples replayed by the real combat engine
- AI practice arena

### Cycle Lab — Solar Arcade 0.6.0+

Cycle Lab makes exact hand order and resource timing visible instead of reducing cycle to one average number.

- reads the exact ordered 8-card deck, including the persistent Deck Lab custom deck
- opening slots 1–4 are analyzed as **spend now → recover later** routes
- slots 5–8 are analyzed as **draw-in** routes from the current opening hand and NEXT card
- every route uses the real `RelaySiegeGame.tryPlay()`, `getHand()`, `getNextCard()` and real Flux regeneration
- card-count distance is therefore locked to the gameplay hand-cycle implementation, not copied into a UI-only ruleset
- Flux/time evidence follows a deterministic cheapest-first cycle discipline and is labeled as that policy rather than claimed as a global optimum
- the route exposes the played-card sequence, final ready hand and next card
- CI proves the current slot invariant: after spending an opening-hand card, slot 1 returns after 4 additional plays while slot 4 returns after 7; future slot 5 draws after one play and slot 8 after four
- dedicated Cycle Lab CI reruns all starter decks twice and requires identical sequences, Flux, timing and canonical final state

### Tactical Trainer — Solar Arcade 0.5.0+

Tactical Trainer turns deck-guide advice into measured decisions instead of static tips.

- every lesson starts from one canonical battlefield and deterministic seed
- 3+ choices per lesson, including WAIT when strategically meaningful
- every choice is replayed by the real `RelaySiegeGame`
- results compare own Relay damage, enemy Relay damage, surviving friendly HP, remaining enemy threat, Flux spent and a lesson-specific trade score
- the teaching answer is CI-locked to the choice that actually wins under current engine rules
- the Android UI hides future results before the choice, then lets the player switch between their replay and the best-line replay
- replay final state is digest-locked to the state used by the trainer score, preventing guide animation from drifting away from gameplay
- initial lessons teach structure-target building pulls, splash-positive swarm defense, survivor-to-counterpush conversion, and the opposite decision when an undefended second lane makes a cheap split push more valuable than stacking the existing counterpush

### Deck Lab — Solar Arcade 0.4.0+

Deck Lab turns Relay Siege from fixed starter decks into a deck-building system.

- build an ordered 8-card deck; slots 1–4 are the opening hand and slot 5 is the first NEXT card
- reorder slots to deliberately shape opening and cycle order
- duplicate cards are rejected
- persistent local custom deck
- average Flux and four-card-cycle metrics
- structural coverage analysis for win conditions, anti-air, splash, defensive building pulls, frontline and control
- human-readable strengths and weakness warnings
- deterministic matchup probes against all three starter archetypes using the real `RelaySiegeGame` + `RelaySiegeAi`
- direct AI playtest with the exact custom deck and exact slot order

## 01 — Pulse Hockey 3D

A real OpenGL ES 3D turn-based air-hockey / tactics hybrid.

- AI Battle and same-phone Local 2P
- drag to aim and choose shot strength
- STRIKE / POWER / GUARD / COUNTER tactics
- deterministic puck / mallet collision simulation
- energy economy and one-attack defensive stances
- loser-serves-next and seeded opening initiative
- escalating Overdrive goal width and shot speed
- hard 36-turn cap so avoidance cannot create an infinite match
- CI AI-vs-AI checks for termination, decisive games and opening-player bias
- **0.6.1 overview camera:** wider 58° FOV, rink-fit minimum distance, bounded puck-led action following, edge-triggered extra zoom-out and smoothed camera motion
- camera-policy CI checks keep narrow portrait framing from regressing back to the old close fixed view

## 00 — Grid Duel Classic

The previous 5x5 Grid Duel remains available as a Classic game with AI, Local 2P, Bluetooth Classic and authenticated Nearby modes.

## APK

CI produces the installable debug artifact:

`solar-arcade-android-game-apk`

Application ID remains `com.hoonex.solarnet.gridduel`; Solar Arcade 0.6.1 uses versionCode 7.

CI proves source-level game tests, Pulse Hockey camera-policy invariants, Tactical Trainer outcome/replay agreement, Cycle Lab exact-hand invariants, Android compilation, package identity, checksum generation and APK artifact creation. Real-phone touch ergonomics, rendering appearance, frame pacing, thermal and power remain separate device evidence.
