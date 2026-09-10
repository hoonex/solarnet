# Pulse Hockey 3D

Pulse Hockey 3D is Solar Arcade's first deliberately deeper game. It replaces the structurally simple move/attack loop of Grid Duel with deterministic 3D arcade physics plus turn-based tactical decisions.

## Match structure

- first to 5 goals wins;
- one action per turn;
- opening initiative is derived from the match seed rather than permanently assigned to SUN;
- after a goal, the side that conceded receives the next action;
- after 36 turns, the higher score wins and an exact tie is a draw.

The finite turn cap is a hard rules invariant. Two players can choose defensive actions forever in intent, but the match itself cannot become infinite.

## Tactics and energy

Each side has up to 5 energy.

- **STRIKE** — normal physical shot, cost 1;
- **POWER** — faster shot, cost 3;
- **GUARD** — widens the effective defensive mallet for one opposing attack and restores energy;
- **COUNTER** — arms a one-attack reflection that boosts the returned puck speed.

A defensive stance expires after exactly one opposing attack. It is not a permanent shield.

## Overdrive anti-stall rule

Every non-scoring turn increases `noGoalTurns`. Once the stall passes the early match window, Overdrive progressively:

1. widens both goals;
2. increases strike velocity;
3. pushes the tactical AI away from passive Guard/Counter choices.

Goal width is bounded, so the arena remains playable rather than turning the entire end wall into a goal.

## Physics

The deterministic simulation uses a fixed 60 Hz timestep with bounded simulation steps per action.

It includes:

- puck and mallet velocity;
- side/end-wall collisions;
- circular mallet-puck impulse resolution;
- separate puck and mallet friction;
- player-half movement constraints;
- Guard damping and Counter reflection multipliers;
- recorded motion frames for the Android turn animation.

The same action from the same state produces the same authoritative result.

## Tactical AI

The AI does not simply move toward the puck. It generates several direct shots, offset hits, bank-shot families, Power shots and situational defensive actions. For promising first moves it simulates opponent responses and evaluates the resulting state.

An early CI version exposed a design problem: two identical AIs selected safe Guard/Counter behavior often enough that too few games were decisive. The test was kept and the AI was changed instead. Defense is now situational, and Overdrive level 2+ removes defensive candidates so identical agents cannot mirror passive play indefinitely.

## 3D renderer

`PulseHockey3DView` is backed by `GLSurfaceView` and OpenGL ES 2.0. It uses custom vertex/fragment shaders, a perspective camera, depth testing, directional lighting, and procedural cube/cylinder meshes.

Rendered objects include:

- thick arena board;
- side and end walls;
- dynamic goal openings and posts;
- cylindrical 3D puck;
- stacked cylindrical mallets;
- turn/stance highlights;
- aim/power indicator;
- Overdrive visual state.

This is actual 3D geometry, not a 2D Canvas transformed to resemble perspective.

### Overview follow camera — Solar Arcade 0.6.1

The original renderer used one fixed 48-degree perspective camera at `(0, 13.4, -16.2)`. On a portrait phone that produced a narrow horizontal field of view and could make the rink feel unnecessarily close.

0.6.1 moves camera composition into `PulseHockeyCameraRig`:

- vertical FOV is widened to 58 degrees;
- eye height is fitted from the full rink width and length before any action following is applied;
- the puck leads a bounded focus target while both mallets anchor the composition;
- focus movement is clamped so the camera follows play without chasing one object off-center;
- action near a goal or side wall increases eye height, producing extra zoom-out instead of a close-up;
- focus and zoom move through a low-pass blend to avoid camera snaps;
- very narrow portrait aspect ratios receive at least as much overview distance as wider portrait screens.

The pure-Java camera smoke test locks these composition rules and verifies conservative rink-width/rink-length coverage calculations. It does not substitute for visual inspection on a physical phone.

## Current play modes

Pulse Hockey 3D intentionally ships with:

- AI Battle;
- same-phone Local 2P.

Bluetooth/Nearby are not copied over merely to increase the mode count. Grid Duel Classic retains those existing modes. Pulse Hockey networking should be added only after the new game loop and device interaction are verified.

## CI acceptance checks

The pure-Java game and camera smoke tests require:

- an all-defense match terminates at exactly the hard turn cap;
- both sides receive opening initiative across seeded matches;
- tactical energy and stance expiry remain valid;
- AI only emits legal actions;
- seeded AI-vs-AI matches all terminate;
- enough matches are decisive to measure initiative;
- the opening player does not exceed 75% of decisive wins in the checked sample;
- both SUN and MOON can win;
- AI does not collapse into mostly draws;
- portrait camera framing retains conservative full-rink width and length margins;
- camera focus follows either end of the rink but remains bounded;
- edge action increases zoom-out distance;
- follow and zoom transitions do not snap directly to the target.

The Android job then compiles the OpenGL/game activities, verifies Solar Arcade 0.6.1 package identity, computes a SHA-256 digest, and uploads the debug APK artifact.

## Evidence boundary

CI can prove deterministic rule tests, deterministic camera-policy tests, source compilation, Android packaging, application metadata and artifact generation. It does **not** prove real-phone touch ergonomics, OpenGL appearance, frame pacing, thermal behavior, power draw or OEM-specific runtime behavior. Those remain device-runtime evidence and must be reported separately after physical testing.
