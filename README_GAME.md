# Solar Arcade

`android-smoke/grid-duel-game` now builds the **Solar Arcade** Android app.

## 01 — Pulse Hockey 3D

The primary game is a real OpenGL ES 3D turn-based air-hockey / tactics hybrid.

- AI Battle and same-phone Local 2P
- drag to aim and choose shot strength
- STRIKE / POWER / GUARD / COUNTER tactics
- deterministic puck / mallet collision simulation
- energy economy and one-attack defensive stances
- loser-serves-next and seeded opening initiative
- escalating Overdrive goal width and shot speed
- hard 36-turn cap so avoidance cannot create an infinite match
- CI AI-vs-AI checks for termination, decisive games and opening-player bias

## 00 — Grid Duel Classic

The previous 5x5 Grid Duel remains available as a Classic game with AI, Local 2P, Bluetooth Classic and authenticated Nearby modes.

## APK

CI produces the installable debug artifact:

`solar-arcade-android-game-apk`

Application ID remains `com.hoonex.solarnet.gridduel`; Solar Arcade 0.2.0 uses versionCode 2.

See `docs/pulse-hockey-3d.md` and `docs/grid-duel-android-game.md` for rules and evidence boundaries.
