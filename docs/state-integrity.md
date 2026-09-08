# State integrity and resynchronization

SolarNet 0.3 adds an optional deterministic game-state contract on top of the transport/turn core.

## State-machine contract

Implement `ISolarGameStateMachine` when the game wants rule validation, state hashes, snapshots, and automatic recovery.

```csharp
public sealed class MyGameState : ISolarGameStateMachine
{
    public bool TryApply(SolarGameAction action)
    {
        // Validate + apply synchronously and deterministically.
        // Return false without mutating state when the action is illegal.
    }

    public byte[] CaptureSnapshot()
    {
        // Return canonical bytes. Same logical state => exactly the same bytes.
    }

    public void RestoreSnapshot(byte[] snapshot)
    {
        // Restore so CaptureSnapshot() reproduces the same canonical bytes.
    }
}
```

The state machine must not depend on wall-clock time, unseeded randomness, frame rate, device locale, iteration order of unstable collections, or platform-specific floating-point behavior when those can change the resulting snapshot. If the game needs randomness, encode the deterministic PRNG state/seed into the synchronized game state.

When a state machine is attached, `ActionCommitted` is a notification after the engine has applied the action. Do not apply the same action a second time from that event.

## Host commit path

```text
turn identity/sequence validation
       |
       v
ISolarGameStateMachine.TryApply
       | reject -> GameRuleRejected, no turn advance
       v
advance TurnCoordinator
       |
       v
canonical snapshot -> SHA-256 digest
       |
       +-> bounded action journal
       +-> TurnCommitted(stateHash) broadcast
```

The game-rule callback is invoked only after turn ownership/index/sequence checks pass. Its rejection contract is transactional: returning `false` must leave the state unchanged.

## Client integrity path

A client applies authoritative commits only at its exact `KnownNextTurnIndex`.

- older committed turn -> duplicate/replay already applied, ignore;
- exact next turn -> apply once;
- future committed turn -> detect a gap and request replay from the missing turn.

After applying a commit, the client hashes its canonical snapshot. If the digest differs from the host's digest, SolarNet raises `StateMismatchDetected` and requests resynchronization.

## Recovery ladder

The host keeps a bounded committed-action journal (default 256 turns).

```text
client requests from turn N
        |
        +-- complete N..current-1 journal range exists
        |       -> replay missing TurnCommitted packets
        |
        +-- range missing OR client already at current turn but hash differs
                -> authoritative snapshot
```

A snapshot contains authoritative game bytes plus current player, round, and next turn index. The client verifies the digest before restoring, restores the bytes, then captures and hashes state again. A restore that cannot round-trip to the authoritative digest is treated as a protocol/state-machine fault rather than silently accepted.

Stale snapshots never rewind a client that has already advanced past that snapshot's turn index.

If the host has no state machine and the required journal range has expired, it returns `ResyncUnavailable` instead of pretending recovery succeeded.

## Reconnect usage

After the transport reports the peer as reconnected, the client can explicitly request catch-up:

```csharp
await session.RequestResyncAsync();
```

The request uses the client's current `KnownNextTurnIndex`. A gap detected while receiving an authoritative commit triggers the same mechanism automatically.

## Evidence boundary

CI proves three distinct state-boundary behaviors:

1. a game-rule rejection leaves both game state and turn index unchanged;
2. an intentionally corrupted client state produces a digest mismatch and is restored from an authoritative snapshot;
3. an intentionally dropped commit is recovered from the action journal without falling back to a snapshot when the complete range is retained.

These are deterministic in-memory protocol tests. They do not prove a real Android process survives OS kill/backgrounding, nor that a physical Nearby connection reconnects after radio loss. Those remain device/runtime evidence tasks.
