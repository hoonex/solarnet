using System;
using System.Collections.Generic;

namespace SolarNet.Turns
{
    public enum SolarTurnRejectReason : byte
    {
        None = 0,
        UnknownPlayer = 1,
        NotActivePlayer = 2,
        TurnIndexMismatch = 3,
        DuplicateOrOutOfOrderSequence = 4,
        InvalidAction = 5,
        ProtocolViolation = 6,
        GameRuleRejected = 7
    }

    public sealed class SolarTurnAction
    {
        public SolarTurnAction(string actorId, long expectedTurnIndex, long sequence, string actionKind, byte[] payload)
        {
            ActorId = actorId;
            ExpectedTurnIndex = expectedTurnIndex;
            Sequence = sequence;
            ActionKind = actionKind;
            Payload = payload ?? Array.Empty<byte>();
        }

        public string ActorId { get; private set; }
        public long ExpectedTurnIndex { get; private set; }
        public long Sequence { get; private set; }
        public string ActionKind { get; private set; }
        public byte[] Payload { get; private set; }
    }

    public sealed class SolarTurnCommit
    {
        public SolarTurnCommit(
            string actorId,
            long committedTurnIndex,
            string actionKind,
            byte[] payload,
            string nextPlayerId,
            long nextTurnIndex,
            int round,
            string stateHash = "")
        {
            ActorId = actorId;
            CommittedTurnIndex = committedTurnIndex;
            ActionKind = actionKind;
            Payload = payload ?? Array.Empty<byte>();
            NextPlayerId = nextPlayerId;
            NextTurnIndex = nextTurnIndex;
            Round = round;
            StateHash = stateHash ?? string.Empty;
        }

        public string ActorId { get; private set; }
        public long CommittedTurnIndex { get; private set; }
        public string ActionKind { get; private set; }
        public byte[] Payload { get; private set; }
        public string NextPlayerId { get; private set; }
        public long NextTurnIndex { get; private set; }
        public int Round { get; private set; }
        public string StateHash { get; private set; }

        public SolarTurnCommit WithStateHash(string stateHash)
        {
            return new SolarTurnCommit(ActorId, CommittedTurnIndex, ActionKind, Payload, NextPlayerId, NextTurnIndex, Round, stateHash);
        }
    }

    public sealed class TurnCoordinator
    {
        private readonly string[] _players;
        private readonly HashSet<string> _playerSet;
        private readonly Dictionary<string, long> _lastAcceptedSequence = new Dictionary<string, long>(StringComparer.Ordinal);
        private int _activePlayerIndex;

        public TurnCoordinator(IList<string> players)
        {
            if (players == null) throw new ArgumentNullException(nameof(players));
            if (players.Count < 2) throw new ArgumentException("SolarNet turn sessions require at least two players.", nameof(players));

            _players = new string[players.Count];
            _playerSet = new HashSet<string>(StringComparer.Ordinal);
            for (var i = 0; i < players.Count; i++)
            {
                var peerId = players[i];
                if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Player IDs cannot be empty.", nameof(players));
                if (!_playerSet.Add(peerId)) throw new ArgumentException("Player IDs must be unique: " + peerId, nameof(players));
                _players[i] = peerId;
            }
        }

        public long TurnIndex { get; private set; }
        public int Round { get; private set; } = 1;
        public string CurrentPlayerId { get { return _players[_activePlayerIndex]; } }

        public bool IsKnownPlayer(string peerId)
        {
            return !string.IsNullOrWhiteSpace(peerId) && _playerSet.Contains(peerId);
        }

        public bool TryCommit(SolarTurnAction action, out SolarTurnCommit commit, out SolarTurnRejectReason reason)
        {
            return TryCommit(action, null, out commit, out reason);
        }

        public bool TryCommit(
            SolarTurnAction action,
            Func<SolarTurnAction, bool> gameRuleValidator,
            out SolarTurnCommit commit,
            out SolarTurnRejectReason reason)
        {
            commit = null;
            reason = Validate(action);
            if (reason != SolarTurnRejectReason.None) return false;

            if (gameRuleValidator != null && !gameRuleValidator(action))
            {
                reason = SolarTurnRejectReason.GameRuleRejected;
                return false;
            }

            var committedTurnIndex = TurnIndex;
            _lastAcceptedSequence[action.ActorId] = action.Sequence;

            _activePlayerIndex++;
            if (_activePlayerIndex >= _players.Length)
            {
                _activePlayerIndex = 0;
                Round++;
            }
            TurnIndex++;

            commit = new SolarTurnCommit(
                action.ActorId,
                committedTurnIndex,
                action.ActionKind,
                action.Payload,
                CurrentPlayerId,
                TurnIndex,
                Round);
            return true;
        }

        private SolarTurnRejectReason Validate(SolarTurnAction action)
        {
            if (action == null || string.IsNullOrWhiteSpace(action.ActionKind) || action.Sequence < 0)
                return SolarTurnRejectReason.InvalidAction;
            if (!_playerSet.Contains(action.ActorId))
                return SolarTurnRejectReason.UnknownPlayer;
            if (!string.Equals(action.ActorId, CurrentPlayerId, StringComparison.Ordinal))
                return SolarTurnRejectReason.NotActivePlayer;
            if (action.ExpectedTurnIndex != TurnIndex)
                return SolarTurnRejectReason.TurnIndexMismatch;

            long lastSequence;
            if (_lastAcceptedSequence.TryGetValue(action.ActorId, out lastSequence) && action.Sequence <= lastSequence)
                return SolarTurnRejectReason.DuplicateOrOutOfOrderSequence;
            return SolarTurnRejectReason.None;
        }
    }
}
