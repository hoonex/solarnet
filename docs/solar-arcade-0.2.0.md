# Solar Arcade 0.2.0

This milestone promotes the Android package to the Solar Arcade launcher and ships Pulse Hockey 3D as the primary game while preserving Grid Duel as Classic.

Pulse Hockey 3D includes deterministic fixed-step puck/mallet physics, STRIKE/POWER/GUARD/COUNTER tactics, energy, seeded opening initiative, loser-serves-next scoring flow, Overdrive anti-stall escalation, a 36-turn hard cap, tactical look-ahead AI, direct drag aiming, and an OpenGL ES 2.0 perspective arena.

The GL surface stops its renderer when detached so rematches or returning to the launcher do not leave an obsolete render loop active.

CI acceptance for this milestone includes Pulse Hockey termination/fairness smoke tests, Android compilation, Solar Arcade 0.2.0 package/label checks, SHA-256 generation, and the `solar-arcade-android-game-apk` artifact.

Real-device visual quality, touch feel, frame pacing, thermal behavior and physical-radio behavior remain separate device evidence and are not claimed by CI.
