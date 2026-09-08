using System;
using System.Collections.Generic;
using SolarNet.State;
using SolarNet.Transport;
using SolarNet.Turns;

namespace SolarNet.Session
{
    public sealed class SolarAuthorityCheckpoint
    {
        public SolarAuthorityCheckpoint(
            string sourceSessionId,
            IList<string> playerIds,
            long nextTurnIndex,
            string currentPlayerId,
            int round,
            string stateHash,
            byte[] state)
        {
            if (string.IsNullOrWhiteSpace(sourceSessionId)) throw new ArgumentException("Source session ID is required.", nameof(sourceSessionId));
            if (playerIds == null) throw new ArgumentNullException(nameof(playerIds));
            if (playerIds.Count < 2) throw new ArgumentException("Authority checkpoints require at least two players.", nameof(playerIds));
            if (nextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(nextTurnIndex));
            if (string.IsNullOrWhiteSpace(currentPlayerId)) throw new ArgumentException("Current player ID is required.", nameof(currentPlayerId));
            if (round < 1) throw new ArgumentOutOfRangeException(nameof(round));
            if (state == null) throw new ArgumentNullException(nameof(state));
            if (string.IsNullOrWhiteSpace(stateHash)) throw new ArgumentException("State hash is required.", nameof(stateHash));

            var players = new string[playerIds.Count];
            var unique = new HashSet<string>(StringComparer.Ordinal);
            var includesCurrent = false;
            for (var i = 0; i < playerIds.Count; i++)
            {
                var peerId = playerIds[i];
                if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Player IDs cannot be empty.", nameof(playerIds));
                if (!unique.Add(peerId)) throw new ArgumentException("Player IDs must be unique: " + peerId, nameof(playerIds));
                players[i] = peerId;
                if (string.Equals(peerId, currentPlayerId, StringComparison.Ordinal)) includesCurrent = true;
            }
            if (!includesCurrent) throw new ArgumentException("Current player must exist in the checkpoint player order.", nameof(currentPlayerId));

            var stateCopy = Clone(state);
            var computedHash = SolarStateDigest.Compute(stateCopy);
            if (!string.Equals(computedHash, stateHash, StringComparison.Ordinal))
                throw new ArgumentException("Checkpoint state hash does not match checkpoint bytes.", nameof(stateHash));

            // Validates that turn, round, and current player form one deterministic coordinator state.
            TurnCoordinator.Restore(players, nextTurnIndex, currentPlayerId, round);

            SourceSessionId = sourceSessionId;
            PlayerIds = players;
            NextTurnIndex = nextTurnIndex;
            CurrentPlayerId = currentPlayerId;
            Round = round;
            StateHash = stateHash;
            State = stateCopy;
        }

        public string SourceSessionId { get; private set; }
        public string[] PlayerIds { get; private set; }
        public long NextTurnIndex { get; private set; }
        public string CurrentPlayerId { get; private set; }
        public int Round { get; private set; }
        public string StateHash { get; private set; }
        public byte[] State { get; private set; }

        internal static byte[] Clone(byte[] value)
        {
            if (value == null || value.Length == 0) return Array.Empty<byte>();
            var copy = new byte[value.Length];
            Buffer.BlockCopy(value, 0, copy, 0, value.Length);
            return copy;
        }
    }

    public static class SolarAuthorityPromotion
    {
        public static SolarAuthorityCheckpoint Capture(
            SolarTurnSession synchronizedSession,
            IList<string> playerIds,
            ISolarGameStateMachine gameStateMachine)
        {
            if (synchronizedSession == null) throw new ArgumentNullException(nameof(synchronizedSession));
            if (gameStateMachine == null) throw new ArgumentNullException(nameof(gameStateMachine));
            if (!synchronizedSession.StateIntegrityEnabled)
                throw new InvalidOperationException("Authority checkpoint capture requires a state-integrity-enabled SolarTurnSession.");

            var state = gameStateMachine.CaptureSnapshot();
            if (state == null) throw new InvalidOperationException("ISolarGameStateMachine.CaptureSnapshot returned null.");
            var hash = SolarStateDigest.Compute(state);
            if (!string.Equals(hash, synchronizedSession.LastStateHash, StringComparison.Ordinal))
                throw new InvalidOperationException("Local game state is not synchronized with the session's last authoritative state hash.");

            return new SolarAuthorityCheckpoint(
                synchronizedSession.SessionId,
                playerIds,
                synchronizedSession.KnownNextTurnIndex,
                synchronizedSession.KnownCurrentPlayerId,
                synchronizedSession.KnownRound,
                hash,
                state);
        }

        public static SolarTurnSession CreatePromotedHost(
            string newSessionId,
            string newHostPeerId,
            ISolarTransport transport,
            SolarAuthorityCheckpoint checkpoint,
            ISolarGameStateMachine gameStateMachine,
            int journalCapacity = 256,
            string requiredReplicationPeerId = null)
        {
            if (string.IsNullOrWhiteSpace(newSessionId)) throw new ArgumentException("New session ID is required.", nameof(newSessionId));
            if (string.IsNullOrWhiteSpace(newHostPeerId)) throw new ArgumentException("New host peer ID is required.", nameof(newHostPeerId));
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            if (checkpoint == null) throw new ArgumentNullException(nameof(checkpoint));
            if (gameStateMachine == null) throw new ArgumentNullException(nameof(gameStateMachine));
            if (string.Equals(newSessionId, checkpoint.SourceSessionId, StringComparison.Ordinal))
                throw new ArgumentException("Authority promotion must rotate to a new session ID so stale packets from the previous authority epoch are fenced out.", nameof(newSessionId));
            if (!string.Equals(transport.LocalPeerId, newHostPeerId, StringComparison.Ordinal))
                throw new ArgumentException("Promoted host peer ID must match the transport local peer ID.", nameof(newHostPeerId));

            var knownHost = false;
            foreach (var playerId in checkpoint.PlayerIds)
                if (string.Equals(playerId, newHostPeerId, StringComparison.Ordinal)) { knownHost = true; break; }
            if (!knownHost) throw new ArgumentException("Promoted host must be one of the checkpoint players.", nameof(newHostPeerId));

            gameStateMachine.RestoreSnapshot(SolarAuthorityCheckpoint.Clone(checkpoint.State));
            var restoredBytes = gameStateMachine.CaptureSnapshot();
            if (restoredBytes == null) throw new InvalidOperationException("ISolarGameStateMachine.CaptureSnapshot returned null after checkpoint restore.");
            var restoredHash = SolarStateDigest.Compute(restoredBytes);
            if (!string.Equals(restoredHash, checkpoint.StateHash, StringComparison.Ordinal))
                throw new InvalidOperationException("Game state did not round-trip to the authority checkpoint digest.");

            var coordinator = TurnCoordinator.Restore(
                checkpoint.PlayerIds,
                checkpoint.NextTurnIndex,
                checkpoint.CurrentPlayerId,
                checkpoint.Round);

            return new SolarTurnSession(
                newSessionId,
                newHostPeerId,
                transport,
                coordinator,
                gameStateMachine,
                journalCapacity,
                requiredReplicationPeerId);
        }
    }
}
