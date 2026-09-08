using System;

namespace SolarNet.State
{
    public enum SolarStateMismatchReason
    {
        TurnGap = 1,
        DigestMismatch = 2,
        ReducerRejectedCommittedAction = 3
    }

    public sealed class SolarStateMismatch
    {
        public SolarStateMismatch(
            SolarStateMismatchReason reason,
            long localNextTurnIndex,
            long receivedTurnIndex,
            string expectedHash,
            string actualHash)
        {
            Reason = reason;
            LocalNextTurnIndex = localNextTurnIndex;
            ReceivedTurnIndex = receivedTurnIndex;
            ExpectedHash = expectedHash ?? string.Empty;
            ActualHash = actualHash ?? string.Empty;
        }

        public SolarStateMismatchReason Reason { get; private set; }
        public long LocalNextTurnIndex { get; private set; }
        public long ReceivedTurnIndex { get; private set; }
        public string ExpectedHash { get; private set; }
        public string ActualHash { get; private set; }
    }

    public sealed class SolarStateSnapshot
    {
        public SolarStateSnapshot(long nextTurnIndex, string currentPlayerId, int round, string stateHash, byte[] state)
        {
            if (nextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(nextTurnIndex));
            if (string.IsNullOrWhiteSpace(currentPlayerId)) throw new ArgumentException("Current player ID is required.", nameof(currentPlayerId));
            if (round < 1) throw new ArgumentOutOfRangeException(nameof(round));

            NextTurnIndex = nextTurnIndex;
            CurrentPlayerId = currentPlayerId;
            Round = round;
            StateHash = stateHash ?? string.Empty;
            State = state ?? Array.Empty<byte>();
        }

        public long NextTurnIndex { get; private set; }
        public string CurrentPlayerId { get; private set; }
        public int Round { get; private set; }
        public string StateHash { get; private set; }
        public byte[] State { get; private set; }
    }

    public enum SolarResyncFailureReason : byte
    {
        UnknownPlayer = 1,
        SnapshotUnavailable = 2,
        InvalidTurnIndex = 3,
        AutomaticRetryLimit = 4
    }

    public sealed class SolarResyncFailure
    {
        public SolarResyncFailure(long requestedNextTurnIndex, SolarResyncFailureReason reason)
        {
            RequestedNextTurnIndex = requestedNextTurnIndex;
            Reason = reason;
        }

        public long RequestedNextTurnIndex { get; private set; }
        public SolarResyncFailureReason Reason { get; private set; }
    }
}
