# Grid Duel integration sample

SolarNet 0.7 includes a small playable 5x5 turn game so engine correctness is exercised by actual game rules rather than protocol-only fixtures.

`GridDuelStateMachine` is intentionally pure C#. It implements `ISolarGameStateMachine`, uses canonical binary snapshots, and contains no Unity/networking code. The Unity `GridDuelLocalDemo` creates two independent state machines and two independent `SolarTurnSession` instances; only the display/input lives in Unity.

This separation demonstrates the intended game architecture:

```text
Unity input/rendering
      |
      v
SolarTurnSession ---- ISolarTransport
      |
      v
ISolarGameStateMachine
      |
      v
canonical game state
```

CI separately type-checks the Unity sample surface and runs a full networked match over LoopbackTransport. Invalid game moves must be rejected without advancing the turn, and both peers must end with byte-identical canonical state.
