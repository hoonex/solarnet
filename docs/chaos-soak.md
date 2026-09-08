# Deterministic chaos soak

SolarNet's CI includes a deterministic transport-fault soak that exercises recovery repeatedly instead of validating only one isolated failure.

## Scenario

The test runs a four-player authoritative turn session for 60 rounds / 240 committed turns. `peer-4` is wrapped in a fault-injection transport while the other peers use normal `LoopbackTransport` endpoints.

The fault schedule is reproducible with seed `20260908`. The first four rounds guarantee coverage of every fault class; the remaining rounds select from the same matrix with the fixed seed.

Injected faults:

- **drop** — discard one authoritative `TurnCommitted` packet, then require the next commit to expose a turn gap and automatic journal replay to recover it;
- **duplicate** — deliver the same authoritative commit twice and verify idempotent reducer/event handling;
- **reorder** — hold one commit, deliver the following commit first, recover the resulting gap, then release the stale held commit and verify it cannot rewind state;
- **state corruption** — mutate the client reducer state before a valid commit and require digest mismatch detection plus authoritative snapshot recovery;
- **clean** — normal delivery between fault rounds.

After every four-player round, all peers must agree on reducer bytes, state hash, next turn index, round number, and active player. The faulted peer must observe every authoritative commit exactly once at the application event layer despite packet duplication and replay.

## What this proves

This is a deterministic protocol/recovery soak. It proves repeated combinations of packet loss, duplicate delivery, reordering, replay, and snapshot recovery do not accumulate state divergence under the simulated transport.

It is **not** evidence about real Bluetooth/Nearby radio quality, OEM Android stack behavior, range, throughput, or background-process restrictions. Those require two physical Android devices and measured radio soak runs.
