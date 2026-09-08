using System;
using System.Security.Cryptography;
using System.Text;
using SolarNet.Session;

namespace SolarNet.Room
{
    public sealed class SolarHostMigrationPlan
    {
        internal SolarHostMigrationPlan(
            string roomId,
            string sourceHostPeerId,
            string successorPeerId,
            string sourceGameSessionId,
            string nextGameSessionId,
            long sourceRoomRevision,
            long checkpointNextTurnIndex,
            string checkpointStateHash,
            string[] playerIds)
        {
            RoomId = roomId;
            SourceHostPeerId = sourceHostPeerId;
            SuccessorPeerId = successorPeerId;
            SourceGameSessionId = sourceGameSessionId;
            NextGameSessionId = nextGameSessionId;
            SourceRoomRevision = sourceRoomRevision;
            CheckpointNextTurnIndex = checkpointNextTurnIndex;
            CheckpointStateHash = checkpointStateHash;
            PlayerIds = playerIds == null ? Array.Empty<string>() : (string[])playerIds.Clone();
        }

        public string RoomId { get; private set; }
        public string SourceHostPeerId { get; private set; }
        public string SuccessorPeerId { get; private set; }
        public string SourceGameSessionId { get; private set; }
        public string NextGameSessionId { get; private set; }
        public long SourceRoomRevision { get; private set; }
        public long CheckpointNextTurnIndex { get; private set; }
        public string CheckpointStateHash { get; private set; }
        public string[] PlayerIds { get; private set; }

        public bool IsSuccessor(string peerId)
        {
            return !string.IsNullOrWhiteSpace(peerId) && string.Equals(peerId, SuccessorPeerId, StringComparison.Ordinal);
        }
    }

    public static class SolarHostMigrationPlanner
    {
        private const string EpochDomain = "SolarNet.HostMigration.v1";

        public static SolarHostMigrationPlan Create(SolarRoomSnapshot roomSnapshot, SolarAuthorityCheckpoint checkpoint)
        {
            if (roomSnapshot == null) throw new ArgumentNullException(nameof(roomSnapshot));
            if (checkpoint == null) throw new ArgumentNullException(nameof(checkpoint));
            if (roomSnapshot.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Host migration can only be planned from a Playing room snapshot.");
            if (string.IsNullOrWhiteSpace(roomSnapshot.GameSessionId))
                throw new InvalidOperationException("Playing room snapshot is missing its game session ID.");
            if (!string.Equals(roomSnapshot.GameSessionId, checkpoint.SourceSessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Room game session and authority checkpoint source session do not match.");

            var orderedPlayers = CloneAndSortPlayers(roomSnapshot.Players);
            if (orderedPlayers.Length != checkpoint.PlayerIds.Length)
                throw new InvalidOperationException("Room roster and authority checkpoint player count do not match.");
            if (orderedPlayers.Length < 2)
                throw new InvalidOperationException("Host migration requires at least one successor player.");

            var foundSourceHost = false;
            string successorPeerId = null;
            var playerIds = new string[orderedPlayers.Length];
            for (var i = 0; i < orderedPlayers.Length; i++)
            {
                var roomPlayer = orderedPlayers[i];
                var checkpointPlayerId = checkpoint.PlayerIds[i];
                if (!string.Equals(roomPlayer.PeerId, checkpointPlayerId, StringComparison.Ordinal))
                    throw new InvalidOperationException("Room slot order and authority checkpoint player order do not match.");

                playerIds[i] = roomPlayer.PeerId;
                if (string.Equals(roomPlayer.PeerId, roomSnapshot.HostPeerId, StringComparison.Ordinal))
                {
                    foundSourceHost = true;
                    continue;
                }

                // Safety-first policy: successor identity is based only on stable room slot order.
                // Transient connectivity flags are deliberately ignored so peers cannot elect
                // different authorities from slightly different link observations.
                if (successorPeerId == null) successorPeerId = roomPlayer.PeerId;
            }

            if (!foundSourceHost)
                throw new InvalidOperationException("Room snapshot does not contain its declared host peer.");
            if (successorPeerId == null)
                throw new InvalidOperationException("Room snapshot has no successor candidate.");

            var nextGameSessionId = DeriveNextGameSessionId(
                roomSnapshot.RoomId,
                roomSnapshot.GameSessionId,
                roomSnapshot.HostPeerId,
                successorPeerId,
                checkpoint.NextTurnIndex,
                checkpoint.StateHash,
                playerIds);

            return new SolarHostMigrationPlan(
                roomSnapshot.RoomId,
                roomSnapshot.HostPeerId,
                successorPeerId,
                roomSnapshot.GameSessionId,
                nextGameSessionId,
                roomSnapshot.Revision,
                checkpoint.NextTurnIndex,
                checkpoint.StateHash,
                playerIds);
        }

        private static SolarRoomPlayer[] CloneAndSortPlayers(SolarRoomPlayer[] players)
        {
            if (players == null) return Array.Empty<SolarRoomPlayer>();
            var copy = (SolarRoomPlayer[])players.Clone();
            Array.Sort(copy, (left, right) => left.Slot.CompareTo(right.Slot));
            for (var i = 1; i < copy.Length; i++)
            {
                if (copy[i - 1].Slot == copy[i].Slot)
                    throw new InvalidOperationException("Room snapshot contains duplicate player slots.");
            }
            return copy;
        }

        private static string DeriveNextGameSessionId(
            string roomId,
            string sourceGameSessionId,
            string sourceHostPeerId,
            string successorPeerId,
            long nextTurnIndex,
            string stateHash,
            string[] playerIds)
        {
            var builder = new StringBuilder();
            builder.Append(EpochDomain).Append('\n');
            builder.Append(roomId).Append('\n');
            builder.Append(sourceGameSessionId).Append('\n');
            builder.Append(sourceHostPeerId).Append('\n');
            builder.Append(successorPeerId).Append('\n');
            builder.Append(nextTurnIndex).Append('\n');
            builder.Append(stateHash).Append('\n');
            for (var i = 0; i < playerIds.Length; i++) builder.Append(i).Append(':').Append(playerIds[i]).Append('\n');

            byte[] digest;
            using (var sha = SHA256.Create())
                digest = sha.ComputeHash(Encoding.UTF8.GetBytes(builder.ToString()));
            return "solarnet-migration-" + ToHex(digest);
        }

        private static string ToHex(byte[] bytes)
        {
            const string alphabet = "0123456789abcdef";
            var chars = new char[bytes.Length * 2];
            for (var i = 0; i < bytes.Length; i++)
            {
                chars[i * 2] = alphabet[(bytes[i] >> 4) & 0xf];
                chars[(i * 2) + 1] = alphabet[bytes[i] & 0xf];
            }
            return new string(chars);
        }
    }
}
