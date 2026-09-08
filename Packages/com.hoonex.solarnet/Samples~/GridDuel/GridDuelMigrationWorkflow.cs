using System;
using SolarNet.Room;
using SolarNet.Session;
using SolarNet.Transport;

namespace SolarNet.Samples.GridDuel
{
    public sealed class GridDuelMigrationContext
    {
        internal GridDuelMigrationContext(
            SolarRoomSnapshot sourceRoom,
            SolarAuthorityCheckpoint checkpoint,
            SolarHostMigrationPlan plan)
        {
            SourceRoom = sourceRoom ?? throw new ArgumentNullException(nameof(sourceRoom));
            Checkpoint = checkpoint ?? throw new ArgumentNullException(nameof(checkpoint));
            Plan = plan ?? throw new ArgumentNullException(nameof(plan));
        }

        public SolarRoomSnapshot SourceRoom { get; private set; }
        public SolarAuthorityCheckpoint Checkpoint { get; private set; }
        public SolarHostMigrationPlan Plan { get; private set; }

        public bool IsSuccessor(string peerId)
        {
            return Plan.IsSuccessor(peerId);
        }
    }

    public static class GridDuelMigrationWorkflow
    {
        public static GridDuelMigrationContext Prepare(
            SolarRoomSnapshot sourceRoom,
            SolarTurnSession synchronizedGame,
            GridDuelStateMachine gameState)
        {
            if (sourceRoom == null) throw new ArgumentNullException(nameof(sourceRoom));
            if (synchronizedGame == null) throw new ArgumentNullException(nameof(synchronizedGame));
            if (gameState == null) throw new ArgumentNullException(nameof(gameState));
            if (sourceRoom.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Grid Duel migration requires a Playing room snapshot.");
            if (sourceRoom.Players == null || sourceRoom.Players.Length != 2)
                throw new InvalidOperationException("Grid Duel migration requires exactly two room players.");
            if (!string.Equals(sourceRoom.GameSessionId, synchronizedGame.SessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Grid Duel room and game session IDs do not match.");
            if (!string.Equals(sourceRoom.HostPeerId, synchronizedGame.HostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Grid Duel room and game authority identities do not match.");

            var players = (SolarRoomPlayer[])sourceRoom.Players.Clone();
            Array.Sort(players, (left, right) => left.Slot.CompareTo(right.Slot));
            if (players[0].Slot == players[1].Slot)
                throw new InvalidOperationException("Grid Duel room snapshot contains duplicate player slots.");

            var localFound = false;
            var playerIds = new string[players.Length];
            for (var i = 0; i < players.Length; i++)
            {
                playerIds[i] = players[i].PeerId;
                if (string.Equals(players[i].PeerId, synchronizedGame.LocalPeerId, StringComparison.Ordinal))
                    localFound = true;
            }
            if (!localFound)
                throw new InvalidOperationException("Grid Duel local peer is not part of the room snapshot.");

            // Capture is the safety gate: it refuses promotion when local game bytes no longer
            // match the last authoritative digest observed by the current turn session.
            var checkpoint = SolarAuthorityPromotion.Capture(synchronizedGame, playerIds, gameState);
            var plan = SolarHostMigrationPlanner.Create(sourceRoom, checkpoint);
            return new GridDuelMigrationContext(sourceRoom, checkpoint, plan);
        }

        public static SolarRoomMigrationBootstrap CreateRoom(
            GridDuelMigrationContext migration,
            ISolarTransport transport,
            SolarHostDisconnectPolicy hostDisconnectPolicy = SolarHostDisconnectPolicy.WaitForReconnect)
        {
            if (migration == null) throw new ArgumentNullException(nameof(migration));
            return SolarRoomMigration.CreateSession(
                migration.SourceRoom,
                migration.Plan,
                transport,
                hostDisconnectPolicy);
        }

        public static SolarTurnSession CreateGame(
            GridDuelMigrationContext migration,
            SolarRoomMigrationBootstrap roomBootstrap,
            ISolarTransport transport,
            GridDuelStateMachine gameState,
            int journalCapacity = 256)
        {
            if (migration == null) throw new ArgumentNullException(nameof(migration));
            if (roomBootstrap == null) throw new ArgumentNullException(nameof(roomBootstrap));
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            if (gameState == null) throw new ArgumentNullException(nameof(gameState));
            if (!string.Equals(roomBootstrap.GameStart.GameSessionId, migration.Plan.NextGameSessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migrated room and game epochs do not match.");
            if (!string.Equals(roomBootstrap.GameStart.HostPeerId, migration.Plan.SuccessorPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Migrated room authority does not match the elected successor.");

            if (roomBootstrap.IsSuccessor)
            {
                return SolarAuthorityPromotion.CreatePromotedHost(
                    migration.Plan.NextGameSessionId,
                    migration.Plan.SuccessorPeerId,
                    transport,
                    migration.Checkpoint,
                    gameState,
                    journalCapacity,
                    migration.SourceRoom.HostPeerId);
            }

            return new SolarTurnSession(
                migration.Plan.NextGameSessionId,
                migration.Plan.SuccessorPeerId,
                transport,
                null,
                gameState,
                journalCapacity);
        }
    }
}
