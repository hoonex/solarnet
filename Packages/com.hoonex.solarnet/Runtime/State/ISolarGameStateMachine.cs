using System;

namespace SolarNet.State
{
    public sealed class SolarGameAction
    {
        public SolarGameAction(string actorId, long turnIndex, string actionKind, byte[] payload)
        {
            if (string.IsNullOrWhiteSpace(actorId)) throw new ArgumentException("Actor ID is required.", nameof(actorId));
            if (turnIndex < 0) throw new ArgumentOutOfRangeException(nameof(turnIndex));
            if (string.IsNullOrWhiteSpace(actionKind)) throw new ArgumentException("Action kind is required.", nameof(actionKind));

            ActorId = actorId;
            TurnIndex = turnIndex;
            ActionKind = actionKind;
            Payload = payload ?? Array.Empty<byte>();
        }

        public string ActorId { get; private set; }
        public long TurnIndex { get; private set; }
        public string ActionKind { get; private set; }
        public byte[] Payload { get; private set; }
    }

    public interface ISolarGameStateMachine
    {
        // Accepted actions must deterministically mutate state. Rejected actions must leave state unchanged.
        bool TryApply(SolarGameAction action);

        // The returned bytes must be a canonical representation: equal logical states must produce equal bytes.
        byte[] CaptureSnapshot();

        // After restore, CaptureSnapshot() must reproduce the same canonical bytes.
        void RestoreSnapshot(byte[] snapshot);
    }
}
