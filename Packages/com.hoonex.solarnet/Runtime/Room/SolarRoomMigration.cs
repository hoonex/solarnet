using System;
using SolarNet.Transport;

namespace SolarNet.Room
{
    public sealed class SolarRoomMigrationBootstrap
    {
        internal SolarRoomMigrationBootstrap(SolarRoomSession roomSession, SolarGameStartInfo gameStart, bool isSuccessor)
        {
            RoomSession = roomSession ?? throw new ArgumentNullException(nameof(roomSession));
            GameStart = gameStart ?? throw new ArgumentNullException(nameof(gameStart));
            IsSuccessor = isSuccessor;
        }

        public SolarRoomSession RoomSession { get; private set; }
        public SolarGameStartInfo GameStart { get; private set; }
        public bool IsSuccessor { get; private set; }
    }

    public static class SolarRoomMigration
    {
        public static SolarRoomMigrationBootstrap CreateSession(
            SolarRoomSnapshot sourceSnapshot,
            SolarHostMigrationPlan plan,
            ISolarTransport transport,
            SolarHostDisconnectPolicy hostDisconnectPolicy = SolarHostDisconnectPolicy.WaitForReconnect)
        {
            if (sourceSnapshot == null) throw new ArgumentNullException(nameof(sourceSnapshot));
            if (plan == null) throw new ArgumentNullException(nameof(plan));
            if (transport == null) throw new ArgumentNullException(nameof(transport));

            ValidateSource(sourceSnapshot, plan);
            var local = FindPlayer(sourceSnapshot, transport.LocalPeerId);
            if (local == null)
                throw new InvalidOperationException("Local transport peer is not part of the migration room roster.");

            var options = new SolarRoomOptions(
                sourceSnapshot.RoomId,
                plan.SuccessorPeerId,
                local.DisplayName,
                sourceSnapshot.CompatibilityKey,
                sourceSnapshot.RoomName,
                sourceSnapshot.MaxPlayers,
                hostDisconnectPolicy);

            var isSuccessor = plan.IsSuccessor(transport.LocalPeerId);
            var room = isSuccessor
                ? new SolarRoomSession(options, transport, sourceSnapshot, plan)
                : new SolarRoomSession(options, transport);
            var start = new SolarGameStartInfo(plan.NextGameSessionId, plan.SuccessorPeerId, plan.PlayerIds);
            return new SolarRoomMigrationBootstrap(room, start, isSuccessor);
        }

        private static void ValidateSource(SolarRoomSnapshot sourceSnapshot, SolarHostMigrationPlan plan)
        {
            if (sourceSnapshot.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Room migration bootstrap requires a Playing source snapshot.");
            if (!string.Equals(sourceSnapshot.RoomId, plan.RoomId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migration plan room ID does not match the source room snapshot.");
            if (!string.Equals(sourceSnapshot.HostPeerId, plan.SourceHostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migration plan source host does not match the source room snapshot.");
            if (!string.Equals(sourceSnapshot.GameSessionId, plan.SourceGameSessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migration plan source game session does not match the source room snapshot.");
            if (sourceSnapshot.Revision != plan.SourceRoomRevision)
                throw new InvalidOperationException("Migration plan source room revision does not match the source room snapshot.");

            var players = sourceSnapshot.Players == null ? Array.Empty<SolarRoomPlayer>() : (SolarRoomPlayer[])sourceSnapshot.Players.Clone();
            Array.Sort(players, (left, right) => left.Slot.CompareTo(right.Slot));
            if (players.Length != plan.PlayerIds.Length)
                throw new InvalidOperationException("Migration plan roster length does not match the source room snapshot.");
            for (var i = 0; i < players.Length; i++)
            {
                if (!string.Equals(players[i].PeerId, plan.PlayerIds[i], StringComparison.Ordinal))
                    throw new InvalidOperationException("Migration plan player order does not match the source room slot order.");
            }
        }

        private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
        {
            if (string.IsNullOrWhiteSpace(peerId)) return null;
            foreach (var player in snapshot.Players)
                if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
            return null;
        }
    }

    public sealed partial class SolarRoomSession
    {
        internal SolarRoomSession(
            SolarRoomOptions options,
            ISolarTransport transport,
            SolarRoomSnapshot sourceSnapshot,
            SolarHostMigrationPlan plan)
        {
            _options = options ?? throw new ArgumentNullException(nameof(options));
            _transport = transport ?? throw new ArgumentNullException(nameof(transport));
            if (sourceSnapshot == null) throw new ArgumentNullException(nameof(sourceSnapshot));
            if (plan == null) throw new ArgumentNullException(nameof(plan));
            if (!plan.IsSuccessor(_transport.LocalPeerId))
                throw new InvalidOperationException("Only the elected successor can seed migrated room authority.");
            if (!string.Equals(_options.HostPeerId, plan.SuccessorPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migrated room options must name the elected successor as host.");

            IsHost = true;
            _localPhase = SolarRoomPhase.Playing;
            _hostRevision = Math.Max(sourceSnapshot.Revision, plan.SourceRoomRevision) + 1;

            foreach (var sourcePlayer in sourceSnapshot.Players)
            {
                var connected = string.Equals(sourcePlayer.PeerId, LocalPeerId, StringComparison.Ordinal);
                _hostPlayers.Add(new SolarRoomPlayer(
                    sourcePlayer.Slot,
                    sourcePlayer.PeerId,
                    sourcePlayer.DisplayName,
                    sourcePlayer.IsReady,
                    connected));
            }
            SortHostPlayers();

            if (FindHostPlayer(LocalPeerId) < 0)
                throw new InvalidOperationException("Migrated room roster does not contain the elected successor.");

            _snapshot = BuildHostSnapshot(SolarRoomPhase.Playing, plan.NextGameSessionId);
            _lastStartedGameSessionId = plan.NextGameSessionId;
        }
    }
}
